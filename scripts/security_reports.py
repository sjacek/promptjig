"""Candidate-bound CycloneDX inventories and fail-closed Grype evidence for the Gradle producer."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DISTRIBUTIONS = ('core', 'spring-ai', 'gradle-plugin', 'maven-plugin')
SCOPES = ('runtime', 'build', 'test', 'provided')
GROUP = 'io.github.sjacek.promptjig'
NAMES = {'core': 'promptjig-core', 'spring-ai': 'promptjig-spring-ai',
         'gradle-plugin': 'promptjig-gradle-plugin', 'maven-plugin': 'promptjig-maven-plugin'}


def read(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON evidence field')
            result[key] = value
        return result
    return json.loads(Path(path).read_text(encoding='utf-8-sig'), object_pairs_hook=unique)


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def policy():
    value = read(ROOT / 'security/scan-policy.json')
    if (value.get('schemaVersion') != 1 or not re.fullmatch(r'\d+\.\d+\.\d+', value.get('scannerVersion', ''))
            or value.get('severityCutoff') != 'high' or value.get('includeUnfixed') is not True
            or value.get('blockingScopes') != list(SCOPES) or value.get('distributions') != list(DISTRIBUTIONS)
            or type(value.get('databaseMaxAgeHours')) is not int or not 1 <= value['databaseMaxAgeHours'] <= 120):
        raise ValueError('Missing or weakened security policy')
    return value


def source_inputs(root=None):
    root = ROOT if root is None else Path(root)
    result = {}
    excluded = {'build', 'target', '.git', '.gradle', '.gradle-user-home', '.idea', '__pycache__'}
    # Prune before traversal: resolver/test workspaces can contain hundreds of
    # thousands of files and must not make every scope's freshness check expensive.
    for directory, children, files in os.walk(root, followlinks=False):
        children[:] = [name for name in children if name not in excluded]
        for name in files:
            path = Path(directory) / name
            relative = path.relative_to(root)
            selected = (path.suffix == '.gradle' or name in {'pom.xml', 'invoker.properties', 'gradle.properties', 'prompt-contracts.lock.json'} or 'src' in relative.parts
                        or relative.parts[0] in {'scripts', 'gradle', 'security', '.mvn', '.github', 'testing'}
                        or relative.as_posix() in {'.gitattributes', 'gradlew', 'gradlew.bat'})
            if selected and path.is_file():
                if path.is_symlink():
                    raise ValueError('Security source input must not be a symlink: ' + relative.as_posix())
                result[relative.as_posix()] = digest(path)
    return result


def validate_native(native):
    if (native.get('schemaVersion') != 1 or native.get('distribution') not in DISTRIBUTIONS
            or set(native.get('scopes', {})) != set(SCOPES) or native.get('consumersResolved') is not True):
        raise ValueError('Incomplete native inventory')
    if native['distribution'] in {'gradle-plugin', 'maven-plugin'} and not native.get('consumerEvidence'):
        raise ValueError('Missing native consumer evidence')
    for item in native.get('consumerEvidence', []):
        path = ROOT / item['file']
        if not path.resolve().is_relative_to((ROOT / 'build/security/native/consumers').resolve()) or path.is_symlink() or digest(path) != item['sha256']:
            raise ValueError('Changed native consumer evidence')
    inputs = {item['file']: item['sha256'] for item in native.get('sourceInputs', [])}
    if not inputs or inputs != source_inputs():
        raise ValueError('Native inventory does not describe current build inputs; regenerate it')
    artifact = Path(native['candidate']['file'])
    expected = ROOT / native['distribution'] / 'build/libs' / (NAMES[native['distribution']] + '-' + native['version'] + '.jar')
    # The Gradle projects use short archive basenames even though their Maven artifactIds differ.
    if not expected.exists():
        expected = ROOT / native['distribution'] / 'build/libs' / (native['distribution'] + '-' + native['version'] + '.jar')
    if artifact.resolve() != expected.resolve() or digest(artifact) != native['candidate']['sha256']:
        raise ValueError('Candidate bytes differ from native evidence')
    hashes = {}
    for graphs in native['scopes'].values():
        for graph in graphs:
            ids = {node['id'] for node in graph['components']}
            if len(ids) != len(graph['components']) or graph['root'] not in ids:
                raise ValueError('Invalid native graph roots/identities')
            if any(child not in ids for node in graph['components'] for child in node['dependencies']):
                raise ValueError('Dangling native dependency')
            for item in graph['artifacts']:
                path = Path(item['file'])
                if path not in hashes:
                    hashes[path] = digest(path)
                if path.is_symlink() or hashes[path] != item['sha256']:
                    raise ValueError('Resolved dependency bytes changed: ' + item['name'])
                if item.get('nativeKind') == 'project-classes-directory':
                    directory = Path(item['nativePath'])
                    classes = list(directory.rglob('*.class'))
                    if not directory.is_dir() or not classes:
                        raise ValueError('Missing native project classes')
                    with zipfile.ZipFile(path) as jar:
                        names = {file.relative_to(directory).as_posix() for file in classes}
                        if names != {name for name in jar.namelist() if name.endswith('.class')}:
                            raise ValueError('Candidate and native project class sets differ')
                        for file in classes:
                            if jar.read(file.relative_to(directory).as_posix()) != file.read_bytes():
                                raise ValueError('Project classes differ from assembled candidate')
    return artifact


def component(group, name, version, checksum=None, generic=False, kind='library'):
    purl = ('pkg:generic/' if generic else 'pkg:maven/') + group + '/' + name + '@' + version
    result = {'type': kind, 'group': group, 'name': name, 'version': version, 'bom-ref': purl, 'purl': purl}
    if checksum:
        result['hashes'] = [{'alg': 'SHA-256', 'content': checksum}]
    return result


def sdk_component(artifact, gradle):
    registry = read(ROOT / 'security/gradle-sdk-artifacts.json')
    if registry.get('schemaVersion') != 1 or not isinstance(registry.get('artifacts'), list):
        raise ValueError('Missing reviewed SDK identity registry')
    matches = [item for item in registry['artifacts'] if item['file'] == artifact['name']]
    if matches:
        if len(matches) != 1 or matches[0]['sha256'] != artifact['sha256']:
            raise ValueError('SDK identity/hash mismatch')
        item = matches[0]
        expected = 'https://repo.maven.apache.org/maven2/' + item['group'].replace('.', '/') + '/' + item['name'] + '/' + item['version'] + '/' + item['file']
        if item['source'] != expected or item['file'] != item['name'] + '-' + item['version'] + '.jar':
            raise ValueError('Invalid reviewed SDK identity')
        value = component(item['group'], item['name'], item['version'], artifact['sha256'])
    elif artifact['name'] in {f'gradle-api-{gradle}.jar', f'gradle-test-kit-{gradle}.jar', f'gradle-installation-beacon-{gradle}.jar'}:
        value = component('gradle', artifact['name'][:-4], gradle, artifact['sha256'], generic=True)
    elif artifact.get('nativeKind') == 'plugin-under-test-metadata':
        value = component('promptjig', 'plugin-under-test-metadata', artifact['sha256'], artifact['sha256'], generic=True, kind='file')
    else:
        raise ValueError('Unreviewed third-party SDK/local artifact: ' + artifact['name'])
    value['properties'] = [{'name': 'promptjig:provided-by-gradle', 'value': gradle}]
    return value


def verify_bundle(native):
    if native['distribution'] != 'gradle-plugin':
        return
    expected = {item['name']: item['sha256'] for graph in native['scopes']['runtime']
                if graph['configuration'] == 'isolatedRuntime' for item in graph['artifacts']}
    with zipfile.ZipFile(native['candidate']['file']) as outer:
        with zipfile.ZipFile(io.BytesIO(outer.read('META-INF/promptjig/runtime.zip'))) as bundle:
            actual = {name: hashlib.sha256(bundle.read(name)).hexdigest() for name in bundle.namelist() if not name.endswith('/')}
    if not expected or actual != expected:
        raise ValueError('Embedded runtime does not match the native isolatedRuntime artifact set')


def bom_for(native, scope, commit):
    """Translate native Gradle-selected component edges; SDK file dependencies retain explicit identities."""
    candidate = component(GROUP, NAMES[native['distribution']], native['version'], native['candidate']['sha256'])
    entries, edges = {candidate['bom-ref']: candidate}, {candidate['bom-ref']: set()}
    provenance = []
    def add(value):
        reference = value['bom-ref']
        old = entries.get(reference)
        if old and old.get('hashes') and value.get('hashes') and old['hashes'] != value['hashes']:
            raise ValueError('Conflicting bytes under one component identity')
        if old is None or value.get('hashes'):
            entries[reference] = value
        edges.setdefault(reference, set())
        return reference
    for graph in native['scopes'][scope]:
        mapping = {}
        for node in graph['components']:
            identity = node['identity']
            if identity['kind'] == 'module':
                value = component(identity['group'], identity['name'], identity['version'])
            elif identity['kind'] == 'project':
                name = identity['path'].lstrip(':')
                if name in NAMES:
                    value = component(GROUP, NAMES[name], native['version'])
                else:
                    # Resolution roots are contexts, not fictitious Maven releases.
                    label = re.sub(r'[^A-Za-z0-9._-]', '-', graph['project'] + '-' + graph['source'] + '-' + graph['configuration'])
                    value = component('promptjig-context', label, native['version'], generic=True, kind='application')
            elif node['id'] == graph['root'] and identity == {'kind': 'file', 'display': 'root'}:
                value = component('promptjig-build', graph['project'].replace(':', '-') + '-' + graph['configuration'],
                                  native['version'], generic=True)
            else:
                raise ValueError('Unclassified native component')
            mapping[node['id']] = add(value)
        for node in graph['components']:
            edges[mapping[node['id']]].update(mapping[child] for child in node['dependencies'])
        root = mapping[graph['root']]
        edges[candidate['bom-ref']].add(root)
        for artifact in graph['artifacts']:
            identity = artifact['identity']
            if identity['kind'] == 'module':
                value = component(identity['group'], identity['name'], identity['version'], artifact['sha256'])
            elif identity['kind'] == 'project':
                value = component(GROUP, NAMES[identity['path'].lstrip(':')], native['version'], artifact['sha256'])
            else:
                value = sdk_component(artifact, native['gradleVersion'])
            reference = add(value)
            # Native file collections do not appear in ResolutionResult's module graph.
            if artifact['component'] not in mapping:
                edges[root].add(reference)
            provenance.append({'component': reference, 'project': graph['project'],
                               'configuration': graph['configuration'], 'source': graph['source']})
    # Build environment roots can be self edges; reachability remains checked below.
    for reference, children in edges.items():
        children.discard(reference)
    value = {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
        'serialNumber': 'urn:uuid:' + str(uuid.uuid5(uuid.NAMESPACE_URL, commit + native['distribution'] + scope + json.dumps(native['sourceInputs'], sort_keys=True))),
        'metadata': {'component': entries[candidate['bom-ref']], 'properties': [
            {'name': 'promptjig:source-commit', 'value': commit},
            {'name': 'promptjig:scope', 'value': scope},
            {'name': 'promptjig:generator', 'value': 'Gradle native ResolutionResult / Promptjig CycloneDX adapter'}]},
        'components': [v for k, v in sorted(entries.items()) if k != candidate['bom-ref']],
        'dependencies': [{'ref': k, 'dependsOn': sorted(v)} for k, v in sorted(edges.items())]}
    validate_bom(value)
    return value, provenance


def validate_bom(bom):
    if bom.get('bomFormat') != 'CycloneDX' or bom.get('specVersion') != '1.6':
        raise ValueError('Expected CycloneDX 1.6')
    components = [bom['metadata']['component'], *bom['components']]
    refs = {item['bom-ref'] for item in components}
    if len(refs) != len(components) or any(not item.get('purl') or item.get('scope') == 'excluded' for item in components):
        raise ValueError('Missing/duplicate/excluded SBOM component')
    graph = {}
    for item in bom['dependencies']:
        if item['ref'] not in refs or item['ref'] in graph or any(child not in refs for child in item['dependsOn']):
            raise ValueError('Invalid SBOM dependency graph')
        graph[item['ref']] = item['dependsOn']
    visited, pending = set(), [bom['metadata']['component']['bom-ref']]
    while pending:
        item = pending.pop()
        if item not in visited:
            visited.add(item)
            pending.extend(graph.get(item, []))
    if visited != refs:
        raise ValueError('SBOM has unreachable components')


def invalidate():
    base = ROOT / 'build/security/reports'
    base.mkdir(parents=True, exist_ok=True)
    for file in base.rglob('*'):
        if file.is_file() and file.name in {'summary.json', 'summary.md', 'findings.json', 'scan-status.json'}:
            file.unlink()


def prepare(commit):
    if not re.fullmatch(r'[a-f0-9]{40}', commit):
        raise ValueError('Expected immutable source commit')
    policy()
    invalidate()
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if head != commit:
        raise ValueError('Requested source commit differs from checked-out HEAD')
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=normal'], cwd=ROOT))
    if os.environ.get('GITHUB_ACTIONS') == 'true' and dirty:
        raise ValueError('CI inventories must describe clean checked-out sources')
    for distribution in DISTRIBUTIONS:
        file = ROOT / 'build/security/native' / (distribution + '.json')
        native = read(file)
        validate_native(native)
        verify_bundle(native)
        for scope in SCOPES:
            output = ROOT / 'build/security/reports' / distribution / scope
            bom, provenance = bom_for(native, scope, commit)
            write(output / 'bom.cdx.json', bom)
            write(output / 'inventory.json', {'schemaVersion': 1, 'sourceCommit': commit,
                'sourceDirty': dirty,
                'distribution': distribution, 'scope': scope, 'candidateVersion': native['version'],
                'candidate': native['candidate'], 'nativeFile': file.relative_to(ROOT).as_posix(),
                'nativeSha256': digest(file), 'sbomSha256': digest(output / 'bom.cdx.json'),
                'provenance': provenance, 'publishedArtifactsVerified': False})
            print('Prepared:', distribution, scope, len(bom['components']) + 1, 'components', flush=True)


def timestamp(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def summarize(output, scanner, database, now=None):
    output = Path(output)
    (output / 'summary.json').unlink(missing_ok=True)
    inventory, findings, status = (read(output / name) for name in ('inventory.json', 'findings.json', 'scan-status.json'))
    rules = policy()
    if inventory['sbomSha256'] != digest(output / 'bom.cdx.json') or inventory['nativeSha256'] != digest(ROOT / inventory['nativeFile']):
        raise ValueError('Changed SBOM or retained native graph')
    native = read(ROOT / inventory['nativeFile'])
    validate_native(native)
    if (type(inventory.get('sourceDirty')) is not bool
            or inventory.get('distribution') != native['distribution'] or inventory.get('candidate') != native['candidate']
            or inventory.get('candidateVersion') != native['version'] or inventory.get('scope') != output.name):
        raise ValueError('Inventory belongs to another candidate or scope')
    expected_bom, _ = bom_for(native, inventory['scope'], inventory['sourceCommit'])
    if read(output / 'bom.cdx.json') != expected_bom:
        raise ValueError('SBOM no longer matches retained native resolution')
    descriptor = findings.get('descriptor', {})
    if scanner.get('version') != rules['scannerVersion'] or descriptor.get('name') != 'grype' or descriptor.get('version') != rules['scannerVersion']:
        raise ValueError('Unexpected scanner identity/version')
    recorded = descriptor.get('db', {}).get('status', {})
    if (database.get('valid') is not True or recorded.get('valid') is not True
            or any(not database.get(key) or database[key] != recorded.get(key) for key in ('built', 'schemaVersion', 'from'))):
        raise ValueError('Missing or inconsistent vulnerability DB identity')
    age = ((now or datetime.now(timezone.utc)) - timestamp(database['built'])).total_seconds() / 3600
    if not 0 <= age <= rules['databaseMaxAgeHours']:
        raise ValueError('Stale or future vulnerability database')
    source = findings.get('source', {})
    target = Path(source.get('target', ''))
    if not target.is_absolute():
        target = ROOT / target
    if source.get('type') != 'sbom-file' or target.resolve() != (output / 'bom.cdx.json').resolve():
        raise ValueError('Scanner read a different input')
    options = descriptor.get('configuration', {})
    if (options.get('only-fixed') is not False or options.get('only-notfixed') is not False
            or options.get('fail-on-severity') != 'high' or options.get('show-suppressed') is not True
            or options.get('exclude') != [] or options.get('vex-documents') != []
            or options.get('ignore-wontfix') or findings.get('ignoredMatches')
            or options.get('externalSources', {}).get('enable') is not False):
        raise ValueError('Scanner policy was weakened or findings suppressed')
    # Grype supplies four built-in OS-header filters. These cannot match our
    # Maven/generic JVM components; reject all advisory or Java-package ignores.
    header_names = {'kernel-headers', 'linux(-.*)?-headers-.*', 'linux-libc-dev', 'linux-kbuild-.*'}
    for ignore in options.get('ignore', []):
        package = ignore.get('package', {})
        if (ignore.get('vulnerability') or ignore.get('vex-status') or package.get('type') not in {'deb', 'rpm'}
                or package.get('name') not in header_names or package.get('language')):
            raise ValueError('Unreviewed vulnerability/package suppression')
    matches = findings.get('matches')
    if not isinstance(matches, list):
        raise ValueError('Missing findings')
    severities = Counter()
    for match in matches:
        vulnerability = match.get('vulnerability', {})
        severity = vulnerability.get('severity', '').lower()
        if severity not in {'unknown', 'negligible', 'low', 'medium', 'high', 'critical'} or not vulnerability.get('id') or not match.get('artifact', {}).get('purl'):
            raise ValueError('Malformed vulnerability finding')
        severities[severity] += 1
    blocked = severities['high'] + severities['critical'] > 0
    expected = 2 if blocked else 0
    if status.get('exitCode') != expected or status.get('sbomSha256') != inventory['sbomSha256'] or status.get('findingsSha256') != digest(output / 'findings.json'):
        raise ValueError('Native scanner exit and retained evidence contradict each other')
    result = {'schemaVersion': 1, 'passed': not blocked, 'sourceCommit': inventory['sourceCommit'],
        'distribution': inventory['distribution'], 'scope': inventory['scope'],
        'candidateVersion': inventory['candidateVersion'], 'candidate': inventory['candidate'],
        'sourceDirty': inventory.get('sourceDirty'),
        'sbomSha256': inventory['sbomSha256'], 'findingsSha256': digest(output / 'findings.json'),
        'scanner': scanner, 'database': database, 'severities': dict(severities)}
    write(output / 'summary.json', result)
    return result


def scan(scanner, database_directory, refresh=False):
    """Continue every scope after a finding/tool error, retain JSON, and fail the aggregate."""
    rules = policy()
    environment = {k: v for k, v in os.environ.items() if not k.startswith('GRYPE_')}
    environment.update(GRYPE_DB_CACHE_DIR=str(Path(database_directory).resolve()), GRYPE_DB_AUTO_UPDATE='false',
                       GRYPE_CHECK_FOR_APP_UPDATE='false', GRYPE_DB_VALIDATE_AGE='true',
                       GRYPE_DB_MAX_ALLOWED_BUILT_AGE=str(rules['databaseMaxAgeHours']) + 'h')
    common = [str(Path(scanner).resolve())]
    config = ['--config', str(ROOT / 'security/grype.yaml')]
    base = ROOT / 'build/security/reports'
    (base / 'summary.json').unlink(missing_ok=True)
    (base / 'summary.md').unlink(missing_ok=True)
    (base / 'failures.json').unlink(missing_ok=True)
    for directory in (base / distribution / scope for distribution in DISTRIBUTIONS for scope in SCOPES):
        for name in ('summary.json', 'summary.md', 'findings.json', 'scan-status.json'):
            (directory / name).unlink(missing_ok=True)
    def capture(args, path):
        with path.open('w', encoding='utf-8') as output:
            subprocess.run([*common, *args], cwd=ROOT, env=environment, check=True, stdout=output, timeout=600)
    capture(['version', '-o', 'json'], base / 'scanner.json')
    if refresh:
        subprocess.run([*common, 'db', 'update', *config], cwd=ROOT, env=environment, check=True, timeout=600)
    capture(['db', 'status', '-o', 'json', *config], base / 'database.json')
    scanner_identity, database = read(base / 'scanner.json'), read(base / 'database.json')
    # Metadata-only known-vulnerable input: no vulnerable JAR is downloaded or executed.
    control = component('org.apache.logging.log4j', 'log4j-core', '2.14.1')
    control_root = component('promptjig', 'scanner-positive-control', '1', generic=True, kind='application')
    control_file = base / 'positive-control.cdx.json'
    write(control_file, {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
        'metadata': {'component': control_root}, 'components': [control],
        'dependencies': [{'ref': control_root['bom-ref'], 'dependsOn': [control['bom-ref']]},
                         {'ref': control['bom-ref'], 'dependsOn': []}]})
    with (base / 'positive-control.findings.json').open('w', encoding='utf-8') as stdout, \
            (base / 'positive-control.log').open('w', encoding='utf-8') as stderr:
        probe = subprocess.run([*common, 'sbom:' + str(control_file), '-o', 'json', '--fail-on', 'high',
            '--show-suppressed', *config], cwd=ROOT, env=environment, stdout=stdout, stderr=stderr, timeout=600)
    control_status = {'exitCode': probe.returncode, 'sbomSha256': digest(control_file),
        'findingsSha256': digest(base / 'positive-control.findings.json'), 'expectedAdvisory': 'GHSA-jfh8-c2jp-5v3q'}
    write(base / 'positive-control.status.json', control_status)
    control_findings = read(base / 'positive-control.findings.json')
    control_status['passed'] = probe.returncode == 2 and any(
            item.get('artifact', {}).get('purl') == control['purl']
            and item.get('vulnerability', {}).get('id') == 'GHSA-jfh8-c2jp-5v3q'
            and item['vulnerability'].get('severity', '').lower() == 'critical'
            for item in control_findings.get('matches', []))
    write(base / 'positive-control.status.json', control_status)
    if not control_status['passed']:
        raise ValueError('Scanner positive control failed; a clean cohort cannot be trusted')
    results, failures = [], []
    for distribution in DISTRIBUTIONS:
        for scope in SCOPES:
            output = base / distribution / scope
            bom = output / 'bom.cdx.json'
            try:
                with (output / 'findings.json').open('w', encoding='utf-8') as stdout, \
                        (output / 'scanner.log').open('w', encoding='utf-8') as stderr:
                    process = subprocess.run([*common, 'sbom:' + str(bom), '-o', 'json', '--fail-on', 'high',
                            '--show-suppressed', *config], cwd=ROOT, env=environment, timeout=600,
                            stdout=stdout, stderr=stderr)
                write(output / 'scan-status.json', {'exitCode': process.returncode,
                      'sbomSha256': digest(bom), 'findingsSha256': digest(output / 'findings.json')})
                result = summarize(output, scanner_identity, database)
                results.append(result)
                print(distribution, scope, result['severities'], 'PASS' if result['passed'] else 'BLOCKED', flush=True)
            except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as failure:
                failures.append({'distribution': distribution, 'scope': scope, 'error': str(failure)})
                print(distribution, scope, 'INVALID:', str(failure), flush=True)
    if len(results) != len(DISTRIBUTIONS) * len(SCOPES):
        write(base / 'failures.json', failures)
        raise ValueError('Incomplete security cohort; retained scope logs describe failures')
    # A concurrent source/dependency change after an early scope must invalidate
    # the aggregate too, even if every individual scanner process has exited.
    for distribution in DISTRIBUTIONS:
        validate_native(read(ROOT / 'build/security/native' / (distribution + '.json')))
    expected_scopes = {(distribution, scope) for distribution in DISTRIBUTIONS for scope in SCOPES}
    if ({(item['distribution'], item['scope']) for item in results} != expected_scopes
            or len({item['sourceCommit'] for item in results}) != 1 or len({item['candidateVersion'] for item in results}) != 1):
        raise ValueError('Different source/version cohorts')
    summary = {'schemaVersion': 1, 'passed': all(item['passed'] for item in results),
               'sourceCommit': results[0]['sourceCommit'], 'candidateVersion': results[0]['candidateVersion'],
               'sourceDirty': any(item['sourceDirty'] for item in results),
               'scopes': results, 'scanner': scanner_identity, 'database': database}
    write(base / 'summary.json', summary)
    if not summary['passed']:
        raise ValueError('High/Critical vulnerabilities block release; all severity JSON reports were retained')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'scan', 'policy', 'invalidate'))
    parser.add_argument('--commit')
    parser.add_argument('--scanner')
    parser.add_argument('--database-directory', default='build/security-tools/grype-db')
    parser.add_argument('--refresh', action='store_true')
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare(args.commit or subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip())
    elif args.command == 'scan':
        scan(args.scanner, args.database_directory, args.refresh)
    elif args.command == 'invalidate':
        invalidate()
    else:
        rules = policy()
        print(json.dumps(rules))
        if os.environ.get('GITHUB_OUTPUT'):
            with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
                output.write('scanner-release-tag=v' + rules['scannerVersion'] + '\n')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as failure:
        raise SystemExit(str(failure))
