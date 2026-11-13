"""Check IDE source coverage; explicitly update it from official repositories outside CI."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'v': 'https://schema.gradle.org/dependency-verification'}
REPOSITORIES = ('https://repo.maven.apache.org/maven2/', 'https://plugins.gradle.org/m2/')
AVAILABILITY = 'gradle/ide-source-availability.json'
ORIGIN = 'Verified official repository artifact and checksum sidecar for IDE import'


def read_baseline(root):
    path = root / 'gradle/verification-metadata.xml'
    if path.is_symlink():
        raise ValueError('Verification metadata must be a regular file')
    original = path.read_bytes()
    document = ET.fromstring(original)
    configuration = document.find('v:configuration', NS)
    if (configuration is None or configuration.findtext('v:verify-metadata', namespaces=NS) != 'true'
            or configuration.find('v:trusted-artifacts', NS) is not None):
        raise ValueError('IDE checks require strict metadata verification without artifact trust exceptions')
    artifacts = {}
    binaries = {}
    for component in document.findall('v:components/v:component', NS):
        coordinate = tuple(component.get(key, '') for key in ('group', 'name', 'version'))
        for value in coordinate:
            if not re.fullmatch(r'[A-Za-z0-9_+.-]+', value) or '..' in value:
                raise ValueError('Invalid Maven coordinate')
        for artifact in component.findall('v:artifact', NS):
            identity = (*coordinate, artifact.get('name'))
            checksums = {value.get('value', '') for value in artifact.findall('v:sha256', NS)}
            if identity in artifacts or not checksums or any(not re.fullmatch(r'[a-f0-9]{64}', c) for c in checksums):
                raise ValueError('Duplicate artifact or invalid SHA-256: ' + ':'.join(identity))
            artifacts[identity] = checksums
            stem = coordinate[1] + '-' + coordinate[2]
            if (re.fullmatch(re.escape(stem) + r'(?:-[A-Za-z0-9_+.-]+)?\.jar', identity[3])
                    and not identity[3].endswith(('-sources.jar', '-javadoc.jar'))):
                binaries.setdefault(identity[:3], set()).update(checksums)
    if not binaries:
        raise ValueError('Empty binary verification baseline')
    return original, artifacts, binaries


def required_artifacts(binaries, sdk):
    if sdk.get('schemaVersion') != 1 or not sdk.get('modules'):
        raise ValueError('Missing Gradle SDK inventory')
    required = {(*key, key[1] + '-' + key[2] + '-sources.jar') for key in binaries}
    for module in sdk['modules']:
        key = tuple(module[field] for field in ('group', 'name', 'version'))
        if (key[0] not in ('org.apache.groovy', 'org.codehaus.groovy')
                or not re.fullmatch(r'groovy(?:-[a-z0-9]+)*', key[1])
                or not re.fullmatch(r'\d+\.\d+\.\d+(?:[-.][A-Za-z0-9]+)*', key[2])):
            raise ValueError('Invalid Gradle SDK coordinate')
        required.update((*key, key[1] + '-' + key[2] + suffix) for suffix in ('-sources.jar', '.module'))
    return required


def read_availability(root):
    path = root / AVAILABILITY
    if not path.exists():
        return None, {}
    if path.is_symlink():
        raise ValueError('Source availability policy must be a regular file')
    original = path.read_bytes()
    value = json.loads(original)
    if value.get('schemaVersion') != 1 or not isinstance(value.get('unpublished'), dict):
        raise ValueError('Invalid source availability policy')
    return original, value['unpublished']


def validate_coverage(artifacts, binaries, required, unpublished):
    missing = []
    for identity in sorted(required):
        key = ':'.join(identity)
        if key in unpublished:
            if (identity in artifacts or identity[:3] not in binaries
                    or not identity[3].endswith('-sources.jar')
                    or unpublished[key] != {'binarySha256': sorted(binaries[identity[:3]])}):
                raise ValueError('Stale or overbroad unpublished-source record: ' + key)
        elif identity not in artifacts:
            missing.append(key)
    if set(unpublished) - {':'.join(identity) for identity in required}:
        raise ValueError('Unpublished-source policy contains unknown artifacts')
    if missing:
        raise ValueError('Missing IDE verification metadata:\n  ' + '\n  '.join(missing)
                         + '\nRun python scripts/ide_dependency_verification.py update locally; review the diff, '
                           'then run check --verify-downloads before committing the dependency update.')


def download(identity):
    group, name, version, filename = identity
    relative = '/'.join((group.replace('.', '/'), name, version, filename))
    for repository in REPOSITORIES:
        url = repository + relative
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read(), url
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status != 404:
                raise ValueError('Official repository request failed: ' + url) from error
        except urllib.error.URLError as error:
            raise ValueError('Official repository unavailable: ' + url) from error
    return None, None


def reviewed_download(identity):
    content, url = download(identity)
    if content is None:
        return identity, None
    # A separate trusted origin is still needed in review; a sidecar does not prove publisher identity.
    for algorithm in ('sha512', 'sha256', 'sha1'):
        try:
            with urllib.request.urlopen(url + '.' + algorithm, timeout=60) as response:
                expected = response.read().decode('ascii').strip().split()[0].lower()
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status == 404:
                continue
            raise ValueError('Cannot verify repository sidecar: ' + url) from error
        actual = hashlib.new(algorithm, content).hexdigest()
        if expected != actual:
            raise ValueError('Official repository sidecar mismatch: ' + url)
        return identity, {'sha256': hashlib.sha256(content).hexdigest(), 'url': url,
                          'sidecar': algorithm, 'sidecarValue': expected}
    raise ValueError('No official checksum sidecar: ' + url)


def verify_download(identity, artifacts, unpublished):
    content, url = download(identity)
    key = ':'.join(identity)
    if key in unpublished:
        if content is not None:
            raise ValueError('Previously unpublished sources now exist; review and pin them: ' + key)
    elif content is None or hashlib.sha256(content).hexdigest() not in artifacts[identity]:
        raise ValueError('Missing artifact or reviewed SHA-256 mismatch: ' + key)


def add_checksums(original, reviewed):
    text = original.decode('utf-8')
    by_component = {}
    for identity, record in sorted(reviewed.items()):
        if record is not None:
            by_component.setdefault(identity[:3], []).append((identity[3], record['sha256']))
    for coordinate, records in by_component.items():
        opening = '      <component ' + ' '.join(f'{k}="{v}"' for k, v in zip(
            ('group', 'name', 'version'), coordinate)) + '>'
        additions = ''.join(f'\n         <artifact name="{filename}">\n'
                            f'            <sha256 value="{checksum}" origin="{ORIGIN}"/>\n'
                            '         </artifact>' for filename, checksum in records)
        if opening in text:
            if text.count(opening) != 1:
                raise ValueError('Ambiguous component: ' + str(coordinate))
            text = text.replace(opening, opening + additions, 1)
        else:
            block = opening + additions + '\n      </component>\n'
            match = next((match for match in re.finditer(r'      <component group="([^"]+)" '
                r'name="([^"]+)" version="([^"]+)">', text) if match.groups() > coordinate), None)
            offset = match.start() if match else text.index('   </components>')
            text = text[:offset] + block + text[offset:]
    ET.fromstring(text)  # Refuse malformed output before touching the policy.
    return text.encode('utf-8')


def atomic_write(path, content, expected):
    if (path.read_bytes() if path.exists() else None) != expected:
        raise ValueError('Policy changed concurrently; retry after reviewing it: ' + str(path))
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + '.', suffix='.tmp', delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(content)
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def update(root, sdk):
    if any(os.environ.get(key, '').lower() not in ('', '0', 'false')
           for key in ('CI', 'GITHUB_ACTIONS', 'GITLAB_CI', 'TF_BUILD', 'JENKINS_URL', 'BUILD_BUILDID')):
        raise ValueError('CI may only check reviewed IDE checksums; update is local maintenance only')
    original, artifacts, binaries = read_baseline(root)
    policy_original, _ = read_availability(root)
    required = required_artifacts(binaries, sdk)
    with ThreadPoolExecutor(max_workers=8) as pool:
        reviewed = dict(pool.map(reviewed_download, sorted(required - artifacts.keys())))
    unpublished = {}
    for identity, record in reviewed.items():
        if record is None:
            if identity[:3] not in binaries or not identity[3].endswith('-sources.jar'):
                raise ValueError('Required Gradle SDK metadata is unpublished: ' + ':'.join(identity))
            unpublished[':'.join(identity)] = {'binarySha256': sorted(binaries[identity[:3]])}
    updated = add_checksums(original, reviewed)
    policy = (json.dumps({'schemaVersion': 1, 'unpublished': unpublished}, indent=2, sort_keys=True) + '\n').encode()
    report = {'schemaVersion': 1, 'gradleVersion': sdk['gradleVersion'],
              'reviewed': {':'.join(k): v for k, v in reviewed.items()},
              'unpublishedRepositories': list(REPOSITORIES)}
    report_path = root / 'build/ide-dependency-verification/review.json'
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    if updated != original:
        atomic_write(root / 'gradle/verification-metadata.xml', updated, original)
    if policy != policy_original:
        atomic_write(root / AVAILABILITY, policy, policy_original)
    print(f'Added {sum(v is not None for v in reviewed.values())} IDE checksums; '
          f'{len(unpublished)} exact unpublished-source records. Review the policy diff and {report_path}.')


def check(root, sdk, verify_downloads=False):
    _, artifacts, binaries = read_baseline(root)
    _, unpublished = read_availability(root)
    required = required_artifacts(binaries, sdk)
    validate_coverage(artifacts, binaries, required, unpublished)
    if verify_downloads:
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda identity: verify_download(identity, artifacts, unpublished), sorted(required)))
    print(f'IDE verification passed: {len(required)} source/SDK artifacts, '
          f'{len(unpublished)} exact unpublished-source records; download verification={verify_downloads}.')


def inventory(root):
    wrapper = [str(root / 'gradlew.bat')] if os.name == 'nt' else ['bash', str(root / 'gradlew')]
    subprocess.run([*wrapper, '--no-configuration-cache', '--dependency-verification=strict', '--console=plain',
                    '-I', str(root / 'scripts/ide_dependency_inventory.gradle'), 'ideDependencyInventory'],
                   cwd=root, check=True)
    sdk = json.loads((root / 'build/ide-dependency-verification/sdk.json').read_text(encoding='utf-8'))
    properties = (root / 'gradle/wrapper/gradle-wrapper.properties').read_text(encoding='utf-8')
    if not re.search(r'gradle-' + re.escape(sdk['gradleVersion']) + r'-(?:bin|all)\.zip', properties):
        raise ValueError('SDK inventory does not match the checked-in Wrapper')
    return sdk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('check', 'update'))
    parser.add_argument('--verify-downloads', action='store_true', help='Check official bytes against reviewed SHA-256 values')
    options = parser.parse_args()
    if options.command == 'update' and options.verify_downloads:
        parser.error('--verify-downloads is a read-only check option')
    # Reject CI updates before even invoking the build.
    if options.command == 'update' and any(os.environ.get(k, '').lower() not in ('', '0', 'false')
            for k in ('CI', 'GITHUB_ACTIONS', 'GITLAB_CI', 'TF_BUILD', 'JENKINS_URL', 'BUILD_BUILDID')):
        parser.error('CI cannot update reviewed checksums')
    read_baseline(ROOT)  # Reject permissive/malformed policy before the build loads any plugins.
    sdk = inventory(ROOT)
    if options.command == 'check':
        check(ROOT, sdk, options.verify_downloads)
    else:
        update(ROOT, sdk)


if __name__ == '__main__':
    main()
