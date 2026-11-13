"""Fresh registry/source/signature evidence, kept separate from uncommitted candidate acceptance."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

import security_reports as reports

ROOT = reports.ROOT
OUTPUT = ROOT / 'build/security/published'
CENTRAL = 'https://repo.maven.apache.org/maven2/'
PORTAL = 'https://plugins.gradle.org/m2/'
GROUP = reports.GROUP
CASES = [('gradle-plugin', '0.1.0', PORTAL), ('core', '0.2.0', CENTRAL), ('spring-ai', '0.2.0', CENTRAL)]


def fetch(url, target, optional=False):
    request = urllib.request.Request(url, headers={'User-Agent': 'Promptjig-public-artifact-audit'})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = response.read(64 * 1024 * 1024 + 1)
            if len(payload) > 64 * 1024 * 1024:
                raise ValueError('Oversized registry payload')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)
            return {'source': url, 'resolvedUrl': response.url, 'sha256': reports.digest(target), 'size': len(payload)}
    except urllib.error.HTTPError as failure:
        if optional and failure.code == 404:
            return None
        raise


def check_sidecar(url, file):
    for algorithm in ('sha256', 'sha512', 'sha1'):
        sidecar = file.with_name(file.name + '.' + algorithm)
        evidence = fetch(url + '.' + algorithm, sidecar, optional=True)
        if evidence is not None:
            expected = sidecar.read_text().split()[0].lower()
            if expected != hashlib.new(algorithm, file.read_bytes()).hexdigest():
                raise ValueError('Registry sidecar mismatch: ' + file.name)
            return algorithm
    raise ValueError('No registry checksum: ' + file.name)


def verify_signature(file, signature, home):
    """An isolated public-only keyring. A valid signature is not identity certification."""
    home.mkdir(parents=True, exist_ok=True)
    home.chmod(0o700)
    gpg = shutil.which('gpg')
    if not gpg and os.name == 'nt':
        candidate = Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Git/usr/bin/gpg.exe'
        if candidate.is_file():
            gpg = str(candidate)
    if not gpg:
        raise ValueError('Install GnuPG for public signature verification')
    base = [gpg, '--batch', '--no-options', '--homedir', str(home)]
    packets = subprocess.run([*base, '--list-packets', str(signature)], capture_output=True, text=True, check=True)
    fingerprint = reports.read(ROOT / 'security/published-artifacts.json')['publisherPrimaryFingerprint']
    match = re.search(r'issuer fpr v[45] ([0-9A-F]{40,64})', packets.stdout)
    key_id = re.search(r'issuer key ID ([0-9A-F]{16})', packets.stdout)
    if ((match and match[1] != fingerprint) or not (match or key_id)
            or (key_id and key_id[1] != fingerprint[-16:])):
        raise ValueError('Signature does not match the reviewed public signing identity')
    key = home / (fingerprint + '.asc')
    if not key.exists():
        fetch(reports.read(ROOT / 'security/published-artifacts.json')['publicKeyUrl'], key)
    # gpgv needs only public packets; avoid agents, secret-key discovery and an
    # ownertrust database, including Windows agent socket/path limitations.
    keyring = home / 'reviewed-public-keys.gpg'
    subprocess.run([*base, '--yes', '--output', str(keyring), '--dearmor', str(key)], check=True, capture_output=True)
    gpgv = str(Path(gpg).with_name('gpgv.exe' if os.name == 'nt' else 'gpgv')) if Path(gpg).is_absolute() else 'gpgv'
    result = subprocess.run([gpgv, '--homedir', str(home), '--keyring', keyring.name, '--status-fd', '1', str(signature), str(file)],
                            capture_output=True, text=True)
    signature.with_name(signature.name + '.verification.txt').write_text(result.stdout + result.stderr)
    result.check_returncode()
    if '[GNUPG:] VALIDSIG ' + fingerprint not in result.stdout:
        raise ValueError('OpenPGP signer differs from signature fingerprint')
    return fingerprint


def inspect_jar(file, module, tag):
    bytecode, multi_release, sources = [], [], []
    with zipfile.ZipFile(file) as jar:
        if jar.testzip() or 'META-INF/LICENSE' not in jar.namelist():
            raise ValueError('Invalid JAR or missing license')
        for name in jar.namelist():
            if name.endswith('.class'):
                payload = jar.read(name)
                if payload[:4] != b'\xca\xfe\xba\xbe':
                    raise ValueError('Invalid bytecode')
                major = int.from_bytes(payload[6:8], 'big')
                (multi_release if name.startswith('META-INF/versions/') else bytecode).append(major)
            if name.endswith('.java'):
                expected = subprocess.check_output(['git', 'show', tag + ':' + module + '/src/main/java/' + name], cwd=ROOT)
                # Published sources preserve platform checkout line endings; compare
                # normalized lines, not a claim of reproducible binary compilation.
                if jar.read(name).replace(b'\r\n', b'\n') != expected.replace(b'\r\n', b'\n'):
                    raise ValueError('Published source differs from tag: ' + name)
                sources.append(name)
    if bytecode and max(bytecode) > 61:
        raise ValueError('Published base classes exceed Java 17')
    if file.name.endswith('-sources.jar'):
        prefix = module + '/src/main/java/'
        tagged = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', tag, prefix], cwd=ROOT, text=True)
        expected = {name.removeprefix(prefix) for name in tagged.splitlines() if name.endswith('.java')}
        if not expected or set(sources) != expected:
            raise ValueError('Published source archive does not cover the tag module')
    return {'baseClassMajors': sorted(set(bytecode)), 'multiReleaseClassMajors': sorted(set(multi_release)),
            'sourcesCompared': sources}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / 'summary.json').unlink(missing_ok=True)
    result = {'schemaVersion': 1, 'observedAt': datetime.now(timezone.utc).isoformat(),
              'candidateVersion': '0.3.0', 'candidateRegistryAudit': 'not published; local staging is a separate check',
              'distributions': [], 'errors': []}
    for module, version, registry in CASES:
        item = {'distribution': module, 'version': version, 'registry': registry, 'files': {}, 'passed': False}
        result['distributions'].append(item)
        try:
            tag = 'v' + version
            item['sourceCommit'] = subprocess.check_output(['git', 'rev-parse', tag + '^{commit}'], cwd=ROOT, text=True).strip()
            expected = reports.read(ROOT / 'security/published-artifacts.json')['sourceCommits'][tag]
            if item['sourceCommit'] != expected:
                raise ValueError('Source tag moved from its reviewed commit')
            kind = subprocess.check_output(['git', 'cat-file', '-t', tag], cwd=ROOT, text=True).strip()
            item['sourceTagKind'] = kind
            if kind == 'tag':
                tag_text = subprocess.check_output(['git', 'cat-file', 'tag', tag], cwd=ROOT)
                item['sourceTagSignature'] = 'present; requires separate verification' if b'-----BEGIN PGP SIGNATURE-----' in tag_text else 'unsigned annotated tag'
            else:
                item['sourceTagSignature'] = 'lightweight tag; no tag signature'
            artifact = reports.NAMES[module]
            folder = OUTPUT / module
            prefix = GROUP.replace('.', '/') + '/' + artifact + '/' + version + '/' + artifact + '-' + version
            for suffix in ('.pom', '.jar', '-sources.jar', '-javadoc.jar', '.module'):
                file = folder / (artifact + '-' + version + suffix)
                evidence = fetch(registry + prefix + suffix, file, optional=suffix in {'.module', '-javadoc.jar'})
                if evidence is None:
                    item['files'][suffix] = {'available': False}
                    continue
                evidence['checksumAlgorithm'] = check_sidecar(registry + prefix + suffix, file)
                signature = file.with_name(file.name + '.asc')
                signed = fetch(registry + prefix + suffix + '.asc', signature, optional=True)
                evidence['signature'] = verify_signature(file, signature, OUTPUT / 'public-keyring') if signed else 'not available (HTTP 404)'
                if suffix.endswith('.jar'):
                    evidence.update(inspect_jar(file, module, tag))
                if suffix == '.module':
                    model = reports.read(file)
                    if any(model['component'].get(k) != v for k, v in [('group', GROUP), ('module', artifact), ('version', version)]):
                        raise ValueError('Published Gradle module coordinates differ')
                    checked = []
                    for variant in model['variants']:
                        for payload in variant.get('files', []):
                            name = payload['name']
                            if Path(name).name != name or payload.get('url') != name:
                                raise ValueError('Unexpected module artifact location')
                            downloaded = folder / name
                            if not downloaded.is_file() or payload['sha256'] != reports.digest(downloaded) or payload['size'] != downloaded.stat().st_size:
                                raise ValueError('Module metadata and downloaded payload disagree')
                            checked.append(name)
                    if not checked:
                        raise ValueError('Module metadata has no payloads')
                    evidence['payloadsChecked'] = sorted(set(checked))
                if suffix == '.pom':
                    pom = ET.parse(file).getroot()
                    ns = {'p': 'http://maven.apache.org/POM/4.0.0'}
                    if any(pom.findtext('p:' + k, namespaces=ns) != v for k, v in
                            [('groupId', GROUP), ('artifactId', artifact), ('version', version)]):
                        raise ValueError('Published POM coordinates differ')
                    evidence['dependencies'] = [{k: dep.findtext('p:' + k, namespaces=ns) for k in
                        ('groupId', 'artifactId', 'version', 'scope', 'optional')}
                        for dep in pom.findall('p:dependencies/p:dependency', ns)]
                    evidence['metadata'] = {k: pom.findtext('p:' + k, namespaces=ns) for k in ('name', 'description', 'url')}
                    if not all(evidence['metadata'].values()) or pom.find('p:licenses/p:license', ns) is None:
                        raise ValueError('Incomplete published metadata')
                item['files'][suffix] = evidence
            if module == 'gradle-plugin':
                marker_path = 'io/github/sjacek/promptjig/io.github.sjacek.promptjig.gradle.plugin/0.1.0/io.github.sjacek.promptjig.gradle.plugin-0.1.0.pom'
                marker = folder / 'plugin-marker.pom'
                item['marker'] = fetch(PORTAL + marker_path, marker)
                item['marker']['checksumAlgorithm'] = check_sidecar(PORTAL + marker_path, marker)
                tree = ET.parse(marker).getroot()
                ns = {'p': 'http://maven.apache.org/POM/4.0.0'}
                dependencies = tree.findall('p:dependencies/p:dependency', ns)
                if len(dependencies) != 1 or any(dependencies[0].findtext('p:' + k, namespaces=ns) != v
                        for k, v in [('groupId', GROUP), ('artifactId', artifact), ('version', version)]):
                    raise ValueError('Portal marker points to another implementation')
                with zipfile.ZipFile(folder / (artifact + '-' + version + '.jar')) as jar:
                    bundle = jar.read('META-INF/promptjig/runtime.zip')
                    with zipfile.ZipFile(io.BytesIO(bundle)) as nested:
                        item['embeddedRuntime'] = []
                        for name in nested.namelist():
                            if name.endswith('/'):
                                continue
                            payload = nested.read(name)
                            with zipfile.ZipFile(io.BytesIO(payload)) as library:
                                majors = {int.from_bytes(library.read(entry)[6:8], 'big') for entry in library.namelist()
                                    if entry.endswith('.class') and not entry.startswith('META-INF/versions/')}
                            if majors and max(majors) > 61:
                                raise ValueError('Embedded published base bytecode exceeds Java 17')
                            item['embeddedRuntime'].append({'file': name, 'sha256': hashlib.sha256(payload).hexdigest(), 'baseClassMajors': sorted(majors)})
                item['sourceCoverage'] = 'Adapter source archive compared with tag; embedded core 0.1 has no separately published source archive here'
            item['passed'] = True
        except (OSError, ValueError, KeyError, subprocess.SubprocessError, ET.ParseError, zipfile.BadZipFile) as failure:
            item['error'] = type(failure).__name__ + ': ' + str(failure)
            result['errors'].append(module)
        reports.write(OUTPUT / 'summary.json', result)
    result['passed'] = not result['errors']
    reports.write(OUTPUT / 'summary.json', result)
    if not result['passed']:
        raise ValueError('Published audit incomplete; retained report names unavailable or inconsistent evidence')


if __name__ == '__main__':
    main()
