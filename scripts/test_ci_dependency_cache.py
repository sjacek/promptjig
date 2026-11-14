"""Exercise real archive handling without network, Gradle, Maven, model calls or fixture hooks."""
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import tomllib
import unittest
import zipfile
from unittest.mock import patch

import ci_dependency_cache as cache


class DependencyCacheTest(unittest.TestCase):
    FILE = 'gradle/caches/modules-2/files-2.1/org.example/library/1.0/' + '0' * 40 + '/library-1.0.jar'
    CONTENT = b'verified external dependency'

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.downloads = self.root / 'downloads'
        self.archive = self.root / 'snapshot.tar'
        policy = self.root / 'policy.json'
        policy.write_text(json.dumps({'schemaVersion': 1, 'gradleDistributions': {
            '9.9.1': hashlib.sha256(b'verified distribution').hexdigest()}, 'examples': [], 'mavenProjects': []}))
        metadata = self.root / 'verification-metadata.xml'
        metadata.write_text('''<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
            <configuration><verify-metadata>true</verify-metadata></configuration><components>
            <component group="org.example" name="library" version="1.0"><artifact name="library-1.0.jar">
            <sha256 value="''' + hashlib.sha256(self.CONTENT).hexdigest() + '''"/></artifact></component>
            </components></verification-metadata>''')
        for name, value in [('ROOT', self.root), ('CACHE', self.downloads), ('POLICY', policy), ('METADATA', metadata)]:
            patcher = patch.object(cache, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def write(self, name, content=CONTENT):
        target = self.downloads / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        return target

    def malicious_archive(self, name, kind=tarfile.REGTYPE, linkname=''):
        with tarfile.open(self.archive, 'w') as output:
            first = tarfile.TarInfo(self.FILE)
            first.size = len(self.CONTENT)
            output.addfile(first, io.BytesIO(self.CONTENT))
            entry = tarfile.TarInfo(name)
            entry.type = kind
            entry.linkname = linkname
            output.addfile(entry, io.BytesIO())

    def published_release(self):
        """Pin synthetic public payloads in both homes; no real release files or network are needed."""
        coordinate = 'io.github.sjacek.promptjig:promptjig-core:0.2.0'
        value = json.loads(cache.POLICY.read_text())
        value['publishedModules'] = [coordinate]
        cache.POLICY.write_text(json.dumps(value))
        payloads = {f'promptjig-core-0.2.0.{extension}': f'reviewed {extension}'.encode()
                    for extension in ('jar', 'pom', 'module')}
        artifacts = ''.join(f'<artifact name="{name}"><sha256 value="{hashlib.sha256(content).hexdigest()}"/></artifact>'
                            for name, content in payloads.items())
        component = ('<component group="io.github.sjacek.promptjig" name="promptjig-core" version="0.2.0">'
                     + artifacts + '</component>')
        cache.METADATA.write_text(cache.METADATA.read_text().replace('</components>', component + '</components>'))
        return {prefix + 'files-2.1/io.github.sjacek.promptjig/promptjig-core/0.2.0/' + '1' * 40 + '/' + name: content
                for prefix in cache.MODULES for name, content in payloads.items()}

    def test_missing_snapshot_is_cold_only_when_not_required(self):
        cache.restore(self.downloads, self.archive)
        self.assertFalse(self.downloads.exists())
        with self.assertRaisesRegex(ValueError, 'Required'):
            cache.restore(self.downloads, self.archive, required=True)

    def test_round_trip_transfers_only_downloads(self):
        retained = {self.FILE: self.CONTENT,
                    self.FILE.replace('gradle/caches', 'testkit/caches'): self.CONTENT,
                    'gradle/caches/modules-2/metadata-2.107/module-artifact.bin': b'resolver state',
                    'distributions/gradle/gradle-9.9.1-bin.zip': b'verified distribution'}
        excluded = ['gradle/gradle.properties', 'gradle/init.d/evil.gradle', 'gradle/daemon/daemon.log',
                    'gradle/caches/build-cache-1/output', 'gradle/caches/9.9.1/generated-gradle-jars/api.jar',
                    'gradle/caches/modules-2/modules-2.lock', 'testkit/test-kit-cache/metadata.bin',
                    'gradle/caches/modules-2/metadata-2.107/credentials.bin',
                    'project/build/classes/Compiled.class', 'project/build/reports/results.xml',
                    'project/.gradle/configuration-cache/state.bin', 'maven-user-home/settings.xml',
                    'target/it/build.log', 'gnupg/private.key', '.git/config',
                    self.FILE.replace('org.example', 'io.github.sjacek.promptjig')]
        for name, content in retained.items():
            self.write(name, content)
        for name in excluded:
            self.write(name, b'secret or local output')
        cache.pack(self.downloads, self.archive)
        restored = self.root / 'restored'
        cache.restore(restored, self.archive, required=True)
        actual = {path.relative_to(restored).as_posix(): path.read_bytes()
                  for path in restored.rglob('*') if path.is_file()}
        self.assertEqual(retained, actual)

    def test_compact_gradle_checksum_directories_are_preserved(self):
        # Real Gradle downloads include shorter storage hashes, e.g. the Lombok POM used by this build.
        for storage_hash in ('e5ad6781683659969d9da0658e938f42f694147', '1'):
            name = self.FILE.replace('0' * 40, storage_hash)
            self.write(name)
        cache.pack(self.downloads, self.archive)
        restored = self.root / 'restored'
        cache.restore(restored, self.archive, required=True)
        self.assertEqual(2, len([path for path in restored.rglob('*.jar') if path.is_file()]))

    def test_published_project_downloads_require_both_explicit_release_and_reviewed_checksum(self):
        coordinate = 'io.github.sjacek.promptjig:promptjig-core:0.2.0'
        name = self.FILE.replace('org.example/library/1.0', 'io.github.sjacek.promptjig/promptjig-core/0.2.0')
        name = name.replace('library-1.0.jar', 'promptjig-core-0.2.0.jar')
        self.assertFalse(cache.allowed(name))
        self.assertTrue(cache.allowed(name, [coordinate]))
        self.assertFalse(cache.allowed(name.replace('0.2.0', '0.3.0-SNAPSHOT'), [coordinate]))
        value = json.loads(cache.POLICY.read_text())
        value['publishedModules'] = [coordinate]
        cache.POLICY.write_text(json.dumps(value))
        metadata = cache.METADATA.read_text().replace('group="org.example" name="library" version="1.0"',
                'group="io.github.sjacek.promptjig" name="promptjig-core" version="0.2.0"')
        cache.METADATA.write_text(metadata.replace('library-1.0.jar', 'promptjig-core-0.2.0.jar'))
        self.write(name)
        cache.pack(self.downloads, self.archive)
        restored = self.root / 'restored'
        cache.restore(restored, self.archive, required=True)
        self.assertEqual(self.CONTENT, (restored / name).read_bytes())
        self.write(name, b'locally rebuilt output')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            cache.pack(self.downloads, self.archive)

    def test_published_payloads_round_trip_in_both_gradle_and_testkit_homes(self):
        payloads = self.published_release()
        for name, content in payloads.items():
            self.write(name, content)
        cache.pack(self.downloads, self.archive)
        restored = self.root / 'restored'
        cache.restore(restored, self.archive, required=True)
        self.assertEqual(payloads, {path.relative_to(restored).as_posix(): path.read_bytes()
                                    for path in restored.rglob('*') if path.is_file()})

    def test_forged_published_payloads_cannot_partially_replace_a_live_cache(self):
        for forged_name in self.published_release():
            with self.subTest(payload=forged_name):
                # A valid first member must not be installed before a later forged payload is checked.
                restored = self.root / 'live'
                existing = restored / self.FILE
                existing.parent.mkdir(parents=True, exist_ok=True)
                existing.write_bytes(b'previous cache contents')
                with tarfile.open(self.archive, 'w') as output:
                    for name, content in [(self.FILE, self.CONTENT), (forged_name, b'locally rebuilt payload')]:
                        entry = tarfile.TarInfo(name)
                        entry.size = len(content)
                        output.addfile(entry, io.BytesIO(content))
                with self.assertRaisesRegex(ValueError, 'checksum'):
                    cache.restore(restored, self.archive, required=True)
                self.assertEqual([existing], [path for path in restored.rglob('*') if path.is_file()])
                self.assertEqual(b'previous cache contents', existing.read_bytes())

    def test_published_path_exception_does_not_admit_other_modules_versions_or_outputs(self):
        payloads = self.published_release()
        published = cache.policy()['publishedModules']
        for name in payloads:
            for excluded in [name.replace('0.2.0', '0.2.1'), name.replace('0.2.0', '0.2.0-SNAPSHOT'),
                             name.replace('promptjig-core', 'promptjig-gradle-plugin'),
                             name.replace('io.github.sjacek.promptjig', 'io.github.sjacek.other'),
                             name + '.asc', name + '.tmp', 'project/build/libs/promptjig-core-0.2.0.jar']:
                with self.subTest(path=excluded):
                    self.assertFalse(cache.allowed(excluded, published))

    def test_release_allowlist_cannot_export_payloads_missing_from_the_reviewed_policy(self):
        payloads = self.published_release()
        name = next(iter(payloads))
        # The allowlist admits this classifier-shaped path, but the policy contains no hash for its bytes.
        name = name.replace('.jar', '-local.jar')
        self.write(name, b'local output with an allowed release coordinate')
        self.write(self.FILE)
        cache.pack(self.downloads, self.archive)
        with tarfile.open(self.archive) as snapshot:
            self.assertEqual([self.FILE], snapshot.getnames())

    def test_published_policy_requires_exact_project_release_coordinates(self):
        value = json.loads(cache.POLICY.read_text())
        for coordinate in ['io.github.sjacek.promptjig:*:0.2.0', 'io.github.sjacek.promptjig:promptjig-core:0.2.+',
                           'io.github.sjacek.promptjig:promptjig-core:0.2.0-SNAPSHOT',
                           'io.github.sjacek.promptjig:promptjig-core:[0.1,0.3)',
                           'io.github.sjacek.other:promptjig-core:0.2.0',
                           'io.github.sjacek.promptjig:../outside:0.2.0',
                           'io.github.sjacek.promptjig:promptjig-core:0.2.0:classifier']:
            with self.subTest(coordinate=coordinate):
                value['publishedModules'] = [coordinate]
                cache.POLICY.write_text(json.dumps(value))
                with self.assertRaisesRegex(ValueError, 'explicit release coordinates'):
                    cache.policy()

    def test_unsafe_paths_are_rejected_before_any_live_write(self):
        for name in ['../outside', '/outside', 'C:/outside', 'gradle/../outside', self.FILE + '/../outside',
                     self.FILE.replace('/', '\\'), self.FILE.replace('/library/', '//library/'),
                     self.FILE.replace('/library/', '/./library/'), self.FILE + '\n',
                     self.FILE.replace('org.example', '.. '), self.FILE.replace('org.example', 'NUL'),
                     self.FILE.replace('org.example', 'trailing.'), self.FILE + '\x7f',
                     'gradle/gradle.properties', 'maven/io/github/sjacek/promptjig-core/0.2.0/local.jar',
                     'maven/org/example/library/1.0/library-1.0.jar']:
            with self.subTest(name=name):
                self.malicious_archive(name)
                restored = self.root / 'restored'
                with self.assertRaises(ValueError):
                    cache.restore(restored, self.archive)
                self.assertFalse(restored.exists())

    def test_duplicate_and_case_colliding_members_are_rejected(self):
        for name in [self.FILE, self.FILE.replace('org.example', 'ORG.EXAMPLE')]:
            self.malicious_archive(name)
            with self.assertRaises(ValueError):
                cache.restore(self.root / 'restored', self.archive)

    def test_archive_links_and_special_files_are_rejected(self):
        for kind in [tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE, tarfile.CHRTYPE, tarfile.DIRTYPE]:
            with self.subTest(kind=kind):
                self.malicious_archive(self.FILE + '.pom', kind, '../../secret')
                with self.assertRaises(ValueError):
                    cache.restore(self.root / 'restored', self.archive)

    def test_corrupted_cached_artifact_is_rejected_even_with_a_valid_path(self):
        self.write(self.FILE)
        cache.pack(self.downloads, self.archive)
        with tarfile.open(self.archive, 'w') as output:
            entry = tarfile.TarInfo(self.FILE)
            entry.size = len(b'corrupt')
            output.addfile(entry, io.BytesIO(b'corrupt'))
        with self.assertRaisesRegex(ValueError, 'checksum'):
            cache.restore(self.root / 'restored', self.archive)
        self.assertFalse((self.root / 'restored').exists())

    def test_pack_rejects_corruption_without_replacing_previous_snapshot(self):
        target = self.write(self.FILE)
        cache.pack(self.downloads, self.archive)
        original = self.archive.read_bytes()
        target.write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            cache.pack(self.downloads, self.archive)
        self.assertEqual(original, self.archive.read_bytes())

    def test_distribution_checksum_is_rechecked_on_restore(self):
        self.malicious_archive('distributions/gradle/gradle-9.9.1-bin.zip')
        with self.assertRaisesRegex(ValueError, 'distribution checksum'):
            cache.restore(self.root / 'restored', self.archive)

    def distribution(self, entry='gradle-9.9.1/bin/gradle'):
        archive = self.write('distributions/gradle/gradle-9.9.1-bin.zip', b'')
        with zipfile.ZipFile(archive, 'w') as output:
            output.writestr(entry, b'verified launcher')
        value = json.loads(cache.POLICY.read_text())
        value['gradleDistributions']['9.9.1'] = cache.digest(archive)
        cache.POLICY.write_text(json.dumps(value))
        wrapper = self.root / 'gradle/wrapper/gradle-wrapper.properties'
        wrapper.parent.mkdir(parents=True, exist_ok=True)
        wrapper.write_text('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.9.1-bin.zip\n'
                           'distributionSha256Sum=' + cache.digest(archive) + '\n')
        return archive

    def test_distribution_hits_recreate_tools_from_verified_zip(self):
        archive = self.distribution()
        installation = self.root / 'build/ci-tools/gradle/gradle-9.9.1'
        (installation / 'bin').mkdir(parents=True)
        (installation / 'bin/gradle').write_bytes(b'untrusted previous tool')
        (installation / 'unexpected').write_bytes(b'previous build output')
        cache.distributions()
        self.assertEqual(b'verified launcher', (installation / 'bin/gradle').read_bytes())
        self.assertFalse((installation / 'unexpected').exists())
        seeded = list((self.downloads / 'gradle/wrapper/dists').rglob('*.zip'))
        self.assertEqual(1, len(seeded))
        self.assertEqual(archive.read_bytes(), seeded[0].read_bytes())
        archive.write_bytes(b'corrupt restored ZIP')
        with self.assertRaisesRegex(ValueError, 'corrupted'):
            cache.distributions()

    def test_distribution_zip_traversal_fails_before_extraction(self):
        self.distribution('gradle-9.9.1/../../outside')
        with self.assertRaisesRegex(ValueError, 'Unsafe Gradle distribution'):
            cache.distributions()
        self.assertFalse((self.root / 'build/ci-tools/outside').exists())

    def test_empty_snapshots_cannot_pretend_warmup_succeeded(self):
        self.downloads.mkdir()
        with self.assertRaisesRegex(ValueError, 'empty'):
            cache.pack(self.downloads, self.archive)
        with tarfile.open(self.archive, 'w'):
            pass
        with self.assertRaisesRegex(ValueError, 'Empty'):
            cache.restore(self.root / 'restored', self.archive, required=True)

    def test_size_limits_are_checked_before_live_writes(self):
        self.write(self.FILE)
        cache.pack(self.downloads, self.archive)
        for limit in ('MAX_FILE', 'MAX_TOTAL'):
            with self.subTest(limit=limit), patch.object(cache, limit, 1):
                with self.assertRaises(ValueError):
                    cache.restore(self.root / 'restored', self.archive)
                self.assertFalse((self.root / 'restored').exists())

    def test_older_compatible_cache_can_contain_unused_versions(self):
        old_name = self.FILE.replace('/1.0/', '/0.9/').replace('library-1.0.jar', 'library-0.9.jar')
        self.malicious_archive(old_name)
        restored = self.root / 'restored'
        cache.restore(restored, self.archive)
        self.assertTrue((restored / self.FILE).exists())
        cache.pack(restored, self.archive)
        with tarfile.open(self.archive) as snapshot:
            self.assertEqual([self.FILE], snapshot.getnames())

    def test_exported_hardlinks_are_regular_files(self):
        target = self.write(self.FILE)
        second = self.FILE.replace('gradle/caches', 'testkit/caches')
        other = self.downloads / second
        other.parent.mkdir(parents=True)
        os.link(target, other)
        cache.pack(self.downloads, self.archive)
        with tarfile.open(self.archive) as snapshot:
            self.assertTrue(all(item.isfile() for item in snapshot.getmembers()))

    def test_restore_replaces_hardlinks_without_modifying_external_files(self):
        self.write(self.FILE)
        cache.pack(self.downloads, self.archive)
        outside = self.root / 'outside'
        outside.write_bytes(b'outside stays unchanged')
        restored = self.root / 'restored'
        target = restored / self.FILE
        target.parent.mkdir(parents=True)
        os.link(outside, target)
        cache.restore(restored, self.archive)
        self.assertEqual(b'outside stays unchanged', outside.read_bytes())
        self.assertEqual(self.CONTENT, target.read_bytes())

    @unittest.skipIf(os.name == 'nt', 'Windows symlink creation needs a host privilege; CI executes this check')
    def test_live_destination_symlinks_and_export_symlinks_are_rejected(self):
        self.write(self.FILE)
        cache.pack(self.downloads, self.archive)
        restored = self.root / 'restored'
        outside = self.root / 'outside'
        outside.mkdir()
        restored.mkdir()
        (restored / 'gradle').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'escapes|symlink'):
            cache.restore(restored, self.archive)
        self.assertEqual([], list(outside.iterdir()))
        secret = self.root / 'secret'
        secret.write_bytes(b'secret')
        self.write(self.FILE).unlink()
        (self.downloads / self.FILE).symlink_to(secret)
        with self.assertRaisesRegex(ValueError, 'empty'):
            cache.pack(self.downloads, self.root / 'empty.tar')

    def test_future_maven_projects_cannot_be_silently_skipped(self):
        (self.root / 'pom.xml').write_text('<project/>')
        with self.assertRaisesRegex(ValueError, 'Maven'):
            cache.policy()

    def test_prepare_copies_policy_without_changing_trusted_checksums(self):
        value = json.loads(cache.POLICY.read_text())
        value['examples'] = ['examples/testdata']
        cache.POLICY.write_text(json.dumps(value))
        before = cache.METADATA.read_bytes()
        cache.prepare()
        self.assertEqual(before, cache.METADATA.read_bytes())
        self.assertEqual(before, (self.root / 'examples/testdata/gradle/verification-metadata.xml').read_bytes())

    def test_warmup_commands_propagate_failures_and_never_schedule_tests(self):
        launcher = self.root / 'build/ci-tools/gradle/gradle-9.9.1/bin' / ('gradle.bat' if os.name == 'nt' else 'gradle')
        launcher.parent.mkdir(parents=True)
        launcher.touch()
        with patch.object(cache, 'prepare'), patch.object(cache, 'distributions'), \
                patch.dict(os.environ, {'WARMUP_JAVA_HOME': 'synthetic-jdk-21'}), \
                patch.object(cache.subprocess, 'run') as run:
            cache.warm()
        calls = [call.args[0] for call in run.call_args_list]
        self.assertEqual(4, len(calls))
        self.assertTrue(any('ciResolveDependencies' in command for command in calls))
        self.assertTrue(any(':gradle-plugin:warmTestKitCache' in command for command in calls))
        self.assertTrue(all('--dependency-verification=strict' in command for command in calls[:2]))
        self.assertIn('--stop', calls[-1])
        self.assertIn('-Dorg.gradle.daemon.registry.base=' + str(self.downloads / 'testkit/test-kit-daemon'), calls[-1])
        self.assertTrue(all(call.kwargs['check'] for call in run.call_args_list))
        self.assertFalse(any(token in ('test', 'check', 'verify', 'integration-test', 'run')
                             for command in calls for token in command))

    def test_runtime_example_warmup_does_not_enable_source_substitution(self):
        value = json.loads(cache.POLICY.read_text())
        value['examples'] = ['examples/runtime']
        cache.POLICY.write_text(json.dumps(value))
        with patch.object(cache, 'prepare'), patch.object(cache, 'distributions'), \
                patch.dict(os.environ, {'WARMUP_JAVA_HOME': 'synthetic-jdk-21'}), \
                patch.object(cache.subprocess, 'run') as run:
            cache.warm()
        example_calls = [call for call in run.call_args_list if '-p' in call.args[0]]
        self.assertEqual(1, len(example_calls))
        command = example_calls[0].args[0]
        self.assertEqual('examples/runtime', command[command.index('-p') + 1])
        self.assertIn('ciResolveDependencies', command)
        self.assertIn('--dependency-verification=strict', command)
        self.assertTrue(example_calls[0].kwargs['check'])
        self.assertFalse(any('promptjigBuildDir' in token for token in command))
        self.assertFalse(any(token in ('test', 'check', 'run') for token in command))

    def test_resolution_failure_stops_warmup_before_testkit_and_example_execution(self):
        failure = cache.subprocess.CalledProcessError(1, 'ciResolveDependencies')
        with patch.object(cache, 'prepare'), patch.object(cache, 'distributions'), \
                patch.dict(os.environ, {'WARMUP_JAVA_HOME': 'synthetic-jdk-21'}), \
                patch.object(cache.subprocess, 'run', side_effect=failure) as run:
            with self.assertRaises(cache.subprocess.CalledProcessError):
                cache.warm()
        # CI must see the failure; later steps must not publish an incomplete yet apparently usable snapshot.
        self.assertEqual(1, run.call_count)
        self.assertIn('ciResolveDependencies', run.call_args.args[0])
        self.assertFalse(self.archive.exists())


class PublishedConsumerPolicyTest(unittest.TestCase):
    def test_runtime_catalog_releases_have_exact_cache_coordinates_and_reviewed_payloads(self):
        """A consumer upgrade must update both cache admission and independent byte verification."""
        root = Path(__file__).resolve().parents[1]
        versions = tomllib.loads((root / 'gradle/libs.versions.toml').read_text(encoding='utf-8'))['versions']
        published = cache.policy()['publishedModules']
        hashes = cache.checksums()
        group = 'io.github.sjacek.promptjig'
        for name, version, kinds in (
                (group + '.gradle.plugin', versions['promptjigPlugin'], ('pom',)),
                ('promptjig-gradle-plugin', versions['promptjigPlugin'], ('jar', 'pom', 'module')),
                ('promptjig-core', versions['promptjigRuntime'], ('jar', 'pom', 'module')),
                ('promptjig-spring-ai', versions['promptjigRuntime'], ('jar', 'pom', 'module')),
                # Compatible fallback snapshots still carry these exact, previously reviewed public releases.
                ('promptjig-core', '0.2.0', ('jar', 'pom', 'module')),
                ('promptjig-spring-ai', '0.2.0', ('jar', 'pom', 'module'))):
            with self.subTest(module=name, version=version):
                self.assertIn(f'{group}:{name}:{version}', published)
                for kind in kinds:
                    filename = f'{name}-{version}.{kind}'
                    self.assertIn((group, name, version, filename), hashes)
                    path = f'gradle/caches/modules-2/files-2.1/{group}/{name}/{version}/' + '1' * 40 + '/' + filename
                    self.assertTrue(cache.allowed(path, published))
                    self.assertFalse(cache.allowed(path.replace('/' + version + '/', '/' + version + '-SNAPSHOT/'), published))


if __name__ == '__main__':
    unittest.main()
