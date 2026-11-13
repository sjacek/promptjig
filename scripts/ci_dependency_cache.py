"""Download-only CI snapshots. Shared input is untrusted until checksum verification succeeds."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / 'build/ci-dependencies'
ARCHIVE = ROOT / 'build/ci-dependencies.tar'
POLICY = ROOT / 'gradle/ci-dependencies.json'
METADATA = ROOT / 'gradle/verification-metadata.xml'
MODULES = ('gradle/caches/modules-2/', 'testkit/caches/modules-2/')
NAMESPACE = {'v': 'https://schema.gradle.org/dependency-verification'}
MAX_FILE = 512 * 1024 * 1024
MAX_TOTAL = 4 * 1024 * 1024 * 1024


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def policy():
    value = json.loads(POLICY.read_text(encoding='utf-8'))
    if value['schemaVersion'] != 1 or not value['gradleDistributions']:
        raise ValueError('Invalid CI dependency policy')
    for version, checksum in value['gradleDistributions'].items():
        if not re.fullmatch(r'\d+\.\d+\.\d+', version) or not re.fullmatch(r'[a-f0-9]{64}', checksum):
            raise ValueError('Distributions require an exact version and SHA-256')
    # Published consumers need a narrow exception to the project-output exclusion. A coordinate
    # permits a download path only; it never grants trust to the bytes or updates the checksum policy.
    for coordinate in value.get('publishedModules', []):
        if not re.fullmatch(r'io\.github\.sjacek\.promptjig:[A-Za-z0-9_.-]+:\d+\.\d+\.\d+', coordinate):
            raise ValueError('Published project dependencies require explicit release coordinates')
    # There is no Maven adapter yet. Introducing POMs must deliberately extend warmup and verification.
    poms = []
    for folder, directories, files in os.walk(ROOT):
        directories[:] = [name for name in directories if not name.startswith('.')
                          and name not in ('build', 'target') and not name.startswith('build-')]
        if 'pom.xml' in files:
            poms.append(Path(folder) / 'pom.xml')
    if value['mavenProjects'] or poms:
        raise ValueError('Maven projects require an explicit verified warmup implementation before CI can proceed')
    return value


def checksums():
    """Native Gradle and snapshot verification use exactly the same committed SHA-256 baseline."""
    root = ET.parse(METADATA).getroot()
    config = root.find('v:configuration', NAMESPACE)
    if config is None or config.findtext('v:verify-metadata', namespaces=NAMESPACE) != 'true' \
            or config.find('v:trusted-artifacts', NAMESPACE) is not None:
        raise ValueError('Strict metadata verification must not trust whole artifact groups')
    result = {}
    for component in root.findall('v:components/v:component', NAMESPACE):
        identity = tuple(component.get(key) for key in ('group', 'name', 'version'))
        for artifact in component.findall('v:artifact', NAMESPACE):
            values = {item.get('value') for item in artifact.findall('v:sha256', NAMESPACE)}
            if not values or any(not re.fullmatch(r'[a-f0-9]{64}', value or '') for value in values):
                raise ValueError('Every external artifact requires SHA-256')
            result[(*identity, artifact.get('name'))] = values
    if not result:
        raise ValueError('Empty dependency verification baseline')
    return result


def safe_name(name):
    parts = name.split('/')
    return bool(name) and not name.startswith('/') and all(parts) and all(
        part not in ('.', '..') and not part.endswith(('.', ' '))
        and not re.fullmatch(r'(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?', part)
        and not any(ord(char) < 32 or ord(char) == 127 or char in '\\:' for char in part)
        for part in parts)


def allowed(name, published=()):
    """Allow resolver downloads, never project output, credentials or executable user-home configuration."""
    if not safe_name(name) or name.endswith(('.lock', '.lck', '.part', '.tmp', '.lastUpdated')):
        return False
    if re.fullmatch(r'distributions/gradle/gradle-\d+\.\d+\.\d+-bin\.zip', name):
        return True
    for prefix in MODULES:
        if not name.startswith(prefix):
            continue
        parts = name[len(prefix):].split('/')
        if parts[0] == 'files-2.1' and len(parts) == 6:
            _, group, artifact, version, storage_hash, filename = parts
            # Gradle's compact checksum directory can omit leading zeroes; content trust comes from SHA-256 below.
            # Match group, artifact and release together, so allowing runtime 0.2.0 cannot admit
            # another local module/version or the independently versioned build plugin.
            reviewed_release = ':'.join((group, artifact, version)) in published
            return (not group.startswith('io.github.sjacek') or reviewed_release) and bool(re.fullmatch(r'[a-f0-9]{1,40}', storage_hash)) \
                and filename.startswith(artifact + '-' + version) and filename.endswith(('.jar', '.pom', '.module'))
        return bool(re.fullmatch(r'metadata-2\.\d+', parts[0])) and parts[-1] in {
            'module-artifact.bin', 'module-metadata.bin', 'module-versions.bin',
            'resource-at-url.bin', 'descriptor.bin', 'artifact-at-repository.bin'} \
            and all(re.fullmatch(r'[A-Za-z0-9_.-]+', part) for part in parts[1:])
    return False


def verify_file(name, path, baseline, distributions):
    """Recheck restored bytes before any Gradle/plugin code can execute."""
    if name.startswith('distributions/'):
        match = re.fullmatch(r'distributions/gradle/gradle-(.+)-bin.zip', name)
        expected = distributions.get(match[1]) if match else None
        if expected is None or digest(path) != expected:
            raise ValueError('Gradle distribution checksum mismatch: ' + name)
        return
    for prefix in MODULES:
        if name.startswith(prefix + 'files-2.1/'):
            _, group, artifact, version, _, filename = name[len(prefix):].split('/')
            if digest(path) not in baseline.get((group, artifact, version, filename), set()):
                raise ValueError('External artifact checksum missing or mismatched: ' + name)
            return


def contained(root, target):
    if root.is_symlink() or target.is_symlink() or not target.resolve().is_relative_to(root.resolve()):
        raise ValueError('Dependency cache destination escapes its root')
    for ancestor in target.parents:
        if ancestor == root.parent:
            break
        if ancestor.is_symlink():
            raise ValueError('Dependency cache destination contains a symlink')


def pack(cache=CACHE, archive=ARCHIVE):
    value = policy()
    baseline, distributions = checksums(), value['gradleDistributions']
    archive.parent.mkdir(parents=True, exist_ok=True)
    temporary = archive.with_suffix('.tmp')
    count = 0
    try:
        with tarfile.open(temporary, 'w', dereference=True) as output:
            for path in sorted(cache.rglob('*')):
                name = path.relative_to(cache).as_posix()
                if not path.is_file() or path.is_symlink() or not allowed(name, value.get('publishedModules', ())):
                    continue
                contained(cache, path)
                # Old compatible caches can contain unused versions: only reviewed external bytes are exported.
                # This applies to explicitly allowed public Promptjig downloads too.
                if '/files-2.1/' in name and artifact_identity(name) not in baseline:
                    continue
                verify_file(name, path, baseline, distributions)
                output.add(path, arcname=name, recursive=False)
                count += 1
        if not count:
            raise ValueError('Refusing to publish an empty dependency snapshot')
        temporary.replace(archive)
    finally:
        temporary.unlink(missing_ok=True)
    print(f'Packed {count} downloaded files', flush=True)


def restore(cache=CACHE, archive=ARCHIVE, required=False):
    if not archive.exists():
        if required:
            raise ValueError('Required same-run dependency snapshot is missing')
        print('No snapshot; warming a cold cache.', flush=True)
        return
    value = policy()
    baseline, distributions = checksums(), value['gradleDistributions']
    with tarfile.open(archive) as source:
        members = source.getmembers()
        if not members:
            raise ValueError('Empty dependency snapshot')
        names = set()
        total = 0
        for member in members:
            folded = member.name.casefold()
            if not member.isfile() or not allowed(member.name, value.get('publishedModules', ())) or folded in names or not 0 <= member.size <= MAX_FILE:
                raise ValueError('Unsafe dependency snapshot entry: ' + member.name)
            names.add(folded)
            total += member.size
            contained(cache, cache / member.name)
        if total > MAX_TOTAL:
            raise ValueError('Dependency snapshot exceeds size limit')
        # Stage and verify the whole archive before changing a live cache. Never extract through existing links.
        with tempfile.TemporaryDirectory(prefix='promptjig-cache-') as staging:
            stage = Path(staging)
            for member in members:
                target = stage / member.name
                target.parent.mkdir(parents=True, exist_ok=True)
                stream = source.extractfile(member)
                if stream is None:
                    raise ValueError('Missing archive data')
                with stream, target.open('wb') as output:
                    shutil.copyfileobj(stream, output)
                # Unused old artifacts are retained but may never bypass native strict Gradle verification.
                if '/files-2.1/' not in member.name or artifact_identity(member.name) in baseline:
                    verify_file(member.name, target, baseline, distributions)
            for member in members:
                target = cache / member.name
                target.parent.mkdir(parents=True, exist_ok=True)
                contained(cache, target)
                # Replace rather than truncate: an existing hardlink must not modify a file outside this cache.
                with tempfile.NamedTemporaryFile(dir=target.parent, prefix='restore-', suffix='.tmp', delete=False) as output:
                    temporary = Path(output.name)
                    with (stage / member.name).open('rb') as input:
                        shutil.copyfileobj(input, output)
                try:
                    temporary.replace(target)
                finally:
                    temporary.unlink(missing_ok=True)
    print(f'Restored {len(members)} downloaded files; project outputs were not restored.', flush=True)


def artifact_identity(name):
    for prefix in MODULES:
        if name.startswith(prefix + 'files-2.1/'):
            _, group, artifact, version, _, filename = name[len(prefix):].split('/')
            return group, artifact, version, filename
    return None


def prepare():
    """Copy the reviewed policy into isolated example builds; do not derive or append trusted checksums."""
    value = policy()
    checksums()
    for project in value['examples']:
        target = ROOT / project / 'gradle/verification-metadata.xml'
        contained(ROOT, target)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(METADATA, target)


def distributions(download=False):
    """Share verified ZIPs, not cached executable trees or Wrapper .ok markers."""
    values = policy()['gradleDistributions']
    directory = CACHE / 'distributions/gradle'
    contained(CACHE, directory)
    directory.mkdir(parents=True, exist_ok=True)
    for version, checksum in values.items():
        print('Checking pinned Gradle distribution ' + version, flush=True)
        archive = directory / f'gradle-{version}-bin.zip'
        contained(CACHE, archive)
        if not archive.exists() and download:
            url = f'https://services.gradle.org/distributions/gradle-{version}-bin.zip'
            temporary = archive.with_suffix('.part')
            try:
                with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as output:
                    shutil.copyfileobj(response, output)
                if digest(temporary) != checksum:
                    raise ValueError('Downloaded Gradle distribution checksum mismatch')
                temporary.replace(archive)
            finally:
                temporary.unlink(missing_ok=True)
        if not archive.is_file() or archive.is_symlink() or digest(archive) != checksum:
            raise ValueError('Missing or corrupted Gradle distribution: ' + version)
        installation = ROOT / 'build/ci-tools/gradle' / f'gradle-{version}'
        contained(ROOT, installation)
        if installation.exists():
            # A fresh extraction is required even if a previous tool tree is still present.
            shutil.rmtree(installation)
        with zipfile.ZipFile(archive) as source:
            for item in source.infolist():
                name = item.filename.rstrip('/')
                if not safe_name(name) or PurePosixPath(name).parts[0] != f'gradle-{version}' \
                        or stat.S_ISLNK(item.external_attr >> 16):
                    raise ValueError('Unsafe Gradle distribution entry')
            source.extractall(installation.parent)
            for item in source.infolist():
                target = installation.parent / item.filename
                if target.is_file():
                    target.chmod(0o755 if item.external_attr >> 16 & 0o111 else 0o644)
    # The project Wrapper still runs and checks its committed distributionSha256Sum.
    properties = (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text(encoding='utf-8')
    url = re.search(r'^distributionUrl=(.+)$', properties, re.MULTILINE)[1].replace('\\:', ':')
    version = re.search(r'gradle-(\d+\.\d+\.\d+)-bin.zip$', url)[1]
    checksum = re.search(r'^distributionSha256Sum=(.+)$', properties, re.MULTILINE)[1].strip()
    if values.get(version) != checksum:
        raise ValueError('Wrapper checksum and CI policy disagree')
    url_hash = base36(int.from_bytes(hashlib.md5(url.encode(), usedforsecurity=False).digest(), 'big'))
    target = CACHE / 'gradle/wrapper/dists' / f'gradle-{version}-bin' / url_hash / f'gradle-{version}-bin.zip'
    contained(CACHE, target)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(directory / target.name, target)


def base36(number):
    alphabet = '0123456789abcdefghijklmnopqrstuvwxyz'
    result = ''
    while number:
        number, remainder = divmod(number, 36)
        result = alphabet[remainder] + result
    return result or '0'


def warm():
    environment = dict(os.environ)
    if not environment.get('WARMUP_JAVA_HOME'):
        raise ValueError('Set WARMUP_JAVA_HOME to JDK 21 for all supported TestKit distributions')
    environment.update(GRADLE_USER_HOME=str(CACHE / 'gradle'),
                       PROMPTJIG_TESTKIT_DIR=str(CACHE / 'testkit'),
                       PROMPTJIG_GRADLE_DISTRIBUTIONS=str(ROOT / 'build/ci-tools/gradle'))
    prepare()
    distributions(download=True)
    wrapper = [str(ROOT / 'gradlew.bat')] if os.name == 'nt' else ['bash', str(ROOT / 'gradlew')]
    common = ['--no-configuration-cache', '--dependency-verification=strict', '--console=plain',
              '-I', str(ROOT / 'scripts/ci_dependency_warmup.gradle')]
    subprocess.run([*wrapper, 'ciResolveDependencies', *common], cwd=ROOT, check=True, env=environment)
    subprocess.run([*wrapper, ':gradle-plugin:warmTestKitCache', *common,
                    '-PtestGradleVersions=' + ','.join(policy()['gradleDistributions'])], cwd=ROOT, check=True, env=environment)
    for project in policy()['examples']:
        subprocess.run([*wrapper, '-p', project, 'ciResolveDependencies', *common,
                        '-PpromptjigPluginBuild=' + str(ROOT)], cwd=ROOT, check=True, env=environment)
    # Flush resolver state before exporting. These homes are owned by warmup, never the developer's ~/.gradle.
    subprocess.run([*wrapper, '--stop'], cwd=ROOT, check=True, env=environment)
    stop_environment = {**environment, 'JAVA_HOME': environment['WARMUP_JAVA_HOME']}
    for version in policy()['gradleDistributions']:
        launcher = ROOT / 'build/ci-tools/gradle' / f'gradle-{version}' / 'bin' / ('gradle.bat' if os.name == 'nt' else 'gradle')
        if launcher.is_file():
            # TestKit has a separate daemon registry; the default <home>/daemon registry is not sufficient.
            subprocess.run([str(launcher), '--stop', '--gradle-user-home', str(CACHE / 'testkit'),
                            '-Dorg.gradle.daemon.registry.base=' + str(CACHE / 'testkit/test-kit-daemon')],
                           cwd=ROOT, check=True, env=stop_environment)
    print('Maven/Invoker warmup: not applicable (no POMs or Maven adapter).', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('restore', 'pack', 'prepare', 'distributions', 'warm'))
    parser.add_argument('--required', action='store_true')
    options = parser.parse_args()
    if options.command == 'restore':
        restore(required=options.required)
    else:
        {'pack': pack, 'prepare': prepare, 'distributions': distributions, 'warm': warm}[options.command]()


if __name__ == '__main__':
    main()
