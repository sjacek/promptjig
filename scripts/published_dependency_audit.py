"""Native runtime inventories of freshly audited registry bytes, independent of the 0.3.0 candidate."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import io
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

import ci_dependency_cache as cache
import published_artifact_audit as published
import security_reports as reports

ROOT = reports.ROOT
OUTPUT = published.OUTPUT


def embedded_components(file, version):
    """Exact byte matches against reviewed external identities; own embedded core is explicit."""
    identities = cache.checksums()
    with zipfile.ZipFile(file) as jar:
        with zipfile.ZipFile(io.BytesIO(jar.read('META-INF/promptjig/runtime.zip'))) as bundle:
            result = []
            for name in bundle.namelist():
                if name.endswith('/'):
                    continue
                checksum = hashlib.sha256(bundle.read(name)).hexdigest()
                matches = [key[:3] for key, hashes in identities.items()
                           if isinstance(key, tuple) and key[3] == name and checksum in hashes]
                if name == 'core-' + version + '.jar':
                    # This internal 0.1 module was never a standalone Central
                    # publication. Do not invent a historical Maven coordinate.
                    value = reports.component('promptjig', 'embedded-core', version, checksum, generic=True)
                else:
                    if len(matches) != 1:
                        raise ValueError('Embedded published dependency has no unique reviewed identity: ' + name)
                    value = reports.component(*matches[0], checksum)
                value['properties'] = [{'name': 'promptjig:contained-in', 'value': reports.digest(file)}]
                result.append(value)
            return result


def prepare():
    audit = reports.read(OUTPUT / 'summary.json')
    if audit.get('passed') is not True:
        raise ValueError('Complete the fresh registry/source/signature audit first')
    project = OUTPUT / 'resolution'
    project.mkdir(parents=True, exist_ok=True)
    (project / 'settings.gradle').write_text("rootProject.name = 'published-dependency-audit-testdata'\n")
    names = {'gradle-plugin': 'securityPublishedGradle', 'core': 'securityPublishedCore', 'spring-ai': 'securityPublishedSpring'}
    text = "plugins { id 'java' }\nrepositories { mavenCentral(); maven { url = uri('https://plugins.gradle.org/m2/') } }\nconfigurations {\n"
    text += ''.join(' ' + name + '\n' for name in names.values()) + '}\ndependencies {\n'
    for module, version, _ in published.CASES:
        text += ' ' + names[module] + " '" + reports.GROUP + ':' + reports.NAMES[module] + ':' + version + "'\n"
    (project / 'build.gradle').write_text(text + '}\n')
    (project / 'gradle').mkdir(exist_ok=True)
    metadata = ET.parse(ROOT / 'gradle/verification-metadata.xml')
    namespace = 'https://schema.gradle.org/dependency-verification'
    ns = {'v': namespace}
    components = metadata.getroot().find('v:components', ns)
    for item in reports.read(ROOT / 'security/published-dependency-checksums.json')['artifacts']:
        component = next((node for node in components if all(node.get(key) == item[key] for key in ('group', 'name', 'version'))), None)
        if component is None:
            component = ET.SubElement(components, '{' + namespace + '}component', {key: item[key] for key in ('group', 'name', 'version')})
        artifact = next((node for node in component if node.get('name') == item['file']), None)
        if artifact is not None:
            if item['sha256'] not in {node.get('value') for node in artifact.findall('v:sha256', ns)}:
                raise ValueError('Historical supplement contradicts the main checksum policy')
        else:
            artifact = ET.SubElement(component, '{' + namespace + '}artifact', {'name': item['file']})
            ET.SubElement(artifact, '{' + namespace + '}sha256', {'value': item['sha256'], 'origin': 'Reviewed isolated historical audit; Maven Central SHA-256'})
    ET.register_namespace('', namespace)
    metadata.write(project / 'gradle/verification-metadata.xml', encoding='utf-8', xml_declaration=True)
    raw = project / 'native.json'
    command = str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
    subprocess.run([command, '-p', str(project), 'securityConsumerInventory', '-I',
        str(ROOT / 'scripts/security_consumer_inventory.gradle'), '-PsecurityOutput=' + str(raw),
        '--offline', '--no-configuration-cache', '--dependency-verification=strict', '--console=plain'], cwd=ROOT, check=True, timeout=300)
    graphs = reports.read(raw)
    for distribution in audit['distributions']:
        module, version = distribution['distribution'], distribution['version']
        selected = [graph for graph in graphs if graph['configuration'] == names[module]]
        if len(selected) != 1:
            raise ValueError('Missing published native graph')
        file = OUTPUT / module / (reports.NAMES[module] + '-' + version + '.jar')
        if reports.digest(file) != distribution['files']['.jar']['sha256']:
            raise ValueError('Fresh registry JAR changed since its audit')
        root = [item for item in selected[0]['artifacts'] if item['identity'] ==
            {'kind': 'module', 'group': reports.GROUP, 'name': reports.NAMES[module], 'version': version}]
        if len(root) != 1 or root[0]['sha256'] != reports.digest(file):
            raise ValueError('Native resolver and fresh registry bytes disagree')
        for graph in selected:
            for artifact in graph['artifacts']:
                if reports.digest(artifact['file']) != artifact['sha256']:
                    raise ValueError('Published resolved dependency bytes changed')
        native = {'distribution': module, 'version': version, 'gradleVersion': '9.9.1',
            'candidate': {'file': str(file), 'sha256': reports.digest(file)}, 'sourceInputs': distribution['files'],
            'scopes': {'runtime': selected}}
        bom, provenance = reports.bom_for(native, 'runtime', distribution['sourceCommit'])
        if module == 'gradle-plugin':
            contained = embedded_components(file, version)
            existing = {value['bom-ref'] for value in bom['components']}
            root_ref = bom['metadata']['component']['bom-ref']
            edges = {value['ref']: set(value['dependsOn']) for value in bom['dependencies']}
            for value in contained:
                if value['bom-ref'] in existing:
                    raise ValueError('Duplicate embedded identity')
                bom['components'].append(value)
                edges[root_ref].add(value['bom-ref'])
                edges[value['bom-ref']] = set()
            bom['dependencies'] = [{'ref': key, 'dependsOn': sorted(value)} for key, value in sorted(edges.items())]
        reports.validate_bom(bom)
        output = OUTPUT / module
        reports.write(output / 'bom.cdx.json', bom)
        reports.write(output / 'inventory.json', {'distribution': module, 'version': version,
            'sourceCommit': distribution['sourceCommit'], 'jarSha256': reports.digest(file),
            'sbomSha256': reports.digest(output / 'bom.cdx.json'), 'nativeSha256': reports.digest(raw),
            'scope': 'published runtime, including actual nested Gradle runtime; not historical build/test tooling',
            'provenance': provenance})


def validate_published_bytes(inventory):
    file = OUTPUT / inventory['distribution'] / (reports.NAMES[inventory['distribution']] + '-' + inventory['version'] + '.jar')
    if reports.digest(file) != inventory['jarSha256']:
        raise ValueError('Published JAR changed since native inventory')
    raw = OUTPUT / 'resolution/native.json'
    if reports.digest(raw) != inventory['nativeSha256']:
        raise ValueError('Changed published native resolution')
    for graph in reports.read(raw):
        for artifact in graph['artifacts']:
            if reports.digest(artifact['file']) != artifact['sha256']:
                raise ValueError('Published resolved dependency changed since inventory')


def severity_counts(findings):
    if not isinstance(findings.get('matches'), list):
        raise ValueError('Missing published findings')
    result = Counter()
    for item in findings['matches']:
        vulnerability = item.get('vulnerability', {})
        severity = vulnerability.get('severity', '').lower()
        if (severity not in {'unknown', 'negligible', 'low', 'medium', 'high', 'critical'}
                or not vulnerability.get('id') or not item.get('artifact', {}).get('purl')):
            raise ValueError('Malformed published finding')
        result[severity] += 1
    return result


def scan(scanner, database_directory):
    (OUTPUT / 'scan-summary.json').unlink(missing_ok=True)
    for module, _, _ in published.CASES:
        (OUTPUT / module / 'scan-summary.json').unlink(missing_ok=True)
    environment = {key: value for key, value in os.environ.items() if not key.startswith('GRYPE_')}
    environment.update(GRYPE_DB_CACHE_DIR=str(Path(database_directory).resolve()), GRYPE_DB_AUTO_UPDATE='false',
        GRYPE_DB_VALIDATE_AGE='true', GRYPE_DB_MAX_ALLOWED_BUILT_AGE='120h', GRYPE_CHECK_FOR_APP_UPDATE='false')
    config = ['--config', str(ROOT / 'security/grype.yaml')]
    def identity(arguments):
        import json
        return json.loads(subprocess.check_output([scanner, *arguments], env=environment, cwd=ROOT, text=True, timeout=120))
    scanner_identity = identity(['version', '-o', 'json'])
    database = identity(['db', 'status', '-o', 'json', *config])
    age = (datetime.now(timezone.utc) - reports.timestamp(database['built'])).total_seconds() / 3600
    if scanner_identity['version'] != reports.policy()['scannerVersion'] or not database['valid'] or not 0 <= age <= 120:
        raise ValueError('Unexpected scanner or stale database')
    results = []
    for module, version, _ in published.CASES:
        output = OUTPUT / module
        inventory = reports.read(output / 'inventory.json')
        validate_published_bytes(inventory)
        bom = output / 'bom.cdx.json'
        if inventory['sbomSha256'] != reports.digest(bom) or inventory['nativeSha256'] != reports.digest(OUTPUT / 'resolution/native.json'):
            raise ValueError('Changed published SBOM/native cohort')
        with (output / 'findings.json').open('w', encoding='utf-8') as stdout, (output / 'scanner.log').open('w', encoding='utf-8') as stderr:
            process = subprocess.run([scanner, 'sbom:' + str(bom), '-o', 'json', '--fail-on', 'high', '--show-suppressed', *config],
                cwd=ROOT, env=environment, stdout=stdout, stderr=stderr, timeout=600)
        findings = reports.read(output / 'findings.json')
        descriptor = findings['descriptor']
        options = descriptor['configuration']
        if (descriptor['version'] != scanner_identity['version'] or descriptor['db']['status'] != database
                or findings['source'] != {'type': 'sbom-file', 'target': str(bom)}
                or options['only-fixed'] is not False or options['only-notfixed'] is not False
                or options['exclude'] or options['vex-documents'] or findings.get('ignoredMatches')
                or options['fail-on-severity'] != 'high' or options['show-suppressed'] is not True
                or options.get('ignore-wontfix') or options.get('externalSources', {}).get('enable') is not False):
            raise ValueError('Contradictory or weakened published scan evidence')
        for ignore in options.get('ignore', []):
            package = ignore.get('package', {})
            if (ignore.get('vulnerability') or ignore.get('vex-status') or package.get('type') not in {'deb', 'rpm'}
                    or package.get('name') not in {'kernel-headers', 'linux(-.*)?-headers-.*', 'linux-libc-dev', 'linux-kbuild-.*'}
                    or package.get('language')):
                raise ValueError('Unreviewed published finding suppression')
        severities = severity_counts(findings)
        blocked = severities['high'] + severities['critical'] > 0
        if process.returncode != (2 if blocked else 0):
            raise ValueError('Published scanner exit does not agree with retained findings')
        result = {**inventory, 'passed': not blocked, 'severities': dict(severities),
            'nativeExitCode': process.returncode, 'findingsSha256': reports.digest(output / 'findings.json')}
        reports.write(output / 'scan-summary.json', result)
        results.append(result)
        print(module, version, dict(severities), 'BLOCKED' if blocked else 'PASS', flush=True)
    summary = {'schemaVersion': 1, 'scanner': scanner_identity, 'database': database,
        'passed': all(item['passed'] for item in results), 'distributions': results,
        'candidateEvidence': False}
    for result in results:
        validate_published_bytes(result)
    reports.write(OUTPUT / 'scan-summary.json', summary)
    if not summary['passed']:
        raise ValueError('Published runtime contains High/Critical findings; reports retained separately from candidate')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'scan'))
    parser.add_argument('--scanner')
    parser.add_argument('--database-directory')
    args = parser.parse_args()
    prepare() if args.command == 'prepare' else scan(args.scanner, args.database_directory)
