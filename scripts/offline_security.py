"""Run bounded candidate scenarios in a credential-free, read-only, network-none container."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def network_attempts(trace):
    """Keep non-loopback IP/DNS attempts, including failed connections, and reject unknown IP syntax."""
    attempts = []
    for line in trace.splitlines():
        if not re.search(r'\b(connect|sendto|sendmsg|sendmmsg)\(', line):
            continue
        addresses = re.findall(r'inet_addr\("([^"]+)"\)|inet_pton\(AF_INET6, "([^"]+)"', line)
        if 'sa_family=AF_INET' in line and not addresses:
            raise ValueError('Unparsed IP destination in raw trace')
        for ipv4, ipv6 in addresses:
            ip = ipaddress.ip_address(ipv4 or ipv6)
            address = ip.ipv4_mapped if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped else ip
            dns = bool(re.search(r'sin(?:6)?_port=htons\(53\)', line))
            if dns or not address.is_loopback:
                attempts.append({'address': str(address), 'dns': dns, 'syscall': line})
    return attempts


def execute(name, command, directory, output):
    log, trace = output / (name + '.log'), output / (name + '.strace')
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run(['strace', '-f', '-ttt', '-yy', '-s', '256', '-e', 'trace=network',
            '-o', str(trace), *command], cwd=directory, stdout=stream, stderr=subprocess.STDOUT, timeout=900)
    if not trace.is_file() or not trace.stat().st_size:
        raise ValueError('Missing raw network trace: ' + name)
    return {'name': name, 'exitCode': result.returncode, 'attempts': network_attempts(trace.read_text()),
            'traceSha256': hashlib.sha256(trace.read_bytes()).hexdigest()}


def validate_container(evidence):
    required = {'/inputs/source', '/inputs/ci-dependencies.tar', '/runner/offline_security.py', '/work', '/evidence'}
    destinations = [mount['destination'] for mount in evidence['mounts']]
    if (evidence['network'] != 'none' or not evidence['readOnlyRoot'] or evidence['user'] != '10001:10001'
            or evidence['capDrop'] != ['ALL'] or 'no-new-privileges' not in evidence['securityOptions']
            or set(destinations) - {'/tmp'} != required or len(destinations) != len(set(destinations))
            or any(m['writable'] != (m['destination'] in {'/work', '/evidence', '/tmp'}) for m in evidence['mounts'])):
        raise ValueError('Unexpected container isolation')
    allowed_environment = {'PATH', 'JAVA_HOME', 'JAVA_VERSION', 'LANG', 'LANGUAGE', 'LC_ALL', 'HOME',
        'GRADLE_USER_HOME', 'MAVEN_USER_HOME', 'SOURCE_COMMIT', 'PYTHONDONTWRITEBYTECODE'}
    names = set(evidence.get('environmentNames', []))
    if names - allowed_environment or not {'PATH', 'JAVA_HOME', 'HOME', 'SOURCE_COMMIT'} <= names:
        raise ValueError('Unexpected container environment; no host credentials/proxy configuration may be forwarded')


def work_volume(image, run):
    """Disposable Linux disk avoids Windows bind-mount overhead for resolver/compiler work."""
    name = 'promptjig-security-' + run
    created = subprocess.check_output(['docker', 'volume', 'create', '--label', 'io.github.sjacek.promptjig.audit=true', name], text=True).strip()
    if created != name:
        raise ValueError('Unexpected disposable volume identity')
    try:
        subprocess.run(['docker', 'run', '--rm', '--read-only', '--user', '0:0', '--network', 'none', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--mount', 'type=volume,source=' + name + ',target=/work,volume-nocopy',
            '--entrypoint', 'chmod', image, '0777', '/work'], check=True, timeout=120)
    except (OSError, subprocess.SubprocessError):
        subprocess.run(['docker', 'volume', 'rm', name], check=True, stdout=subprocess.DEVNULL)
        raise
    return name


def inside():
    """Dependency preparation is separate and unmeasured; all candidate execution below is observed."""
    work, source, output = Path('/work/repository'), Path('/inputs/source'), Path('/evidence')
    # Cache restoration stages the whole snapshot before promotion. Its temporary
    # files belong on the disposable disk mount, not the small /tmp memory mount.
    temporary = Path('/work/tmp')
    temporary.mkdir(parents=True, exist_ok=True)
    tempfile.tempdir = str(temporary)
    os.environ['TMPDIR'] = str(temporary)
    work.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, work, dirs_exist_ok=True)
    (work / 'build').mkdir(exist_ok=True)
    shutil.copyfile('/inputs/ci-dependencies.tar', work / 'build/ci-dependencies.tar')
    sys.path.insert(0, str(work / 'scripts'))
    import ci_dependency_cache as cache
    cache.restore(required=True)
    cache.prepare()
    cache.distributions()
    os.environ.update(GRADLE_USER_HOME=str(work / 'build/ci-dependencies/gradle'),
        PROMPTJIG_TESTKIT_DIR=str(work / 'build/ci-dependencies/testkit'),
        JAVA_HOME=os.environ['JAVA_HOME'], JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2',
        PYTHONDONTWRITEBYTECODE='1')
    tools = {}
    for name, command in [('java', ['java', '-version']), ('python', ['python3', '--version']), ('strace', ['strace', '--version'])]:
        identity = subprocess.run(command, capture_output=True, text=True, check=True)
        tools[name] = identity.stdout + identity.stderr
    (output / 'tools.json').write_text(json.dumps(tools, indent=2) + '\n')
    wrapper = ['bash', str(work / 'gradlew')]
    flags = ['--offline', '--no-daemon', '--no-configuration-cache', '--no-build-cache',
             '--dependency-verification=strict', '--console=plain', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx768m']
    # A reserved documentation address cannot reach a real service. Its blocked
    # connect must still appear in strace, proving the observer isn't a no-op.
    probe = work / 'network-probe.py'
    probe.write_text("import socket\ntry:\n socket.create_connection(('198.51.100.1', 443), timeout=1)\n"
        + "except OSError:\n print('connection blocked')\nelse:\n raise SystemExit('unexpected connection')\n")
    control = execute('positive-control', ['python3', str(probe)], work, output)
    if (control['exitCode'] != 0 or not any(item['address'] == '198.51.100.1' for item in control['attempts'])
            or 'connection blocked' not in (output / 'positive-control.log').read_text()):
        raise ValueError('Network observer positive control failed')
    locks = {name: json.loads((work / 'examples' / name / 'prompt-contracts.lock.json').read_text())
             for name in ('gradle', 'runtime')}
    cases = [
        ('libraries', [*wrapper, ':core:test', ':spring-ai:test', ':maven-plugin:test', *flags], work),
        ('generation', [*wrapper, '-p', 'examples/generation', 'check', '-PpromptjigPluginBuild=' + str(work), *flags], work),
        ('lock-baseline', [*wrapper, '-p', 'examples/gradle', 'promptContractsLock', 'check', '-PpromptjigPluginBuild=' + str(work),
            '-Ppromptjig.baselineLockFile=' + str(work / 'examples/gradle/prompt-contracts.lock.json'), *flags], work),
        ('runtime', [*wrapper, '-p', 'examples/runtime', 'check', 'installDist', '-PpromptjigBuildDir=' + str(work),
            '-Ppromptjig.baselineLockFile=' + str(work / 'examples/runtime/prompt-contracts.lock.json'), *flags], work),
        ('synthetic-demo', ['bash', str(work / 'examples/runtime/build/install/promptjig-runtime-pilot/bin/promptjig-runtime-pilot')], work)]
    results, errors = [], []
    for name, command, directory in cases:
        try:
            result = execute(name, command, directory, output)
            results.append(result)
            if result['exitCode'] or result['attempts']:
                errors.append(name)
        except (OSError, ValueError, subprocess.SubprocessError) as failure:
            errors.append(name + ': ' + str(failure))
    test_counts = {}
    for module in ('core', 'spring-ai', 'maven-plugin'):
        files = list((work / module / 'build/test-results/test').glob('TEST-*.xml'))
        count = sum(int(ET.parse(file).getroot().get('tests', '0')) for file in files)
        test_counts[module] = count
        if count <= 0:
            errors.append('Missing native tests: ' + module)
    for name, original in locks.items():
        if json.loads((work / 'examples' / name / 'prompt-contracts.lock.json').read_text()) != original:
            errors.append('Historical lock changed: ' + name)
    if not any((work / 'examples/generation/build/classes/java/main').rglob('*.class')):
        errors.append('Missing compiled generated consumer classes')
    summary = {'schemaVersion': 1, 'sourceCommit': os.environ['SOURCE_COMMIT'],
        'passed': not errors and len(results) == len(cases), 'positiveControl': control, 'cases': results,
        'nativeTestCounts': test_counts, 'historicalLocksUnchanged': not any('Historical lock' in error for error in errors),
        'tools': tools,
        'errors': errors, 'scope': 'candidate libraries and authored synthetic consumers; loopback build transport allowed; no model calls'}
    for path in work.rglob('TEST-*.xml'):
        if 'test-results' in path.parts:
            target = output / 'test-results' / path.relative_to(work)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    if not summary['passed']:
        raise ValueError('Offline acceptance failed; inspect retained logs and raw traces')


def launch(image):
    base = ROOT / 'build/security/offline'
    base.mkdir(parents=True, exist_ok=True)
    (base / 'summary.json').unlink(missing_ok=True)
    run = uuid.uuid4().hex
    output = base / run
    output.mkdir()
    latest = {'run': run, 'status': 'running', 'evidence': (output / 'evidence').relative_to(ROOT).as_posix()}
    (base / 'latest.json').write_text(json.dumps(latest, indent=2) + '\n')
    source = output / 'source'
    # Only repository code is exposed, never a developer home, Docker socket or credentials.
    files = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).split(b'\0')
    allowed_roots = {'core', 'spring-ai', 'gradle-plugin', 'maven-plugin', 'gradle', 'scripts', 'security', 'examples', '.mvn', '.github', 'testing'}
    allowed_files = {'.gitattributes', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'LICENSE'}
    manifest = {}
    for raw in files:
        if not raw:
            continue
        relative = Path(os.fsdecode(raw))
        if relative.parts[0] not in allowed_roots and relative.as_posix() not in allowed_files:
            continue
        if any(part in {'build', 'target', '.gradle', '__pycache__'} for part in relative.parts):
            continue
        if relative.name in {'.env', 'local.properties', 'settings-security.xml', 'gradle-publish.properties'}:
            continue
        path = ROOT / relative
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT):
            raise ValueError('Unsafe source mount input')
        target = source / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        manifest[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    # Remove only stale files under this audit's verified absolute source directory.
    for path in source.rglob('*'):
        if path.is_file() and path.relative_to(source).as_posix() not in manifest:
            path.unlink()
    (output / 'source-inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
    for name in ('evidence',):
        (output / name).mkdir(exist_ok=True)
        (output / name).chmod(0o777)
    (output / 'evidence/summary.json').unlink(missing_ok=True)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    command = ['docker', 'create', '--read-only', '--user', '10001:10001', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--network', 'none', '--cpus', '2', '--memory', '4g', '--pids-limit', '768',
        '--tmpfs', '/tmp:rw,exec,mode=1777,size=256m', '--env', 'SOURCE_COMMIT=' + commit,
        '--env', 'PYTHONDONTWRITEBYTECODE=1']
    volume = work_volume(image, run)
    command += ['--mount', 'type=volume,source=' + volume + ',target=/work,volume-nocopy']
    mounts = [(source, '/inputs/source', True), (ROOT / 'build/ci-dependencies.tar', '/inputs/ci-dependencies.tar', True),
        (source / 'scripts/offline_security.py', '/runner/offline_security.py', True),
        (output / 'evidence', '/evidence', False)]
    for path, destination, readonly in mounts:
        command += ['--mount', 'type=bind,source=' + str(path) + ',target=' + destination + (',readonly' if readonly else '')]
    command += [image, 'python3', '-B', '/runner/offline_security.py', '--inside']
    try:
        identifier = subprocess.check_output(command, text=True).strip()
    except (OSError, subprocess.SubprocessError):
        subprocess.run(['docker', 'volume', 'rm', volume], check=True, stdout=subprocess.DEVNULL)
        raise
    if not re.fullmatch(r'[a-f0-9]{64}', identifier):
        raise ValueError('Invalid container ID')
    try:
        inspected = json.loads(subprocess.check_output(['docker', 'inspect', identifier], text=True))[0]
        host = inspected['HostConfig']
        evidence = {'imageId': inspected['Image'], 'network': host['NetworkMode'], 'readOnlyRoot': host['ReadonlyRootfs'],
            'user': inspected['Config']['User'], 'capDrop': host['CapDrop'], 'securityOptions': host['SecurityOpt'],
            'environmentNames': sorted(value.partition('=')[0] for value in inspected['Config']['Env']),
            'mounts': [{'destination': m['Destination'], 'writable': m['RW'], 'type': m['Type']} for m in inspected['Mounts']]}
        (output / 'container-policy.json').write_text(json.dumps(evidence, indent=2) + '\n')
        validate_container(evidence)
        with (output / 'container.log').open('w', encoding='utf-8') as log:
            subprocess.run(['docker', 'start', '--attach', identifier], check=True, stdout=log, stderr=subprocess.STDOUT, timeout=2400)
        state = json.loads(subprocess.check_output(['docker', 'inspect', identifier], text=True))[0]['State']
        if state['ExitCode'] or state.get('OOMKilled'):
            raise ValueError('Container failed or ran out of memory')
        summary = json.loads((output / 'evidence/summary.json').read_text())
        if summary.get('passed') is not True or summary.get('sourceCommit') != commit:
            raise ValueError('Missing or inconsistent offline acceptance')
        summary.update(evidenceDirectory=latest['evidence'], imageId=evidence['imageId'],
            sourceInputsSha256=hashlib.sha256((output / 'source-inputs.json').read_bytes()).hexdigest())
        (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        if json.loads((base / 'latest.json').read_text())['run'] == run:
            (base / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        latest['status'] = 'passed'
    except (OSError, ValueError, subprocess.SubprocessError):
        latest['status'] = 'failed'
        raise
    finally:
        if json.loads((base / 'latest.json').read_text())['run'] == run:
            (base / 'latest.json').write_text(json.dumps(latest, indent=2) + '\n')
        try:
            subprocess.run(['docker', 'rm', '-f', identifier], check=True, stdout=subprocess.DEVNULL)
        finally:
            subprocess.run(['docker', 'volume', 'rm', volume], check=True, stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inside', action='store_true')
    parser.add_argument('--image', default='promptjig-security-audit:local')
    args = parser.parse_args()
    inside() if args.inside else launch(args.image)
