"""Offline Maven bootstrap/cache regressions; fixtures never load external code or run Maven hooks."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import xml.etree.ElementTree as ET

import ci_dependency_cache as cache
import maven_integration as integration
import maven_verification as maven


class MavenDependencyTest(unittest.TestCase):
    PAYLOAD = b'reviewed external Maven dependency'
    ID = 'org.example:library:jar:1.0'
    FILE = 'maven/repository/org/example/library/1.0/library-1.0.jar'

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.downloads = self.root / 'downloads'
        self.archive = self.root / 'snapshot.tar'
        self.pin = hashlib.sha256(self.PAYLOAD).hexdigest()
        for folder in ('.mvn', 'security', 'gradle'):
            (self.root / folder).mkdir()
        baseline = self.root / '.mvn/checksums.sha256'
        baseline.write_text(self.ID + ' ' + self.pin + '\n')
        verifier = {'version': '0.0.5', 'sha256': self.pin, 'url':
            'https://repo.maven.apache.org/maven2/codes/rafael/mavenchecksumextension/'
            'maven-checksum-extension/0.0.5/maven-checksum-extension-0.0.5.jar'}
        (self.root / 'security/dependency-verification.json').write_text(json.dumps(
            {'schemaVersion': 1, 'mavenExtension': verifier}))
        policy = self.root / 'gradle/ci-dependencies.json'
        policy.write_text(json.dumps({'schemaVersion': 1, 'gradleDistributions': {'9.9.1': self.pin},
            'mavenDistributions': {'3.9.16': self.pin, '4.0.0-rc-7': self.pin}, 'mavenProjects': [], 'examples': []}))
        metadata = self.root / 'gradle/verification-metadata.xml'
        metadata.write_text('<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
            '<configuration><verify-metadata>true</verify-metadata></configuration><components>'
            '<component group="org.example" name="library" version="1.0"><artifact name="library-1.0.jar">'
            '<sha256 value="' + self.pin + '"/></artifact></component></components></verification-metadata>')
        for module, name, value in [(cache, 'ROOT', self.root), (cache, 'CACHE', self.downloads),
                (cache, 'POLICY', policy), (cache, 'METADATA', metadata),
                (maven, 'ROOT', self.root), (maven, 'CACHE', self.downloads), (maven, 'BASELINE', baseline)]:
            context = patch.object(module, name, value)
            context.start()
            self.addCleanup(context.stop)

    def write(self, name, payload=PAYLOAD):
        path = self.downloads / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)
        return path

    def test_maven_round_trip_includes_only_reviewed_external_payloads_and_pinned_bootstrap(self):
        retained = [self.FILE, 'distributions/maven/apache-maven-3.9.16-bin.zip',
                    'maven/verifier/maven-checksum-extension-0.0.5.jar']
        excluded = ['maven/repository/settings.xml', 'maven/settings.xml',
            'maven/repository/org/example/library/1.0/_remote.repositories',
            'maven/repository/org/example/library/1.0/library-1.0.jar.lastUpdated',
            'maven/repository/io/github/sjacek/promptjig/promptjig-core/0.3.0/promptjig-core-0.3.0.jar',
            'maven/repository/io/github/sjacek/promptjig/promptjig-maven-plugin/0.3.0/promptjig-maven-plugin-0.3.0.jar',
            'maven/target/it/build.log', 'maven/repository/.credentials',
            'maven/repository/org/example/library/1.0/token.txt', 'maven/daemon/state',
            'maven/repository/org/example/unreviewed/1.0/unreviewed-1.0.jar']
        for name in retained + excluded:
            self.write(name)
        cache.pack(self.downloads, self.archive)
        destination = self.root / 'restored'
        cache.restore(destination, self.archive, required=True)
        self.assertEqual(set(retained), {p.relative_to(destination).as_posix()
                                       for p in destination.rglob('*') if p.is_file()})

    def test_corrupt_restored_maven_payload_is_rejected_before_any_live_file_changes(self):
        self.write(self.FILE)
        cache.pack(self.downloads, self.archive)
        with tarfile.open(self.archive, 'w') as output:
            entry = tarfile.TarInfo(self.FILE)
            entry.size = 7
            output.addfile(entry, io.BytesIO(b'changed'))
        target = self.root / 'restore'
        with self.assertRaisesRegex(ValueError, 'Maven artifact checksum'):
            cache.restore(target, self.archive, required=True)
        self.assertFalse(target.exists())

    def test_old_unused_maven_coordinate_is_not_promoted_without_current_trust(self):
        name = self.FILE.replace('library', 'old-library')
        with tarfile.open(self.archive, 'w') as output:
            entry = tarfile.TarInfo(name)
            entry.size = len(self.PAYLOAD)
            output.addfile(entry, io.BytesIO(self.PAYLOAD))
        target = self.root / 'restore'
        cache.restore(target, self.archive, required=True)
        self.assertFalse((target / name).exists())

    def test_checksum_baseline_rejects_duplicates_unsafe_coordinates_and_empty_policy(self):
        line = self.ID + ' ' + self.pin + '\n'
        for text in ('', line + line, line.replace('org.example', '../escape'),
                     line.replace(self.pin, '0' * 63), line.replace(':jar:', ':zip:')):
            with self.subTest(text=text):
                maven.BASELINE.write_text(text)
                with self.assertRaises(ValueError):
                    maven.load_checksums()

    def test_native_options_enforce_local_and_downloaded_bytes_and_reject_conflicting_overrides(self):
        self.write('maven/verifier/maven-checksum-extension-0.0.5.jar')
        env = maven.environment(original={'MAVEN_ARGS': '-DskipTests', 'MAVEN_CONFIG': 'secret-settings'})
        self.assertNotIn('MAVEN_ARGS', env)
        self.assertNotIn('MAVEN_CONFIG', env)
        for option in ('mode=enforce', 'algorithm=SHA-256', 'locals=true', 'snapshots=true', 'reactor=false'):
            self.assertIn(option, env['MAVEN_OPTS'])
        self.assertNotIn('collect', env['MAVEN_OPTS'])
        for override in ('-Dmaven.ext.class.path=other.jar', '-Dcodes.rafael.mavenchecksumextension.mode=collect'):
            with self.assertRaisesRegex(ValueError, 'Conflicting'):
                maven.environment(original={'MAVEN_OPTS': override})

    def test_extension_is_rechecked_before_each_native_invocation(self):
        file = self.write('maven/verifier/maven-checksum-extension-0.0.5.jar')
        self.assertEqual(file.resolve(), maven.extension())
        file.write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'corrupted Maven verifier'):
            maven.environment(original={})

    def test_extension_policy_cannot_redirect_bootstrap_to_a_foreign_server(self):
        policy = self.root / 'security/dependency-verification.json'
        value = json.loads(policy.read_text())
        value['mavenExtension']['url'] = 'https://example.invalid/verifier.jar'
        policy.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'Invalid Maven verifier policy'):
            maven.verifier_policy()

    def distribution_archive(self, name, symlink=False):
        target = self.downloads / 'distributions/maven/apache-maven-3.9.16-bin.zip'
        target.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(target, 'w') as output:
            info = zipfile.ZipInfo(name)
            info.external_attr = (0o120777 if symlink else 0o100755) << 16
            output.writestr(info, self.PAYLOAD)
        policy = json.loads(cache.POLICY.read_text())
        policy['mavenDistributions']['3.9.16'] = maven.digest(target)
        cache.POLICY.write_text(json.dumps(policy))
        return target

    def test_pinned_distribution_is_reextracted_and_rejects_traversal_symlinks_and_windows_paths(self):
        self.distribution_archive('apache-maven-3.9.16/bin/mvn')
        home = maven.distribution('3.9.16')
        executable = home / 'bin/mvn'
        executable.write_bytes(b'changed executable')
        self.assertEqual(self.PAYLOAD, (maven.distribution('3.9.16') / 'bin/mvn').read_bytes())
        for name, link in [('../escape', False), ('C:/escape', False),
                ('apache-maven-3.9.16/../escape', False), ('apache-maven-3.9.16/NUL.txt', False),
                ('apache-maven-3.9.16/link', True)]:
            with self.subTest(name=name):
                self.distribution_archive(name, link)
                with self.assertRaisesRegex(ValueError, 'Unsafe Maven archive'):
                    maven.distribution('3.9.16')

    def test_missing_or_corrupted_distribution_cannot_execute(self):
        with self.assertRaisesRegex(ValueError, 'Missing or corrupted'):
            maven.distribution('3.9.16')
        archive = self.distribution_archive('apache-maven-3.9.16/bin/mvn')
        archive.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'Missing or corrupted'):
            maven.distribution('3.9.16')
        with self.assertRaisesRegex(ValueError, 'exact reviewed'):
            maven.distribution('../escape')

    def test_native_command_has_clean_settings_private_repo_and_optional_offline_enforcement(self):
        with patch.object(maven, 'environment', return_value={'JAVA_HOME': 'fixture'}), \
                patch.object(maven.subprocess, 'run') as run:
            maven.run(self.root / 'tools', ['validate'], self.root / 'private', offline=True)
        command = run.call_args.args[0]
        self.assertIn('--offline', command)
        self.assertIn('-Dmaven.repo.local=' + str(self.root / 'private'), command)
        for flag in ('-s', '-gs'):
            self.assertEqual(str(self.root / '.mvn/settings.xml'), command[command.index(flag) + 1])
        self.assertTrue(run.call_args.kwargs['check'])

    def test_warmup_downloads_both_mavens_without_building_candidates_tests_or_hooks(self):
        original = integration.ROOT
        (self.root / 'maven-plugin/src/it').mkdir(parents=True)
        (self.root / 'maven-plugin/src/it/pom.xml').write_bytes((original / 'maven-plugin/src/it/pom.xml').read_bytes())
        (self.root / 'gradle/libs.versions.toml').write_bytes((original / 'gradle/libs.versions.toml').read_bytes())
        (self.root / 'examples/maven').mkdir(parents=True)
        (self.root / 'examples/maven/pom.xml').write_bytes((original / 'examples/maven/pom.xml').read_bytes())
        core = self.root / 'core/build/publications/core/pom-default.xml'
        core.parent.mkdir(parents=True)
        core.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<dependencyManagement><dependencies><dependency><groupId>org.example</groupId>'
            '<artifactId>core-bom</artifactId><version>1.0</version><type>pom</type><scope>import</scope>'
            '</dependency></dependencies></dependencyManagement><dependencies><dependency>'
            '<groupId>org.example</groupId><artifactId>core-library</artifactId><version>1.0</version>'
            '</dependency></dependencies></project>')
        with patch.object(integration, 'ROOT', self.root), patch.object(maven, 'extension'), \
                patch.object(maven, 'distribution', return_value=self.root / 'tool') as distributions, \
                patch.object(maven, 'run') as run:
            integration.warm()
        self.assertEqual(['3.9.16', '4.0.0-rc-7'], [c.args[0] for c in distributions.call_args_list])
        self.assertEqual(8, run.call_count)
        for version in ('3.9.16', '4.0.0-rc-7'):
            folder = self.root / 'build/cache-warmup/maven' / version
            driver = (folder / 'pom.xml').read_text()
            library = (folder / 'core-dependencies.xml').read_text()
            self.assertIn('junit-bom', driver)
            self.assertNotIn('core-bom', driver, 'The plugin BOM must not change consumer resolution')
            self.assertNotIn('core-bom', library)
            self.assertIn('<artifactId>promptjig-core</artifactId>', library)
            self.assertIn('<type>pom</type>', library)
            self.assertIn('core-bom', (folder / 'core-model-repository/io/github/sjacek/promptjig/promptjig-core/0.3.0/promptjig-core-0.3.0.pom').read_text())
            self.assertNotIn('<artifactId>junit-jupiter</artifactId>', library)
            example_model = ET.parse(folder / 'example-dependencies.xml').getroot()
            bootstrap = [p for p in example_model.findall('p:build/p:plugins/p:plugin', integration.NS)
                         if p.findtext('p:artifactId', namespaces=integration.NS) == 'maven-dependency-plugin']
            self.assertEqual([integration.versions()['mavenDependency']],
                             [p.findtext('p:version', namespaces=integration.NS) for p in bootstrap])
        for call in run.call_args_list:
            arguments = call.args[1]
            self.assertIn(integration.SERIAL_COLLECTOR, arguments)
            self.assertTrue(any(a.endswith((':go-offline', ':resolve', ':get')) for a in arguments))
            self.assertFalse(any(a in ('test', 'verify', 'install', 'compile', 'invoker:run') for a in arguments))
        with patch.object(integration, 'ROOT', self.root), patch.object(maven, 'extension'), \
                patch.object(maven, 'distribution', return_value=self.root / 'tool'), \
                patch.object(maven, 'run', side_effect=subprocess.CalledProcessError(1, 'fixture')):
            with self.assertRaises(subprocess.CalledProcessError):
                integration.warm()

    def test_authored_example_and_fixture_dependencies_are_covered_by_the_warmup_driver(self):
        root = integration.ROOT
        driver = integration.render(self.root / 'driver', self.root / 'tool', self.root / 'repo', self.root / 'projects')
        model = ET.parse(driver).getroot()
        ns = integration.NS
        def coordinates(node, plugins=False):
            return (node.findtext('p:groupId', namespaces=ns) or ('org.apache.maven.plugins' if plugins else ''),
                    node.findtext('p:artifactId', namespaces=ns), node.findtext('p:version', namespaces=ns))
        dependencies = {coordinates(d) for d in model.findall('p:dependencies/p:dependency', ns)}
        plugins = {coordinates(p, True) for p in model.findall('p:build/p:pluginManagement/p:plugins/p:plugin', ns)}
        sources = [root / 'examples/maven/pom.xml', *(root / 'maven-plugin/src/it/projects').rglob('pom.xml')]
        for path in sources:
            project = ET.parse(path).getroot()
            for dependency in project.findall('p:dependencies/p:dependency', ns):
                self.assertIn(coordinates(dependency), dependencies, str(path))
            for plugin in project.findall('p:build/p:plugins/p:plugin', ns):
                identity = coordinates(plugin, True)
                if identity[0] != 'io.github.sjacek.promptjig':
                    self.assertIn(identity, plugins, str(path))
        for properties in [root / 'examples/maven/invoker.properties',
                           *(root / 'maven-plugin/src/it/projects').glob('*/invoker.properties')]:
            text = properties.read_text()
            self.assertIn('invoker.offline = true', text, str(properties))
            self.assertNotIn('invoker.mavenOpts', text, 'Fixtures must not replace native verification options')
        example = ET.parse(root / 'examples/maven/pom.xml').getroot()
        self.assertIsNone(example.find('p:parent', ns), 'The standalone example must not inherit a producer checkout')
        self.assertEqual(integration.versions()['promptjig'], example.findtext('p:properties/p:promptjig.version', namespaces=ns))

    def test_candidate_hashes_are_private_and_match_exact_current_staged_bytes(self):
        with patch.object(integration, 'ROOT', self.root), patch.object(integration, 'versions', return_value={'promptjig': '0.3.0'}):
            for module, artifact in [('core', 'promptjig-core'), ('maven-plugin', 'promptjig-maven-plugin')]:
                folder = self.root / module / 'build/staging-repository/io/github/sjacek/promptjig' / artifact / '0.3.0'
                folder.mkdir(parents=True)
                for kind in ('jar', 'pom'):
                    (folder / f'{artifact}-0.3.0.{kind}').write_bytes((module + kind).encode())
            repository = self.root / 'private/repository'
            policy = integration.install_candidates(repository, self.root / 'private/checksums.sha256')
        values = maven.load_checksums(policy)
        self.assertEqual(5, len(values))
        for file in repository.rglob('*'):
            if file.is_file():
                identity = maven.artifact_identity(file.relative_to(repository).as_posix())
                self.assertEqual(maven.digest(file), values[identity])
                self.assertFalse(cache.allowed('maven/repository/' + file.relative_to(repository).as_posix()))

    def test_driver_escapes_paths_without_changing_their_xml_values(self):
        paths = [self.root / name for name in ('Maven & narzędzia', 'repo <external>', 'public & testdata')]
        pom = integration.render(self.root / 'driver', *paths)
        model = ET.parse(pom).getroot()
        for field, expected in zip(('mavenHome', 'localRepositoryPath', 'projectsDirectory'), paths):
            self.assertEqual(expected.as_posix(), model.findtext('.//p:' + field, namespaces=integration.NS))

    def test_missing_numeric_catalog_alias_fails_before_replacing_the_driver(self):
        directory = self.root / 'driver'
        directory.mkdir()
        target = directory / 'pom.xml'
        target.write_text('previous driver')
        pins = integration.versions()
        del pins['jackson3']
        with patch.object(integration, 'versions', return_value=pins):
            with self.assertRaisesRegex(ValueError, 'Unresolved Maven integration POM input'):
                integration.render(directory, self.root, self.root, self.root)
        self.assertEqual('previous driver', target.read_text())

    def test_failed_invoker_preserves_child_diagnostics_and_removes_stale_success(self):
        # Exercise the orchestration with real files; only the native process is substituted.
        for relative in ('maven-plugin/src/it/projects/first', 'examples/maven', 'downloads/maven/repository'):
            (self.root / relative).mkdir(parents=True)
        (self.root / 'maven-plugin/src/it/projects/first/pom.xml').write_text('<project/>')
        (self.root / 'examples/maven/pom.xml').write_text('<project/>')
        output = self.root / 'maven-plugin/build/reports/maven/3.9.16-jdk-17'
        output.mkdir(parents=True)
        summary = output / 'summary.json'
        summary.write_text('{"obsoleteSuccess": true}')

        def invoke(home, arguments, *args, **kwargs):
            self.assertTrue(kwargs['offline'])
            self.assertNotIn(integration.SERIAL_COLLECTOR, arguments)
            work = Path(arguments[arguments.index('-f') + 1]).parent
            files = {
                'target/invoker-reports/BUILD-first.xml': '<build-job result="failure"/>',
                'target/projects/first/build.log': 'original child failure',
                'target/projects/first/target/surefire-reports/TEST-example.xml': '<testsuite errors="1"/>',
                'target/projects/first/target/classes/private.class': 'must not be copied',
            }
            for relative, text in files.items():
                file = work / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(text)
            kwargs['stdout'].write('Invoker failed\n')
            raise subprocess.CalledProcessError(1, 'fixture')

        with patch.object(integration, 'ROOT', self.root), \
                patch.object(integration, 'java_version', return_value='17'), \
                patch.object(integration, 'versions', return_value={'mavenInvoker': '3.9.2'}), \
                patch.object(integration, 'render', side_effect=lambda work, *args: work / 'pom.xml'), \
                patch.object(integration, 'install_candidates', return_value=self.root / 'private-policy'), \
                patch.object(maven, 'distribution', return_value=self.root / 'maven'), \
                patch.object(maven, 'run', side_effect=invoke), \
                patch.object(integration, 'invoker_results') as results, \
                patch.object(integration, 'reject_tampered_local_artifacts') as tamper:
            with self.assertRaises(subprocess.CalledProcessError):
                integration.verify('3.9.16', 'fixture-jdk')
        self.assertFalse(summary.exists())
        self.assertEqual('Invoker failed\n', (output / 'maven.log').read_text())
        self.assertTrue((output / 'invoker-reports/BUILD-first.xml').is_file())
        self.assertEqual('original child failure', (output / 'fixtures/first/build.log').read_text())
        self.assertTrue((output / 'fixtures/first/target/surefire-reports/TEST-example.xml').is_file())
        self.assertFalse((output / 'fixtures/first/target/classes/private.class').exists())
        results.assert_not_called()
        tamper.assert_not_called()

    def test_private_warmup_promotes_only_reviewed_external_files_and_rejects_corruption(self):
        private = self.root / 'private'
        relative = self.FILE.removeprefix('maven/repository/')
        file = private / relative
        file.parent.mkdir(parents=True)
        file.write_bytes(self.PAYLOAD)
        for name in ('settings.xml', 'io/github/sjacek/promptjig/promptjig-core/0.3.0/promptjig-core-0.3.0.pom',
                     'org/example/unreviewed/1.0/unreviewed-1.0.jar'):
            target = private / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b'private or unreviewed')
        destination = self.root / 'shared'
        integration.promote_external_downloads(private, destination)
        self.assertEqual([relative], [p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()])
        file.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'corrupted Maven dependency'):
            integration.promote_external_downloads(private, destination)
        self.assertEqual(self.PAYLOAD, (destination / relative).read_bytes())

    def test_invoker_requires_exact_fixture_names_and_nonzero_successful_executions(self):
        reports = self.root / 'reports'
        reports.mkdir()
        first = reports / 'BUILD-first.xml'
        second = reports / 'BUILD-second.xml'
        valid = '<build-job project="first/pom.xml" result="success" executionCount="1"/>'
        first.write_text(valid)
        second.write_text(valid.replace('first', 'second'))
        self.assertEqual(2, len(integration.invoker_results(reports, ['first', 'second'])))
        for wrong in (valid, valid.replace('first', 'unrelated'), valid.replace('success', 'skipped'),
                      valid.replace('executionCount="1"', 'executionCount="0"')):
            second.write_text(wrong)
            with self.assertRaisesRegex(ValueError, 'Incomplete or unsuccessful'):
                integration.invoker_results(reports, ['first', 'second'])
        second.unlink()
        with self.assertRaisesRegex(ValueError, 'Incomplete or unsuccessful'):
            integration.invoker_results(reports, ['first', 'second'])

    def test_launched_maven_and_jdk_must_match_the_requested_matrix_pair(self):
        integration.require_native_runtime('Apache Maven 3.9.16\nJava version: 17.0.21, vendor: Eclipse', '3.9.16', '17')
        for text in ('Apache Maven 3.9.16\nJava version: 25.0.5',
                     'Apache Maven 4.0.0-rc-7\nJava version: 17.0.21', ''):
            with self.assertRaisesRegex(ValueError, 'selected matrix cell'):
                integration.require_native_runtime(text, '3.9.16', '17')

    def test_clean_settings_preserve_the_default_http_blocker_without_publishing_servers(self):
        settings = ET.parse(integration.ROOT / '.mvn/settings.xml').getroot()
        ns = {'s': 'http://maven.apache.org/SETTINGS/1.2.0'}
        self.assertIsNone(settings.find('s:servers', ns))
        self.assertIsNone(settings.find('s:proxies', ns))
        mirrors = settings.findall('s:mirrors/s:mirror', ns)
        self.assertEqual(1, len(mirrors))
        self.assertEqual('external:http:*', mirrors[0].findtext('s:mirrorOf', namespaces=ns))
        self.assertEqual('true', mirrors[0].findtext('s:blocked', namespaces=ns))

    def test_tamper_probe_requires_native_rejection_and_restores_files_even_on_timeout(self):
        for mode in ('checksum', 'success', 'unrelated-failure', 'timeout'):
            with self.subTest(mode=mode):
                work = self.root / mode
                output = work / 'reports'
                output.mkdir(parents=True)
                repository = work / 'repository'
                folder = repository / 'org/antlr/ST4/4.3.4'
                folder.mkdir(parents=True)
                originals = {kind: ('original ' + kind).encode() for kind in ('jar', 'pom')}
                for kind, content in originals.items():
                    (folder / ('ST4-4.3.4.' + kind)).write_bytes(content)
                calls = []
                def invoke(home, arguments, *args, **kwargs):
                    calls.append(arguments)
                    self.assertIn(integration.SERIAL_COLLECTOR, arguments)
                    self.assertTrue(kwargs['offline'])
                    self.assertEqual(120, kwargs['timeout'])
                    if mode == 'success':
                        return
                    if mode == 'timeout':
                        raise subprocess.TimeoutExpired('fixture', 120)
                    kind = ('jar', 'pom')[len(calls) - 1]
                    text = ('ArtifactChecksumError: Checksums did not match for org.antlr:ST4:' + kind + ':4.3.4'
                            if mode == 'checksum' else 'An unrelated compiler failure')
                    kwargs['stdout'].write(text)
                    kwargs['stdout'].flush()
                    raise subprocess.CalledProcessError(1, 'fixture')
                with patch.object(maven, 'run', side_effect=invoke):
                    if mode == 'checksum':
                        integration.reject_tampered_local_artifacts(self.root / 'tool', repository, self.root / 'policy',
                                                                   'fixture-jdk', work, output)
                        self.assertEqual(2, len(calls))
                    else:
                        with self.assertRaises(subprocess.TimeoutExpired if mode == 'timeout' else ValueError):
                            integration.reject_tampered_local_artifacts(self.root / 'tool', repository, self.root / 'policy',
                                                                       'fixture-jdk', work, output)
                for kind, content in originals.items():
                    self.assertEqual(content, (folder / ('ST4-4.3.4.' + kind)).read_bytes())


if __name__ == '__main__':
    unittest.main()
