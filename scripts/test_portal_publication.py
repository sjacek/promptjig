"""Exercise the actual Portal task graph with audited SBOMs, without credentials or upload.

Run explicitly after publication_sboms.py export. The graph-only regression also
runs before inventory generation. An init script substitutes only the publisher's
network action; production SBOM verification and archive creation remain intact.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import central_publication as central
import publication_sboms as publication


class PortalPublicationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='promptjig-portal-test-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = central.ROOT
        cls.cache = Path(cls.temporary.name) / 'project-cache'
        cls.archive = cls.root / 'gradle-plugin/build/libs' / f'gradle-plugin-{central.version()}-sbom.jar'
        cls.marker = 'PORTAL_PUBLICATION_PROBE_OK'

    def run_publisher(self, validate_only=False, dry_run=False):
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(('GRADLE_PUBLISH_', 'MAVEN_', 'ORG_GRADLE_PROJECT_signing'))}
        environment['PROMPTJIG_PYTHON'] = sys.executable
        command = [str(self.root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')),
                   ':gradle-plugin:publishPlugins', *(['--validate-only'] if validate_only else []),
                   '-PpublicationSboms=true', '--offline', '--dependency-verification=strict',
                   '--no-build-cache', '--console=plain', '--project-cache-dir', str(self.cache),
                   '--init-script', str(self.root / 'testing/publication/portal-probe.gradle')]
        command += ['--dry-run', '--no-configuration-cache'] if dry_run else [
            '--configuration-cache', '--configuration-cache-problems=fail']
        return subprocess.run(command, cwd=self.root, env=environment, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', timeout=360)

    def test_task_graph_schedules_verification_and_archive_before_both_publisher_modes(self):
        # No audited files or existing classifier are needed to detect the 0.3.0 dependency omission.
        for validate_only in (False, True):
            with self.subTest(validate_only=validate_only):
                result = self.run_publisher(validate_only=validate_only, dry_run=True)
                self.assertEqual(0, result.returncode, result.stdout[-6000:])
                tasks = [line.split()[0] for line in result.stdout.splitlines() if line.startswith(':')]
                for required in (':gradle-plugin:verifyPublicationSboms', ':gradle-plugin:publicationSbomJar'):
                    self.assertIn(required, tasks, result.stdout[-6000:])
                self.assertLess(tasks.index(':gradle-plugin:verifyPublicationSboms'),
                                tasks.index(':gradle-plugin:publicationSbomJar'))
                self.assertLess(tasks.index(':gradle-plugin:publicationSbomJar'),
                                tasks.index(':gradle-plugin:publishPlugins'))

    def test_missing_archive_is_rebuilt_for_validation_and_upload_with_configuration_cache_reuse(self):
        publication.verify(module='gradle-plugin')
        for validate_only in (False, True):
            for reuse in (False, True):
                with self.subTest(validate_only=validate_only, reuse=reuse):
                    # Unlike the old collector check, never prepare the JAR through another task first.
                    self.archive.unlink(missing_ok=True)
                    result = self.run_publisher(validate_only=validate_only)
                    self.assertEqual(0, result.returncode, result.stdout[-6000:])
                    self.assertIn(self.marker + ' validateOnly=' + str(validate_only).lower(), result.stdout)
                    self.assertTrue(self.archive.is_file())
                    if reuse:
                        self.assertIn('Reusing configuration cache.', result.stdout)

    def test_invalid_or_missing_audited_input_blocks_a_cached_publisher_before_request_collection(self):
        publication.verify(module='gradle-plugin')
        # First create a valid archive and cached task graph; stale output must never bypass revalidation.
        prepared = self.run_publisher()
        self.assertEqual(0, prepared.returncode, prepared.stdout[-6000:])
        for name, expected in (('runtime.cdx.json', 'Publication SBOM attachment changed'),
                               ('manifest.json', 'Missing SBOM file')):
            file = publication.DIRECTORY / 'gradle-plugin' / name
            original = file.read_bytes()
            try:
                if name == 'manifest.json':
                    file.unlink()
                else:
                    file.write_bytes(original + b' ')
                result = self.run_publisher()
                self.assertNotEqual(0, result.returncode, 'Invalid audited input unexpectedly reached the publisher')
                self.assertIn(expected, result.stdout)
                self.assertNotIn(self.marker, result.stdout)
            finally:
                file.write_bytes(original)
        publication.verify(module='gradle-plugin')


if __name__ == '__main__':
    unittest.main()
