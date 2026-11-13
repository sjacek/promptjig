import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

import ide_dependency_verification as ide


class IdeDependencyVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'gradle').mkdir()
        self.path = self.root / 'gradle/verification-metadata.xml'
        self.binary_hash = hashlib.sha256(b'binary').hexdigest()
        self.source_hash = hashlib.sha256(b'sources').hexdigest()
        self.sdk = {'schemaVersion': 1, 'gradleVersion': '9.9.1', 'modules': [
            {'group': 'org.apache.groovy', 'name': 'groovy', 'version': '4.0.33'}]}
        self.path.write_text('''<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures></configuration>
   <components>
      <component group="example" name="library" version="1.0">
         <artifact name="library-1.0.jar"><sha256 value="''' + self.binary_hash + '''"/></artifact>
      </component>
      <component group="org.apache.groovy" name="groovy" version="4.0.33">
         <artifact name="groovy-4.0.33-sources.jar"><sha256 value="''' + self.source_hash + '''"/></artifact>
         <artifact name="groovy-4.0.33.module"><sha256 value="''' + self.source_hash + '''"/></artifact>
      </component>
   </components>
</verification-metadata>
''', encoding='utf-8')
        self.identity = ('example', 'library', '1.0', 'library-1.0-sources.jar')

    def coverage(self, unpublished=None):
        _, artifacts, binaries = ide.read_baseline(self.root)
        ide.validate_coverage(artifacts, binaries, ide.required_artifacts(binaries, self.sdk), unpublished or {})

    def test_new_dependency_version_fails_before_ide_import(self):
        self.path.write_bytes(ide.add_checksums(self.path.read_bytes(), {
            self.identity: {'sha256': self.source_hash}}))
        self.coverage()
        self.path.write_text(self.path.read_text().replace('name="library" version="1.0"', 'name="library" version="2.0"')
                             .replace('library-1.0.jar', 'library-2.0.jar'))
        with self.assertRaisesRegex(ValueError, 'library-2.0-sources.jar'):
            self.coverage()

    def test_wrapper_groovy_update_requires_new_sources_and_module(self):
        self.sdk['modules'][0]['version'] = '4.0.34'
        with self.assertRaisesRegex(ValueError, 'groovy-4.0.34.module'):
            self.coverage()

    def test_classifier_only_dependencies_also_require_sources(self):
        self.path.write_text(self.path.read_text().replace('library-1.0.jar', 'library-1.0-tests.jar'))
        with self.assertRaisesRegex(ValueError, 'library-1.0-sources.jar'):
            self.coverage()

    def test_unpublished_exception_is_bound_to_exact_binary_bytes(self):
        exception = {':'.join(self.identity): {'binarySha256': [self.binary_hash]}}
        self.coverage(exception)
        self.path.write_text(self.path.read_text().replace(self.binary_hash, 'a' * 64))
        with self.assertRaisesRegex(ValueError, 'Stale or overbroad'):
            self.coverage(exception)

    def test_unknown_unpublished_coordinate_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'unknown artifacts'):
            self.coverage({'other:library:1.0:library-1.0-sources.jar': {'binarySha256': [self.binary_hash]}})

    def test_check_is_read_only_and_rejects_unreviewed_sources(self):
        before = self.path.read_bytes()
        with self.assertRaisesRegex(ValueError, 'Missing IDE verification metadata'):
            ide.check(self.root, self.sdk)
        self.assertEqual(before, self.path.read_bytes())
        self.assertFalse((self.root / ide.AVAILABILITY).exists())

    def test_no_wildcard_trust_can_disable_integrity_gate(self):
        self.path.write_text(self.path.read_text().replace('<verify-signatures>',
            '<trusted-artifacts><trust file=".*-sources.jar" regex="true"/></trusted-artifacts><verify-signatures>'))
        with self.assertRaisesRegex(ValueError, 'trust exceptions'):
            ide.read_baseline(self.root)

    def test_update_never_runs_in_ci(self):
        before = self.path.read_bytes()
        for indicator in ('CI', 'GITHUB_ACTIONS', 'GITLAB_CI', 'TF_BUILD', 'JENKINS_URL', 'BUILD_BUILDID'):
            with self.subTest(indicator=indicator), patch.dict(os.environ, {indicator: 'true'}):
                with self.assertRaisesRegex(ValueError, 'local maintenance only'):
                    ide.update(self.root, self.sdk)
        self.assertEqual(before, self.path.read_bytes())

    def test_reviewed_update_preserves_all_existing_checksums(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(ide, 'reviewed_download', return_value=(
                self.identity, {'sha256': self.source_hash, 'url': 'https://repo.maven.apache.org/maven2/example'})):
            with contextlib.redirect_stdout(io.StringIO()):
                ide.update(self.root, self.sdk)
        self.coverage()
        _, artifacts, _ = ide.read_baseline(self.root)
        self.assertEqual({self.binary_hash}, artifacts[('example', 'library', '1.0', 'library-1.0.jar')])
        self.assertEqual({self.source_hash}, artifacts[self.identity])

    def test_corrupted_download_is_rejected_against_reviewed_sha256(self):
        with patch.object(ide, 'download', return_value=(b'tampered sources', 'official')):
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                ide.verify_download(self.identity, {self.identity: {self.source_hash}}, {})

    def test_online_gate_rejects_tampering_without_changing_policy(self):
        self.path.write_bytes(ide.add_checksums(self.path.read_bytes(), {
            self.identity: {'sha256': self.source_hash}}))
        before = self.path.read_bytes()
        with patch.object(ide, 'download', side_effect=lambda identity:
                (b'tampered' if identity == self.identity else b'sources', 'official')):
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                ide.check(self.root, self.sdk, verify_downloads=True)
        self.assertEqual(before, self.path.read_bytes())

    def test_newly_published_sources_cannot_keep_absence_exception(self):
        with patch.object(ide, 'download', return_value=(b'sources', 'official')):
            with self.assertRaisesRegex(ValueError, 'now exist'):
                ide.verify_download(self.identity, {}, {':'.join(self.identity): {}})

    def test_server_errors_are_not_recorded_as_unpublished(self):
        for status in (403, 429, 500):
            with self.subTest(status=status), patch.object(ide.urllib.request, 'urlopen', side_effect=
                    urllib.error.HTTPError('official', status, 'error', {}, None)):
                with self.assertRaisesRegex(ValueError, 'request failed'):
                    ide.download(self.identity)

    def test_absence_requires_404_from_both_official_repositories(self):
        with patch.object(ide.urllib.request, 'urlopen', side_effect=
                urllib.error.HTTPError('official', 404, 'missing', {}, None)) as request:
            self.assertEqual((None, None), ide.download(self.identity))
        self.assertEqual(2, request.call_count)

    def test_update_rejects_a_sidecar_mismatch(self):
        with (patch.object(ide, 'download', return_value=(b'sources', 'official')),
                patch.object(ide.urllib.request, 'urlopen', return_value=io.BytesIO(b'0' * 128))):
            with self.assertRaisesRegex(ValueError, 'sidecar mismatch'):
                ide.reviewed_download(self.identity)

    def test_concurrent_change_is_not_overwritten(self):
        before = self.path.read_bytes()
        self.path.write_bytes(before + b'<!-- concurrent edit -->')
        with self.assertRaisesRegex(ValueError, 'changed concurrently'):
            ide.atomic_write(self.path, before, before)
        self.assertTrue(self.path.read_bytes().endswith(b'<!-- concurrent edit -->'))

    def test_ci_always_checks_downloads_even_for_exact_cache_hits(self):
        workflow = (ide.ROOT / '.github/workflows/compatibility.yml').read_text()
        expected = ('      - name: Verify IDE source and Gradle SDK checksums\n'
                    '        run: python3 -B scripts/ide_dependency_verification.py check --verify-downloads')
        self.assertIn(expected, workflow)
        self.assertNotIn('ide_dependency_verification.py update', workflow)


if __name__ == '__main__':
    unittest.main()
