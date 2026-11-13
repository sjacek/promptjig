"""Mutations of retained native evidence must never inherit an earlier passing summary."""
import copy
import io
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import security_reports as security
import security_inventory as inventory

SHA = 'a' * 40
NOW = datetime(2026, 11, 13, 12, tzinfo=timezone.utc)


class SecurityEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        context = patch.object(security, 'ROOT', self.root)
        context.start()
        self.addCleanup(context.stop)
        policy = {'schemaVersion': 1, 'scannerVersion': '0.121.3', 'severityCutoff': 'high',
            'databaseMaxAgeHours': 120, 'includeUnfixed': True,
            'blockingScopes': list(security.SCOPES), 'distributions': list(security.DISTRIBUTIONS)}
        security.write(self.root / 'security/scan-policy.json', policy)
        jar = self.root / 'core/build/libs/core-0.3.0.jar'
        jar.parent.mkdir(parents=True)
        with zipfile.ZipFile(jar, 'w') as output:
            output.writestr('Synthetic.class', b'synthetic-candidate')
        graph = {'project': ':core', 'configuration': 'runtimeClasspath', 'source': 'configuration',
            'root': 'project :core', 'components': [{'id': 'project :core',
                'identity': {'kind': 'project', 'path': ':core'}, 'dependencies': []}], 'artifacts': []}
        self.native = {'schemaVersion': 1, 'distribution': 'core', 'version': '0.3.0', 'gradleVersion': '9.9.1',
            'candidate': {'file': str(jar), 'sha256': security.digest(jar)}, 'consumersResolved': True,
            'consumerEvidence': [], 'sourceInputs': [{'file': k, 'sha256': v} for k, v in security.source_inputs().items()],
            'scopes': {scope: [copy.deepcopy(graph)] for scope in security.SCOPES}}
        self.native_file = self.root / 'build/security/native/core.json'
        security.write(self.native_file, self.native)
        self.output = self.root / 'build/security/reports/core/runtime'
        bom, provenance = security.bom_for(self.native, 'runtime', SHA)
        security.write(self.output / 'bom.cdx.json', bom)
        security.write(self.output / 'inventory.json', {'schemaVersion': 1, 'sourceCommit': SHA,
            'sourceDirty': False,
            'distribution': 'core', 'scope': 'runtime', 'candidateVersion': '0.3.0', 'candidate': self.native['candidate'],
            'nativeFile': self.native_file.relative_to(self.root).as_posix(), 'nativeSha256': security.digest(self.native_file),
            'sbomSha256': security.digest(self.output / 'bom.cdx.json'), 'provenance': provenance})
        self.database = {'built': NOW.isoformat(), 'valid': True, 'schemaVersion': 'v6.1.10', 'from': 'https://example.test/db?checksum=sha256:synthetic'}
        self.scanner = {'version': '0.121.3'}
        self.findings = {'matches': [], 'source': {'type': 'sbom-file', 'target': str(self.output / 'bom.cdx.json')},
            'descriptor': {'name': 'grype', 'version': '0.121.3', 'db': {'status': copy.deepcopy(self.database)},
                'configuration': {'only-fixed': False, 'only-notfixed': False, 'fail-on-severity': 'high',
                    'show-suppressed': True, 'exclude': [], 'vex-documents': [], 'ignore': [],
                    'externalSources': {'enable': False}}}}

    def retain(self, exit_code=0):
        security.write(self.output / 'findings.json', self.findings)
        security.write(self.output / 'scan-status.json', {'exitCode': exit_code,
            'sbomSha256': security.digest(self.output / 'bom.cdx.json'),
            'findingsSha256': security.digest(self.output / 'findings.json')})

    def summarize(self):
        return security.summarize(self.output, self.scanner, self.database, NOW)

    def test_complete_clean_cohort_can_pass_without_claiming_published_audit(self):
        self.retain()
        self.assertTrue(self.summarize()['passed'])
        self.assertEqual(SHA, self.summarize()['sourceCommit'])

    def test_duplicate_json_evidence_fields_are_not_silently_overwritten(self):
        file = self.root / 'duplicate.json'
        file.write_text('{"passed":false,"passed":true}')
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON'):
            security.read(file)

    def test_all_severities_are_retained_and_unfixed_high_blocks(self):
        self.findings['matches'] = [{'vulnerability': {'id': 'synthetic-' + severity, 'severity': severity,
             'fix': {'state': 'not-fixed', 'versions': []}}, 'artifact': {'purl': 'pkg:maven/test/fixture@1'}}
             for severity in ('Low', 'Medium', 'High', 'Critical', 'Unknown')]
        self.retain(2)
        result = self.summarize()
        self.assertFalse(result['passed'])
        self.assertEqual({'low': 1, 'medium': 1, 'high': 1, 'critical': 1, 'unknown': 1}, result['severities'])
        self.assertEqual(5, len(security.read(self.output / 'findings.json')['matches']))

    def test_tool_error_or_contradictory_native_exit_destroys_previous_green_summary(self):
        self.retain()
        self.summarize()
        self.retain(1)
        with self.assertRaisesRegex(ValueError, 'contradict'):
            self.summarize()
        self.assertFalse((self.output / 'summary.json').exists())

    def test_stale_future_or_inconsistent_database_is_not_green(self):
        for age in (121, -1):
            self.database['built'] = (NOW - timedelta(hours=age)).isoformat()
            self.findings['descriptor']['db']['status'] = copy.deepcopy(self.database)
            self.retain()
            with self.subTest(age=age), self.assertRaisesRegex(ValueError, 'Stale or future'):
                self.summarize()
        self.database['valid'] = False
        self.retain()
        with self.assertRaisesRegex(ValueError, 'DB identity'):
            self.summarize()

    def test_scanner_options_cannot_hide_unfixed_or_suppressed_findings(self):
        original = copy.deepcopy(self.findings)
        for key, value in [('only-fixed', True), ('exclude', ['pkg:maven/test/fixture@1']),
                ('vex-documents', ['synthetic.json']), ('ignore-wontfix', 'true'),
                ('ignore', [{'vulnerability': 'GHSA-synthetic'}]), ('show-suppressed', False)]:
            self.findings = copy.deepcopy(original)
            self.findings['descriptor']['configuration'][key] = value
            self.retain()
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.summarize()

    def test_missing_modified_or_other_input_evidence_fails_closed(self):
        self.retain()
        self.findings['source']['target'] = str(self.output / 'another-bom.json')
        self.retain()
        with self.assertRaisesRegex(ValueError, 'different input'):
            self.summarize()
        (self.output / 'findings.json').unlink()
        with self.assertRaises(FileNotFoundError):
            self.summarize()

    def test_changed_candidate_and_changed_sources_require_new_native_resolution(self):
        self.retain()
        Path(self.native['candidate']['file']).write_bytes(b'changed candidate')
        with self.assertRaisesRegex(ValueError, 'Candidate bytes'):
            self.summarize()
        security.write(self.root / 'security/new-policy.json', {})
        with self.assertRaisesRegex(ValueError, 'current build inputs'):
            security.validate_native(self.native)

    def test_duplicate_dangling_or_unreachable_sbom_components_are_rejected(self):
        bom, _ = security.bom_for(self.native, 'runtime', SHA)
        bom['components'].append(copy.deepcopy(bom['metadata']['component']))
        with self.assertRaises(ValueError):
            security.validate_bom(bom)
        bom['components'] = [security.component('example', 'orphan', '1')]
        with self.assertRaisesRegex(ValueError, 'unreachable'):
            security.validate_bom(bom)
        bom['dependencies'][0]['dependsOn'] = ['not-in-bom']
        with self.assertRaises(ValueError):
            security.validate_bom(bom)

    def test_weakened_policy_or_missing_native_consumers_are_rejected(self):
        policy = security.policy()
        policy['includeUnfixed'] = False
        security.write(self.root / 'security/scan-policy.json', policy)
        with self.assertRaises(ValueError):
            security.policy()
        self.native['consumersResolved'] = False
        with self.assertRaisesRegex(ValueError, 'Incomplete native'):
            security.validate_native(self.native)

    def test_maven_flat_realm_parser_requires_every_executed_tool_and_real_files(self):
        path = self.root / 'plugins.txt'
        path.write_text('The following plugins have been resolved:\n')
        with self.assertRaisesRegex(ValueError, 'Incomplete native Maven'):
            inventory.plugin_graphs(path, '3.9.16')

    def test_sdk_identity_requires_the_reviewed_source_coordinate_and_exact_hash(self):
        identity = {'file': 'synthetic-1.jar', 'group': 'example', 'name': 'synthetic', 'version': '1',
            'sha256': 'a' * 64, 'source': 'https://repo.maven.apache.org/maven2/example/synthetic/1/synthetic-1.jar'}
        security.write(self.root / 'security/gradle-sdk-artifacts.json', {'schemaVersion': 1, 'artifacts': [identity]})
        artifact = {'name': identity['file'], 'sha256': identity['sha256']}
        self.assertEqual('pkg:maven/example/synthetic@1', security.sdk_component(artifact, '9.9.1')['purl'])
        artifact['sha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'SDK identity/hash'):
            security.sdk_component(artifact, '9.9.1')

    def test_extra_or_substituted_nested_runtime_bytes_reject_the_inventory(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, 'w') as bundle:
            bundle.writestr('unexpected.jar', b'synthetic extra bytes')
        outer = self.root / 'plugin.jar'
        with zipfile.ZipFile(outer, 'w') as jar:
            jar.writestr('META-INF/promptjig/runtime.zip', nested.getvalue())
        native = {'distribution': 'gradle-plugin', 'candidate': {'file': str(outer)}, 'scopes': {'runtime': [
            {'configuration': 'isolatedRuntime', 'artifacts': [{'name': 'expected.jar', 'sha256': 'a' * 64}]}]}}
        with self.assertRaisesRegex(ValueError, 'Embedded runtime'):
            security.verify_bundle(native)


if __name__ == '__main__':
    unittest.main()
