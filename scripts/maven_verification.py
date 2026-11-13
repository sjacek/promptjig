"""Pinned Maven bootstrap and pre-execution checksum enforcement, including restored local artifacts.

There is intentionally no collect/update mode. Maintainers review new external
hashes separately; ordinary builds and CI never modify the trusted baseline.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import urllib.request
import zipfile

from ci_dependency_cache import contained, safe_name

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / '.mvn/checksums.sha256'
CACHE = ROOT / 'build/ci-dependencies'
NS = {'p': 'http://maven.apache.org/POM/4.0.0'}


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def load_checksums(path=None):
    """Validate identities before loading an external extension or Maven plugin."""
    path = BASELINE if path is None else path
    values = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#'):
            continue
        parts = line.split(' ')
        if len(parts) != 2 or not re.fullmatch(r'[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:(?:jar|pom)(?::[A-Za-z0-9_.-]+)?:[A-Za-z0-9_.-]+', parts[0]) \
                or not re.fullmatch(r'[a-f0-9]{64}', parts[1]) or parts[0] in values:
            raise ValueError('Invalid or duplicate Maven verification checksum')
        values[parts[0]] = parts[1]
    if not values:
        raise ValueError('Empty Maven verification baseline')
    return values


def artifact_identity(relative):
    """Accept only Maven repository payloads, not resolver state, settings or credentials."""
    parts = relative.split('/')
    if len(parts) < 4 or any(not re.fullmatch(r'[A-Za-z0-9_.-]+', part) or part in ('.', '..') for part in parts):
        return None
    artifact, version, filename = parts[-3:]
    match = re.fullmatch(re.escape(artifact + '-' + version) + r'(?:-([A-Za-z0-9_.-]+))?\.(jar|pom)', filename)
    if match is None:
        return None
    classifier, kind = match.groups()
    return ':'.join(['.'.join(parts[:-3]), artifact, kind, *([classifier] if classifier else []), version])


def verifier_policy(root=None):
    policy = json.loads(((ROOT if root is None else root) / 'security/dependency-verification.json').read_text(encoding='utf-8'))
    value = policy['mavenExtension']
    expected = ('https://repo.maven.apache.org/maven2/codes/rafael/mavenchecksumextension/'
                f'maven-checksum-extension/{value["version"]}/maven-checksum-extension-{value["version"]}.jar')
    if policy['schemaVersion'] != 1 or not re.fullmatch(r'\d+\.\d+\.\d+', value['version']) \
            or not re.fullmatch(r'[a-f0-9]{64}', value['sha256']) or value['url'] != expected:
        raise ValueError('Invalid Maven verifier policy')
    return value


def extension(download=False):
    """Check the extension on every use, before it can join Maven's executable class realm."""
    value = verifier_policy()
    target = CACHE / 'maven/verifier' / f'maven-checksum-extension-{value["version"]}.jar'
    contained(ROOT, target)
    if target.is_symlink():
        raise ValueError('Maven verifier must not be a symlink')
    if not target.exists() and download:
        target.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(value['url'], timeout=60) as response:
            payload = response.read(2_000_001)
        if len(payload) > 2_000_000 or hashlib.sha256(payload).hexdigest() != value['sha256']:
            raise ValueError('Maven verifier download checksum mismatch')
        target.write_bytes(payload)
    if not target.is_file() or digest(target) != value['sha256']:
        raise ValueError('Missing or corrupted Maven verifier')
    return target.resolve()


def distribution(version, download=False):
    """Restore only the pinned archive; executable trees are always freshly extracted outside cache."""
    policy = json.loads((ROOT / 'gradle/ci-dependencies.json').read_text(encoding='utf-8'))
    expected = policy.get('mavenDistributions', {}).get(version)
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-rc-\d+)?', version) or not re.fullmatch(r'[a-f0-9]{64}', expected or ''):
        raise ValueError('Maven distribution must have an exact reviewed version and SHA-256')
    archive = CACHE / 'distributions/maven' / f'apache-maven-{version}-bin.zip'
    contained(ROOT, archive)
    if archive.is_symlink():
        raise ValueError('Maven archive must not be a symlink')
    if not archive.exists() and download:
        archive.parent.mkdir(parents=True, exist_ok=True)
        url = f'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/{version}/{archive.name}'
        temporary = archive.with_suffix('.part')
        try:
            with urllib.request.urlopen(url, timeout=60) as source, temporary.open('wb') as target:
                shutil.copyfileobj(source, target)
            if digest(temporary) != expected:
                raise ValueError('Maven archive download checksum mismatch')
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    if not archive.is_file() or digest(archive) != expected:
        raise ValueError('Missing or corrupted pinned Maven archive')
    directory = ROOT / 'build/ci-tools/maven' / f'apache-maven-{version}'
    contained(ROOT, directory)
    tools = (ROOT / 'build/ci-tools/maven').resolve()
    if directory.is_symlink() or not directory.resolve().is_relative_to(tools):
        raise ValueError('Maven tool destination escapes its workspace')
    if directory.exists():
        shutil.rmtree(directory)
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            name = entry.filename.rstrip('/')
            parts = name.split('/')
            if not safe_name(name) or parts[0] != directory.name or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError('Unsafe Maven archive entry')
        source.extractall(directory.parent)
        for entry in source.infolist():
            target = directory.parent / entry.filename
            if target.is_file():
                target.chmod(0o755 if entry.external_attr >> 16 & 0o111 else 0o644)
    return directory


def quoted(path):
    value = str(path)
    if any(char in value for char in ('"', '\r', '\n')):
        raise ValueError('Unsafe path in Maven verifier options')
    if not any(char.isspace() for char in value):
        return value
    if os.name == 'nt':
        return '"' + value + '"'
    raise ValueError('POSIX Maven verification requires paths without whitespace')


def environment(checksums=None, java_home=None, original=None):
    """Enforce known downloaded and local bytes before Maven executes build code, with controlled verifier options."""
    checksums = BASELINE if checksums is None else checksums
    load_checksums(checksums)
    verifier = extension()
    env = dict(os.environ if original is None else original)
    previous = env.get('MAVEN_OPTS', '')
    if 'maven.ext.class.path' in previous or 'codes.rafael.mavenchecksumextension.' in previous:
        raise ValueError('Conflicting Maven verifier JVM options')
    env.pop('MAVEN_ARGS', None)
    env.pop('MAVEN_CONFIG', None)
    env['MAVEN_OPTS'] = (previous + ' -Dmaven.ext.class.path=' + quoted(verifier)
        + ' -Dcodes.rafael.mavenchecksumextension.file=' + quoted(checksums.resolve())
        + ' -Dcodes.rafael.mavenchecksumextension.mode=enforce'
        + ' -Dcodes.rafael.mavenchecksumextension.algorithm=SHA-256'
        + ' -Dcodes.rafael.mavenchecksumextension.snapshots=true'
        + ' -Dcodes.rafael.mavenchecksumextension.locals=true'
        + ' -Dcodes.rafael.mavenchecksumextension.reactor=false').strip()
    if java_home:
        env['JAVA_HOME'] = str(java_home)
    return env


def run(home, arguments, repository, checksums=None, java_home=None, cwd=None, offline=False, **kwargs):
    """Use explicit clean settings and an isolated repository, including all Invoker child builds."""
    settings = ROOT / '.mvn/settings.xml'
    executable = home / 'bin' / ('mvn.cmd' if os.name == 'nt' else 'mvn')
    command = [str(executable), '--batch-mode', '--no-transfer-progress', '--show-version', '-s', str(settings), '-gs', str(settings),
               '-Dmaven.repo.local=' + str(repository), *(['--offline'] if offline else []), *arguments]
    return subprocess.run(command, cwd=ROOT if cwd is None else cwd, env=environment(checksums, java_home), check=True, **kwargs)
