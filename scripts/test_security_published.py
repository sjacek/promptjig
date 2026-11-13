"""Fresh-registry audits reject changed payloads and cannot reuse a previous clean scan."""
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import published_artifact_audit as audit
import published_dependency_audit as dependencies


class PublishedEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def jar(self, name, entries):
        file = self.root / name
        with zipfile.ZipFile(file, 'w') as output:
            output.writestr('META-INF/LICENSE', 'synthetic MIT fixture')
            for path, payload in entries.items():
                output.writestr(path, payload)
        return file

    def test_sources_must_match_the_selected_tag(self):
        file = self.jar('sources.jar', {'example/Fixture.java': b'public class Fixture {}\r\n'})
        with patch.object(audit.subprocess, 'check_output', return_value=b'public class Fixture {}\n') as git:
            self.assertEqual(['example/Fixture.java'], audit.inspect_jar(file, 'core', 'v0.2.0')['sourcesCompared'])
            self.assertIn('v0.2.0:core/src/main/java/example/Fixture.java', git.call_args.args[0])
        with patch.object(audit.subprocess, 'check_output', return_value=b'different source'):
            with self.assertRaisesRegex(ValueError, 'differs from tag'):
                audit.inspect_jar(file, 'core', 'v0.2.0')

    def test_high_base_bytecode_is_rejected_without_confusing_multi_release_entries(self):
        file = self.jar('classes.jar', {'example/Fixture.class': b'\xca\xfe\xba\xbe\0\0\0=',
            'META-INF/versions/21/example/Fixture.class': b'\xca\xfe\xba\xbe\0\0\0A'})
        self.assertEqual([61], audit.inspect_jar(file, 'core', 'v0.2.0')['baseClassMajors'])
        file = self.jar('too-new.jar', {'example/Fixture.class': b'\xca\xfe\xba\xbe\0\0\0>'})
        with self.assertRaisesRegex(ValueError, 'Java 17'):
            audit.inspect_jar(file, 'core', 'v0.2.0')

    def test_a_source_archive_cannot_silently_omit_a_tagged_source_file(self):
        file = self.jar('fixture-sources.jar', {})
        with patch.object(audit.subprocess, 'check_output', return_value='core/src/main/java/example/Fixture.java\n'):
            with self.assertRaisesRegex(ValueError, 'cover the tag module'):
                audit.inspect_jar(file, 'core', 'v0.2.0')

    def test_embedded_bytes_require_a_unique_reviewed_external_identity(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, 'w') as output:
            output.writestr('unknown-1.jar', b'controlled synthetic bytes')
        file = self.jar('plugin.jar', {'META-INF/promptjig/runtime.zip': nested.getvalue()})
        with patch.object(dependencies.cache, 'checksums', return_value={}):
            with self.assertRaisesRegex(ValueError, 'unique reviewed identity'):
                dependencies.embedded_components(file, '0.1.0')

    def test_checksum_sidecar_mismatch_is_not_an_audited_download(self):
        file = self.root / 'synthetic.jar'
        file.write_bytes(b'synthetic')
        def fetched(url, target, optional=False):
            target.write_text('0' * 64)
            return {'source': url}
        with patch.object(audit, 'fetch', side_effect=fetched):
            with self.assertRaisesRegex(ValueError, 'sidecar mismatch'):
                audit.check_sidecar('https://example.test/synthetic.jar', file)

    def test_failed_scanner_identity_cannot_leave_an_old_green_summary(self):
        file = self.root / 'scan-summary.json'
        file.write_text('{"passed":true}')
        with patch.object(dependencies, 'OUTPUT', self.root), \
                patch.object(dependencies.subprocess, 'check_output', side_effect=OSError('synthetic tool failure')):
            with self.assertRaises(OSError):
                dependencies.scan('unavailable-scanner', self.root / 'db')
        self.assertFalse(file.exists())

    def test_missing_coordinates_and_unrecognized_severity_cannot_look_clean(self):
        for matches in (None, [{'vulnerability': {'id': 'synthetic', 'severity': 'invalid'}, 'artifact': {'purl': 'pkg:generic/test@1'}}],
                [{'vulnerability': {'id': 'synthetic', 'severity': 'High'}, 'artifact': {}}]):
            with self.subTest(matches=matches), self.assertRaises(ValueError):
                dependencies.severity_counts({'matches': matches})

    def test_changed_fresh_registry_jar_invalidates_published_evidence(self):
        directory = self.root / 'core'
        directory.mkdir()
        (directory / 'promptjig-core-0.2.0.jar').write_bytes(b'changed')
        with patch.object(dependencies, 'OUTPUT', self.root):
            with self.assertRaisesRegex(ValueError, 'Published JAR changed'):
                dependencies.validate_published_bytes({'distribution': 'core', 'version': '0.2.0', 'jarSha256': 'a' * 64})


if __name__ == '__main__':
    unittest.main()
