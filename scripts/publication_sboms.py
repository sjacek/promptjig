"""Export audited SBOMs and bind release attachments to the current sources and actual JARs.

No dependency resolution, scanner, credentials or network access is needed to
verify the portable set downloaded from the exact successful Compatibility run.
The Plugin Portal receives a separate classifier JAR, avoiding a self-hash cycle.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import uuid
import xml.etree.ElementTree as ET
import zipfile

import security_reports as reports

ROOT = reports.ROOT
DIRECTORY = ROOT / 'build/publication-sboms'
CLASSIFIERS = {'runtime': 'cyclonedx', 'build': 'cyclonedx-build',
               'test': 'cyclonedx-test', 'provided': 'cyclonedx-provided'}


def regular(path):
    path = Path(path)
    if not path.is_file() or any(parent.is_symlink() for parent in (path, *path.parents)):
        raise ValueError('Missing SBOM file or symlink in its path: ' + path.name)
    return path


def properties(bom):
    values = bom['metadata'].get('properties', [])
    result = {item['name']: item['value'] for item in values}
    if len(result) != len(values):
        raise ValueError('Duplicate SBOM metadata property')
    return result


def validate_bom(payload, module, scope, version, jar_sha256, commit=None):
    """Also used on final Central payload bytes, after attachment/signing/staging."""
    bom = json.loads(payload, object_pairs_hook=unique)
    reports.validate_bom(bom)
    component = bom['metadata']['component']
    if (component.get('group') != reports.GROUP or component.get('name') != reports.NAMES[module]
            or component.get('version') != version
            or component.get('purl') != f'pkg:maven/{reports.GROUP}/{reports.NAMES[module]}@{version}'
            or component.get('hashes') != [{'alg': 'SHA-256', 'content': jar_sha256}]):
        raise ValueError('Publication SBOM does not describe the actual JAR coordinates/bytes')
    metadata = properties(bom)
    if (metadata.get('promptjig:scope') != scope
            or not re.fullmatch(r'[a-f0-9]{40}', metadata.get('promptjig:source-commit', ''))
            or (commit is not None and metadata['promptjig:source-commit'] != commit)):
        raise ValueError('Publication SBOM scope or source commit differs')
    return bom


def unique(pairs):
    values = {}
    for key, value in pairs:
        if key in values:
            raise ValueError('Duplicate publication SBOM field')
        values[key] = value
    return values


def module_manifest(manifest, module):
    return {key: manifest[key] for key in ('schemaVersion', 'sourceCommit', 'sourceDirty', 'candidateVersion')} | {
        'module': module, **manifest['modules'][module]}


def export(commit):
    """Run only after the complete scan. Never copy an old green aggregate unchecked."""
    if any(path.is_symlink() for path in (DIRECTORY, *DIRECTORY.parents)):
        raise ValueError('Publication SBOM output must not contain symlinks')
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    (DIRECTORY / 'manifest.json').unlink(missing_ok=True)
    summary = reports.read(ROOT / 'build/security/reports/summary.json')
    required = {(module, scope) for module in reports.DISTRIBUTIONS for scope in reports.SCOPES}
    if (summary.get('passed') is not True or summary.get('sourceCommit') != commit
            or len(summary.get('scopes', [])) != len(required)
            or {(entry['distribution'], entry['scope']) for entry in summary['scopes']} != required
            or reports.read(ROOT / 'build/security/reports/positive-control.status.json').get('passed') is not True):
        raise ValueError('A complete successful candidate security cohort is required')
    manifest = {key: summary[key] for key in ('sourceCommit', 'sourceDirty', 'candidateVersion')}
    manifest.update(schemaVersion=1, modules={})
    pending = []
    for module in reports.DISTRIBUTIONS:
        native = reports.read(ROOT / 'build/security/native' / (module + '.json'))
        reports.validate_native(native)
        reports.verify_bundle(native)
        if 'sourceInputs' in manifest and manifest['sourceInputs'] != native['sourceInputs']:
            raise ValueError('Publication SBOMs belong to different source cohorts')
        manifest['sourceInputs'] = native['sourceInputs']
        record = {'artifactId': reports.NAMES[module], 'jarSha256': native['candidate']['sha256'], 'scopes': {}}
        for scope in reports.SCOPES:
            folder = ROOT / 'build/security/reports' / module / scope
            result = reports.summarize(folder, summary['scanner'], summary['database'])
            aggregate = next(entry for entry in summary['scopes']
                             if (entry['distribution'], entry['scope']) == (module, scope))
            if result != aggregate or result['passed'] is not True:
                raise ValueError('Changed or failed scoped security evidence')
            file = f'{module}/{scope}.cdx.json'
            source = regular(folder / 'bom.cdx.json')
            record['scopes'][scope] = {'file': file, 'sha256': reports.digest(source)}
            pending.append((source, DIRECTORY / file))
        manifest['modules'][module] = record
    # Validate every scope before making a new complete set discoverable.
    for source, target in pending:
        if any(path.is_symlink() for path in (target, *target.parents)):
            raise ValueError('Publication SBOM output must not contain symlinks')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    for module in reports.DISTRIBUTIONS:
        reports.write(DIRECTORY / module / 'manifest.json', module_manifest(manifest, module))
    reports.write(DIRECTORY / 'manifest.json', manifest)
    verify(DIRECTORY, commit=commit)
    print('Exported 16 audited publication SBOMs; no publishing credentials or upload.')


def verify(directory=DIRECTORY, module=None, commit=None, root=ROOT):
    directory, root = Path(directory), Path(root)
    manifest = reports.read(regular(directory / 'manifest.json'))
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    if (manifest.get('schemaVersion') != 1 or type(manifest.get('sourceDirty')) is not bool
            or set(manifest) != {'schemaVersion', 'sourceCommit', 'sourceDirty', 'candidateVersion', 'sourceInputs', 'modules'}
            or manifest.get('sourceCommit') != head or (commit is not None and head != commit)
            or set(manifest.get('modules', {})) != set(reports.DISTRIBUTIONS)):
        raise ValueError('Publication SBOM manifest has a foreign/incomplete source cohort')
    inputs = manifest['sourceInputs']
    if (len({item['file'] for item in inputs}) != len(inputs)
            or {item['file']: item['sha256'] for item in inputs} != reports.source_inputs(root)):
        raise ValueError('Publication SBOM source inputs changed since inventory')
    version = manifest['candidateVersion']
    import tomllib
    if version != tomllib.loads((root / 'gradle/libs.versions.toml').read_text())['versions']['promptjig']:
        raise ValueError('Publication SBOM candidate version differs')
    for name in ([module] if module else reports.DISTRIBUTIONS):
        record = manifest['modules'][name]
        if (set(record) != {'artifactId', 'jarSha256', 'scopes'}
                or record['artifactId'] != reports.NAMES[name] or set(record['scopes']) != set(reports.SCOPES)
                or reports.read(regular(directory / name / 'manifest.json')) != module_manifest(manifest, name)):
            raise ValueError('Incomplete or contradictory publication SBOM module')
        if {file.name for file in (directory / name).iterdir()} - {'manifest.json', *(scope + '.cdx.json' for scope in reports.SCOPES)}:
            raise ValueError('Unexpected file in publication SBOM module')
        jar = regular(root / name / 'build/libs' / f'{name}-{version}.jar')
        if reports.digest(jar) != record['jarSha256']:
            raise ValueError('Publication JAR changed since the audited SBOM')
        for scope, entry in record['scopes'].items():
            if entry['file'] != f'{name}/{scope}.cdx.json':
                raise ValueError('Unsafe publication SBOM path')
            file = regular(directory / entry['file'])
            if reports.digest(file) != entry['sha256']:
                raise ValueError('Publication SBOM attachment changed')
            bom = validate_bom(file.read_bytes(), name, scope, version, record['jarSha256'], head)
            expected_serial = 'urn:uuid:' + str(uuid.uuid5(uuid.NAMESPACE_URL,
                head + name + scope + json.dumps(inputs, sort_keys=True)))
            if bom.get('serialNumber') != expected_serial:
                raise ValueError('Publication SBOM serial does not match its source inputs')
    return manifest


def verify_portal(repository, version, directory=DIRECTORY):
    """Check the actual staged classifier JAR and the publisher's supported artifact model."""
    manifest = verify(directory, module='gradle-plugin')
    record = manifest['modules']['gradle-plugin']
    folder = Path(repository) / Path(*reports.GROUP.split('.')) / reports.NAMES['gradle-plugin'] / version
    binary = regular(folder / f'promptjig-gradle-plugin-{version}.jar')
    if reports.digest(binary) != record['jarSha256']:
        raise ValueError('Staged Portal implementation JAR changed')
    marker_name = reports.GROUP + '.gradle.plugin'
    marker_folder = Path(repository) / Path(*reports.GROUP.split('.')) / marker_name / version
    marker = ET.fromstring(regular(marker_folder / f'{marker_name}-{version}.pom').read_bytes())
    ns = {'p': 'http://maven.apache.org/POM/4.0.0'}
    dependency = marker.find('p:dependencies/p:dependency', ns)
    if (marker.findtext('p:packaging', namespaces=ns) != 'pom' or dependency is None
            or any(dependency.findtext('p:' + key, namespaces=ns) != value for key, value in
                   [('groupId', reports.GROUP), ('artifactId', reports.NAMES['gradle-plugin']), ('version', version)])
            or any(file.suffix in {'.json', '.jar'} for file in marker_folder.iterdir())):
        raise ValueError('Portal marker must remain POM-only and point to the implementation')
    with zipfile.ZipFile(regular(folder / f'promptjig-gradle-plugin-{version}-sbom.jar')) as archive:
        required = {'META-INF/MANIFEST.MF', 'META-INF/LICENSE', 'META-INF/sbom/manifest.json'}
        required |= {f'META-INF/sbom/{scope}.cdx.json' for scope in reports.SCOPES}
        if (archive.testzip() or len(archive.namelist()) != len(set(archive.namelist()))
                or {name for name in archive.namelist() if not name.endswith('/')} != required):
            raise ValueError('Unexpected Portal SBOM JAR content')
        if json.loads(archive.read('META-INF/sbom/manifest.json'), object_pairs_hook=unique) != module_manifest(manifest, 'gradle-plugin'):
            raise ValueError('Portal SBOM JAR manifest differs')
        for scope in reports.SCOPES:
            if archive.read(f'META-INF/sbom/{scope}.cdx.json') != (Path(directory) / 'gradle-plugin' / f'{scope}.cdx.json').read_bytes():
                raise ValueError('Portal SBOM JAR does not carry the audited JSON bytes')
    print('Verified Portal SBOM classifier JAR: four exact audited documents; marker unchanged.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('export', 'verify', 'verify-portal'))
    parser.add_argument('--directory', type=Path, default=DIRECTORY)
    parser.add_argument('--module', choices=reports.DISTRIBUTIONS)
    parser.add_argument('--commit')
    args = parser.parse_args()
    if args.command == 'export':
        export(args.commit)
    elif args.command == 'verify-portal':
        manifest = verify(args.directory)
        verify_portal(ROOT / 'gradle-plugin/build/staging-repository', manifest['candidateVersion'], args.directory)
    else:
        verify(args.directory, args.module, args.commit)
        print('Verified publication SBOM source cohort, graphs and actual JAR hashes.')


if __name__ == '__main__':
    main()
