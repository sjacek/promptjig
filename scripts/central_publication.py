"""Prepare two Gradle library publications and upload once to the Central Publisher API.

No extra Maven/Gradle plugin is needed. Local verification and bundling are offline;
only explicit ci/publish commands use the network. An uncertain upload is never retried.
Recovery state contains release identity and deployment status, never server bodies or secrets.
"""
import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import sys
import time
import tomllib
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
GROUP = 'io.github.sjacek.promptjig'
LIBRARIES = {'core': 'promptjig-core', 'spring-ai': 'promptjig-spring-ai'}
OUTPUT = ROOT / 'build/central-publication'
API = 'https://central.sonatype.com/api/v1/publisher'
REPO = 'sjacek/promptjig'
STATES = {'PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED', 'FAILED'}
TRANSIENT = {429, 500, 502, 503, 504}
HASHES = ('md5', 'sha1', 'sha256', 'sha512')
POM_NS = {'p': 'http://maven.apache.org/POM/4.0.0'}
MAX_RESPONSE = 1024 * 1024
MAX_BUNDLE = 64 * 1024 * 1024


class PublicationError(RuntimeError):
    """Messages safe for CI logs: exclude credentials and untrusted HTTP response bodies."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def transport():
    # Credentials belong only to the fixed HTTPS endpoints, never redirects or ambient proxies.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def version(root=ROOT):
    value = tomllib.loads((root / 'gradle/libs.versions.toml').read_text(encoding='utf-8'))['versions']['promptjig']
    if not isinstance(value, str) or not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', value):
        raise PublicationError('Expected a stable release version in libs.versions.toml')
    return value


def identity(environ, root=ROOT):
    value = version(root)
    commit = environ.get('RELEASE_COMMIT', '')
    if (environ.get('GITHUB_REPOSITORY') != REPO or environ.get('RELEASE_TAG') != f'v{value}'
            or not re.fullmatch(r'[0-9a-f]{40}', commit)):
        raise PublicationError('Expected the verified Promptjig release tag and commit')
    return {'version': value, 'tag': f'v{value}', 'commit': commit}


def validate_pom(payload, artifact, value):
    pom = ET.fromstring(payload)
    for field, expected in (('groupId', GROUP), ('artifactId', artifact), ('version', value)):
        if pom.findtext('p:' + field, namespaces=POM_NS) != expected:
            raise PublicationError('Unexpected POM coordinates')
    for field in ('name', 'description', 'url', 'licenses/license/name', 'licenses/license/url',
                  'developers/developer/name', 'developers/developer/url', 'scm/url', 'scm/connection'):
        if not (pom.findtext('/'.join('p:' + part for part in field.split('/')), namespaces=POM_NS) or '').strip():
            raise PublicationError('Missing required POM metadata: ' + field)
    if artifact == 'promptjig-spring-ai':
        dependencies = pom.findall('p:dependencies/p:dependency', POM_NS)
        if not any(d.findtext('p:groupId', namespaces=POM_NS) == GROUP
                   and d.findtext('p:artifactId', namespaces=POM_NS) == 'promptjig-core'
                   and d.findtext('p:version', namespaces=POM_NS) == value for d in dependencies):
            raise PublicationError('Runtime POM must depend on the same published core version')


def publication_files(root=ROOT, signed=True):
    """Read an exact file allowlist; never include caches, repository metadata or signing keys.

    Gradle generates repository-level maven-metadata.xml and checksum sidecars. Those are
    deliberately not copied: Central owns repository metadata and we compute bundle checksums.
    Missing sources/Javadocs, a foreign coordinate, symlinks or unsigned payloads fail closed.
    """
    value, files = version(root), {}
    for module, artifact in LIBRARIES.items():
        relative = Path(*GROUP.split('.')) / artifact / value
        repository = root / module / 'build' / ('central-staging' if signed else 'staging-repository')
        folder = repository / relative
        names = [f'{artifact}-{value}{suffix}' for suffix in
                 ('.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module')]
        expected = set(names + ([name + '.asc' for name in names] if signed else []))
        if not folder.is_dir() or any(p.is_symlink() for p in (repository, *folder.parents, folder)):
            raise PublicationError('Missing publication directory or symlink in staging path')
        for item in folder.iterdir():
            if item.is_symlink() or not item.is_file() or (item.name not in expected
                    and not any(item.name == name + '.' + algorithm for name in expected for algorithm in HASHES)):
                raise PublicationError('Unexpected staging content')
        for name in sorted(expected):
            path = folder / name
            if not path.is_file() or path.stat().st_size == 0:
                raise PublicationError('Missing publication payload or signature: ' + name)
            payload = path.read_bytes()
            if name.endswith('.pom'):
                validate_pom(payload, artifact, value)
            elif name.endswith('.module'):
                component = json.loads(payload)['component']
                if any(component.get(k) != v for k, v in {'group': GROUP, 'module': artifact, 'version': value}.items()):
                    raise PublicationError('Unexpected Gradle module coordinates')
            elif name.endswith('.jar'):
                with zipfile.ZipFile(path) as jar:
                    if jar.testzip() or 'META-INF/LICENSE' not in jar.namelist():
                        raise PublicationError('Invalid JAR or missing MIT license')
                    if name.endswith('-javadoc.jar') and 'index.html' not in jar.namelist():
                        raise PublicationError('Javadoc JAR has no index')
                    if name.endswith('-sources.jar') and not any(n.endswith('.java') for n in jar.namelist()):
                        raise PublicationError('Sources JAR has no Java sources')
            elif not (payload.startswith(b'-----BEGIN PGP SIGNATURE-----')
                      and payload.rstrip().endswith(b'-----END PGP SIGNATURE-----')):
                raise PublicationError('Expected an ASCII-armored PGP signature')
            files[(relative / name).as_posix()] = payload
    return files


def bundle(root=ROOT, output=OUTPUT):
    """Create a bounded, deterministic ZIP of signed payloads and freshly computed checksums."""
    files = publication_files(root, signed=True)
    for name, payload in list(files.items()):
        if not name.endswith('.asc'):
            for algorithm in HASHES:
                # MD5/SHA-1 are Central protocol sidecars, never used for dependency trust.
                files[name + '.' + algorithm] = hashlib.new(algorithm, payload, usedforsecurity=False).hexdigest().encode()
    if sum(map(len, files.values())) > MAX_BUNDLE:
        raise PublicationError('Release bundle exceeds the size limit')
    output.mkdir(parents=True, exist_ok=True)
    target, temporary = output / 'bundle.zip', output / 'bundle.tmp'
    with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, payload in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, payload)
    temporary.replace(target)
    print(f'Prepared signed library bundle: {len(files)} files', flush=True)
    return target


def unique_fields(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise PublicationError('Duplicate response field')
        result[name] = value
    return result


def response_bytes(request, opener, timeout=30, limit=MAX_RESPONSE):
    with opener.open(request, timeout=timeout) as response:
        payload = response.read(limit + 1)
    if len(payload) > limit:
        raise PublicationError('Oversized publication response')
    return payload


def require_ci(state, token, opener=None):
    """Only the latest Compatibility run for the exact tagged SHA can authorize a release.

    Check actual jobs as well as the run conclusion: a green run with skipped matrix
    jobs must not authorize publication. The GitHub token never reaches Central.
    """
    opener = opener or transport()
    url = f'https://api.github.com/repos/{REPO}/actions/workflows/compatibility.yml/runs?head_sha={state["commit"]}&per_page=1'
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json', 'User-Agent': 'Promptjig-release'}
    runs = json.loads(response_bytes(urllib.request.Request(url, headers=headers), opener), object_pairs_hook=unique_fields)
    candidates = runs.get('workflow_runs', []) if isinstance(runs, dict) else []
    if not isinstance(candidates, list) or not candidates or not isinstance(candidates[0], dict):
        raise PublicationError('No Compatibility run for the release commit')
    run = candidates[0]
    if (run.get('head_sha') != state['commit'] or run.get('status') != 'completed'
            or run.get('conclusion') != 'success' or run.get('path') != '.github/workflows/compatibility.yml'
            or not isinstance(run.get('id'), int)):
        raise PublicationError('Latest Compatibility run for the release commit is not successful')
    jobs_url = f'https://api.github.com/repos/{REPO}/actions/runs/{run["id"]}/jobs?per_page=100'
    result = json.loads(response_bytes(urllib.request.Request(jobs_url, headers=headers), opener), object_pairs_hook=unique_fields)
    jobs = result.get('jobs', []) if isinstance(result, dict) else None
    if not isinstance(jobs, list) or any(not isinstance(j, dict) for j in jobs):
        raise PublicationError('Invalid Compatibility job response')
    required = {'Prepare verified dependencies', 'core-and-packaging', 'runtime-java17', 'runtime-pilot'}
    required |= {f'Gradle {gradle} / JDK {java}' for gradle in ('9.0.0', '9.1.0', '9.9.1')
                 for java in ('17', '21', '25') if (gradle, java) != ('9.0.0', '25')}
    if (result.get('total_count') != len(jobs) or {j.get('name') for j in jobs} != required
            or len(jobs) != len(required) or any(j.get('status') != 'completed' or j.get('conclusion') != 'success' for j in jobs)):
        raise PublicationError('Every required Compatibility job must execute successfully')
    print(f'Confirmed Compatibility run {run["id"]}: all {len(required)} required jobs passed.', flush=True)


def deployment_id(value):
    try:
        canonical = str(uuid.UUID(value))
    except (ValueError, AttributeError):
        raise PublicationError('Invalid deployment ID') from None
    if canonical != value:
        raise PublicationError('Deployment ID must be a canonical UUID')
    return canonical


def save(state, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def credentials(environ):
    names = ('MAVEN_CENTRAL_USERNAME', 'MAVEN_CENTRAL_PASSWORD')
    if any(not environ.get(name) for name in names):
        raise PublicationError('Missing Maven Central user token credentials')
    return base64.b64encode((environ[names[0]] + ':' + environ[names[1]]).encode()).decode()


def recovery_input(environ):
    """Reject accidental reruns before signing as well as before any authenticated upload."""
    identifier, attempt = environ.get('MAVEN_DEPLOYMENT_ID', '').strip(), environ.get('GITHUB_RUN_ATTEMPT', '1')
    if not re.fullmatch(r'[1-9]\d*', attempt) or (int(attempt) > 1 and not identifier):
        raise PublicationError('A rerun requires an accepted deployment ID; do not repeat the upload')
    return deployment_id(identifier) if identifier else ''


def upload_once(state, token, path, persist, opener):
    """One upload attempt, including on HTTP/transport failure; checkpoint the returned ID immediately."""
    payload = path.read_bytes()
    if len(payload) > MAX_BUNDLE:
        raise PublicationError('Release bundle exceeds the size limit')
    boundary = 'promptjig-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="bundle.zip"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode() + payload + f'\r\n--{boundary}--\r\n'.encode()
    query = urllib.parse.urlencode({'name': f'Promptjig {state["version"]}', 'publishingType': 'AUTOMATIC'})
    request = urllib.request.Request(API + '/upload?' + query, data=body, method='POST', headers={
        'Authorization': 'Bearer ' + token, 'Content-Type': 'multipart/form-data; boundary=' + boundary})
    state.update(status='UPLOAD_STARTING', bundleSha256=hashlib.sha256(payload).hexdigest())
    persist(state)
    try:
        identifier = response_bytes(request, opener, timeout=120, limit=128).decode().strip()
        identifier = deployment_id(identifier)
    except (OSError, urllib.error.URLError, http.client.HTTPException, PublicationError, UnicodeError):
        state['status'] = 'UPLOAD_UNCERTAIN'
        persist(state)
        raise PublicationError('Upload outcome is uncertain; inspect Central before any new upload') from None
    state.update(deploymentId=identifier, status='UPLOADED')
    persist(state)
    print('Central deployment ID: ' + state['deploymentId'], flush=True)


def read_status(identifier, token, timeout, opener):
    request = urllib.request.Request(API + '/status?id=' + deployment_id(identifier), data=b'', method='POST',
                                     headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/json'})
    result = json.loads(response_bytes(request, opener, timeout), object_pairs_hook=unique_fields)
    if (not isinstance(result, dict) or result.get('deploymentId') != identifier
            or result.get('deploymentState') not in STATES):
        raise PublicationError('Unexpected Central deployment identity or state')
    return result


def verify_release(result, value):
    if result.get('deploymentName') != f'Promptjig {value}':
        raise PublicationError('Central deployment belongs to another release')
    if result['deploymentState'] == 'PUBLISHED':
        expected = {f'pkg:maven/{GROUP}/{artifact}@{value}' for artifact in LIBRARIES.values()}
        purls = result.get('purls')
        if (not isinstance(purls, list) or any(not isinstance(p, str) for p in purls)
                or {urllib.parse.unquote(p.split('?', 1)[0]) for p in purls} != expected):
            raise PublicationError('Published deployment does not contain exactly both runtime libraries')


def poll(state, token, persist, timeout=1800, clock=time.monotonic, sleep=time.sleep, opener=None):
    """Retry status reads only, under one monotonic deadline; VALIDATED is not publication success."""
    opener, deadline, failures = opener or transport(), clock() + timeout, 0
    while True:
        remaining = deadline - clock()
        if remaining <= 0:
            state['status'] = 'WAIT_TIMEOUT'
            persist(state)
            raise PublicationError('Central wait timed out; resume the saved deployment ID without uploading again')
        delay = 10
        try:
            result = read_status(state['deploymentId'], token, min(30, remaining), opener)
        except urllib.error.HTTPError as error:
            error.close()
            if error.code not in TRANSIENT:
                raise PublicationError(f'Central status HTTP {error.code}; deployment ID retained') from None
            failures += 1
            delay = min(60, 5 * 2 ** min(failures - 1, 4))
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException):
            failures += 1
            delay = min(60, 5 * 2 ** min(failures - 1, 4))
        else:
            verify_release(result, state['version'])
            failures = 0
            state['status'] = result['deploymentState']
            persist(state)
            print('Central deployment ' + state['deploymentId'] + ': ' + state['status'], flush=True)
            if state['status'] == 'PUBLISHED':
                return
            if state['status'] == 'FAILED':
                raise PublicationError('Central validation failed; inspect errors in the Portal')
        sleep(min(delay, max(0, deadline - clock())))


def publish(environ, root=ROOT, output=OUTPUT, opener=None):
    state, path = identity(environ, root), output / 'state.json'
    identifier = recovery_input(environ)
    if not identifier and path.exists():
        raise PublicationError('Publication state exists; inspect Central and resume its deployment ID')
    token, opener = credentials(environ), opener or transport()
    persist = lambda current: save(current, path)
    if identifier:
        # Recovery never reads signing keys or rebuilds/uploads a bundle, even on a new runner.
        state.update(deploymentId=deployment_id(identifier), status='RESUMING')
        persist(state)
    else:
        upload_once(state, token, bundle(root, output), persist, opener)
    poll(state, token, persist, opener=opener)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('verify-staging', 'bundle', 'ci', 'publish'))
    args = parser.parse_args(argv)
    if args.command == 'verify-staging':
        files = publication_files(signed=False)
        print(f'Verified both unsigned local library publications: {len(files)} payloads; no upload.')
    elif args.command == 'bundle':
        bundle()
    elif args.command == 'ci':
        recovery_input(os.environ)
        token = os.environ.get('GITHUB_TOKEN', '')
        if not token:
            raise PublicationError('Missing read-only GitHub Actions token')
        require_ci(identity(os.environ), token)
    else:
        publish(os.environ)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (PublicationError, OSError, ValueError, KeyError, TypeError, ET.ParseError,
            zipfile.BadZipFile, urllib.error.URLError, http.client.HTTPException) as error:
        message = str(error) if isinstance(error, PublicationError) else type(error).__name__
        print('Central publication failed: ' + message, file=sys.stderr)
        sys.exit(1)
