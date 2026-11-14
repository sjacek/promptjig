"""Portable release sets reject foreign/stale data; Portal carries identical JSON without a self-hash cycle."""
import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import publication_sboms as publication
import security_reports as reports
from test_central_publication import sbom_payload, SHA


class PublicationSbomsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.directory = self.root / 'build/publication-sboms'
        (self.root / 'gradle').mkdir()
        (self.root / 'gradle/libs.versions.toml').write_text('[versions]\npromptjig="0.3.0"\n')
        (self.root / 'build.gradle').write_text('// synthetic-publication-testdata\n')
        self.inputs = [{'file': name, 'sha256': checksum} for name, checksum in sorted(reports.source_inputs(self.root).items())]
        self.manifest = {'schemaVersion': 1, 'sourceCommit': SHA, 'sourceDirty': True,
                         'candidateVersion': '0.3.0', 'sourceInputs': self.inputs, 'modules': {}}
        for module in reports.DISTRIBUTIONS:
            jar = self.root / module / 'build/libs' / (module + '-0.3.0.jar')
            jar.parent.mkdir(parents=True)
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('META-INF/LICENSE', 'MIT fixture')
                archive.writestr('Fixture.class', 'synthetic')
            record = {'artifactId': reports.NAMES[module], 'jarSha256': reports.digest(jar), 'scopes': {}}
            for scope in reports.SCOPES:
                file = self.directory / module / (scope + '.cdx.json')
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(sbom_payload(module, scope, '0.3.0', jar.read_bytes(), inputs=self.inputs))
                record['scopes'][scope] = {'file': f'{module}/{scope}.cdx.json', 'sha256': reports.digest(file)}
            self.manifest['modules'][module] = record
        self.save()
        self.addCleanup(patch.stopall)
        patch.object(publication.subprocess, 'check_output', return_value=SHA + '\n').start()

    def save(self):
        for module in self.manifest['modules']:
            reports.write(self.directory / module / 'manifest.json', publication.module_manifest(self.manifest, module))
        reports.write(self.directory / 'manifest.json', self.manifest)

    def verify(self):
        return publication.verify(self.directory, root=self.root)

    def test_complete_set_verifies_after_relocation_without_native_cache_or_credentials(self):
        self.assertEqual(self.manifest, self.verify())
        relocated = self.root / 'build/downloaded-sboms'
        shutil.copytree(self.directory, relocated)
        self.assertEqual(self.manifest, publication.verify(relocated, root=self.root, commit=SHA))

    def test_missing_scope_foreign_commit_version_unsafe_path_and_changed_sources_fail(self):
        original = copy.deepcopy(self.manifest)
        for mutation in ('scope', 'commit', 'version', 'path', 'module', 'cohort'):
            self.manifest = copy.deepcopy(original)
            if mutation == 'scope': del self.manifest['modules']['core']['scopes']['test']
            elif mutation == 'commit': self.manifest['sourceCommit'] = 'b' * 40
            elif mutation == 'version': self.manifest['candidateVersion'] = '9.0.0'
            elif mutation == 'path': self.manifest['modules']['core']['scopes']['test']['file'] = '../outside.json'
            elif mutation == 'module': del self.manifest['modules']['core']
            else: self.manifest['sourceInputs'] = []
            self.save()
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): self.verify()
        self.manifest = original
        self.save()
        (self.root / 'build.gradle').write_text('// changed after inventory\n')
        with self.assertRaisesRegex(ValueError, 'source inputs'): self.verify()

    def test_changed_jar_or_json_and_contradictory_module_manifest_fail(self):
        file = self.directory / 'core/runtime.cdx.json'
        original = file.read_bytes()
        file.write_bytes(original + b' ')
        with self.assertRaisesRegex(ValueError, 'attachment changed'): self.verify()
        file.write_bytes(original)
        reports.write(self.directory / 'core/manifest.json', {})
        with self.assertRaisesRegex(ValueError, 'contradictory'): self.verify()
        self.save()
        (self.root / 'core/build/libs/core-0.3.0.jar').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'JAR changed'): self.verify()

    def test_unexpected_module_payload_is_not_packaged_as_public_metadata(self):
        (self.directory / 'gradle-plugin/accidental.cdx.json').write_text('synthetic-secret-testdata')
        with self.assertRaisesRegex(ValueError, 'Unexpected file'): self.verify()

    def test_rehashed_graph_or_scope_and_duplicate_json_are_still_rejected(self):
        file = self.directory / 'core/runtime.cdx.json'
        original = file.read_bytes()
        for mutation in ('scope', 'graph', 'serial', 'duplicate'):
            bom = json.loads(original)
            if mutation == 'scope': bom['metadata']['properties'][0]['value'] = 'test'
            elif mutation == 'graph': bom['dependencies'][0]['dependsOn'] = ['unknown']
            elif mutation == 'serial': bom['serialNumber'] = 'urn:uuid:wrong'
            data = json.dumps(bom).encode()
            if mutation == 'duplicate': data = data.replace(b'{', b'{"version":999,', 1)
            file.write_bytes(data)
            self.manifest['modules']['core']['scopes']['runtime']['sha256'] = reports.digest(file)
            self.save()
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): self.verify()

    def test_symlink_and_missing_attachment_fail(self):
        file = self.directory / 'core/test.cdx.json'
        file.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing SBOM'): self.verify()
        try:
            file.symlink_to(self.directory / 'core/runtime.cdx.json')
        except (OSError, NotImplementedError):
            self.skipTest('OS denies symlink creation; missing-file rejection was exercised')
        with self.assertRaisesRegex(ValueError, 'symlink'): self.verify()

    def test_failed_scan_cannot_leave_an_export_manifest_usable(self):
        (self.root / 'build/security/reports').mkdir(parents=True)
        reports.write(self.root / 'build/security/reports/summary.json', {'passed': False})
        with patch.object(publication, 'ROOT', self.root), patch.object(publication, 'DIRECTORY', self.directory):
            with self.assertRaisesRegex(ValueError, 'successful candidate'): publication.export(SHA)
        self.assertFalse((self.directory / 'manifest.json').exists())

    def test_portal_classifier_jar_contains_exact_json_and_no_executable_or_secret_payload(self):
        folder = self.root / 'portal' / Path(*reports.GROUP.split('.')) / reports.NAMES['gradle-plugin'] / '0.3.0'
        folder.mkdir(parents=True)
        shutil.copyfile(self.root / 'gradle-plugin/build/libs/gradle-plugin-0.3.0.jar', folder / 'promptjig-gradle-plugin-0.3.0.jar')
        marker_name = reports.GROUP + '.gradle.plugin'
        marker = self.root / 'portal' / Path(*reports.GROUP.split('.')) / marker_name / '0.3.0'
        marker.mkdir(parents=True)
        (marker / f'{marker_name}-0.3.0.pom').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<packaging>pom</packaging><dependencies><dependency>'
            f'<groupId>{reports.GROUP}</groupId><artifactId>promptjig-gradle-plugin</artifactId>'
            '<version>0.3.0</version></dependency></dependencies></project>')
        archive = folder / 'promptjig-gradle-plugin-0.3.0-sbom.jar'
        def write(extra=False):
            with zipfile.ZipFile(archive, 'w') as jar:
                jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
                jar.writestr('META-INF/LICENSE', 'MIT fixture')
                jar.writestr('META-INF/sbom/manifest.json', json.dumps(publication.module_manifest(self.manifest, 'gradle-plugin')))
                for scope in reports.SCOPES:
                    jar.writestr(f'META-INF/sbom/{scope}.cdx.json', (self.directory / 'gradle-plugin' / f'{scope}.cdx.json').read_bytes())
                if extra: jar.writestr('accidental-token.txt', 'synthetic-secret-testdata')
        write()
        with patch.object(publication, 'verify', return_value=self.manifest):
            publication.verify_portal(self.root / 'portal', '0.3.0', self.directory)
            write(extra=True)
            with self.assertRaisesRegex(ValueError, 'Unexpected Portal'): publication.verify_portal(self.root / 'portal', '0.3.0', self.directory)
            write()
            (marker / 'unexpected-sbom.jar').write_bytes(b'not a marker attachment')
            with self.assertRaisesRegex(ValueError, 'POM-only'): publication.verify_portal(self.root / 'portal', '0.3.0', self.directory)


if __name__ == '__main__':
    unittest.main()
