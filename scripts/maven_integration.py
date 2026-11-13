"""Download-only Maven preparation and real, isolated Invoker acceptance of the Gradle-built plugin."""
import argparse
import copy
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib
import xml.etree.ElementTree as ET

import maven_verification as verification

ROOT = Path(__file__).resolve().parent.parent
NS = {'p': 'http://maven.apache.org/POM/4.0.0'}
POM = '{http://maven.apache.org/POM/4.0.0}'
ET.register_namespace('', POM[1:-1])
# A verifier throws an Error on rejection. Maven 4's parallel collector can leave an
# unfinished future in that path; serial collection propagates it to the CLI immediately.
# Ordinary Invoker acceptance keeps the consumer's default collector.
SERIAL_COLLECTOR = '-Daether.dependencyCollector.impl=df'


def versions():
    return tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text(encoding='utf-8'))['versions']


def render(directory, home, repository, projects):
    """Version pins come from the same catalog as Gradle, not an independently drifting test reactor."""
    values = versions() | {'repository': repository.as_posix(), 'mavenHome': home.as_posix(),
        'projectsDirectory': projects.as_posix(), 'settingsFile': (ROOT / '.mvn/settings.xml').as_posix()}
    text = (ROOT / 'maven-plugin/src/it/pom.xml').read_text(encoding='utf-8')
    for name, value in values.items():
        text = text.replace('@' + name + '@', html.escape(value, quote=False))
    # Catalog aliases can contain digits (jackson3); fail here rather than launching
    # Maven with an unresolved version and reporting a misleading download failure.
    if re.search(r'@[A-Za-z][A-Za-z0-9_]*@', text):
        raise ValueError('Unresolved Maven integration POM input')
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / 'pom.xml'
    target.write_text(text, encoding='utf-8')
    return target


def warm():
    """Resolve external libraries/plugins/providers without compilation, tests or any fixture hooks."""
    policy = json.loads((ROOT / 'gradle/ci-dependencies.json').read_text())
    value = versions()
    repository = verification.CACHE / 'maven/repository'
    repository.mkdir(parents=True, exist_ok=True)
    verification.extension(download=True)
    for version in policy['mavenDistributions']:
        home = verification.distribution(version, download=True)
        pom = render(ROOT / 'build/cache-warmup/maven' / version, home, repository,
                     ROOT / 'maven-plugin/src/it/projects')
        tree = ET.parse(pom)
        model = tree.getroot()
        plugins = model.find('p:build/p:plugins', NS)
        for artifact, pin in [('maven-compiler-plugin', 'mavenCompiler'), ('maven-resources-plugin', 'mavenResources'),
                              ('maven-jar-plugin', 'mavenJar'), ('maven-surefire-plugin', 'mavenSurefire')]:
            plugin = ET.SubElement(plugins, POM + 'plugin')
            ET.SubElement(plugin, POM + 'artifactId').text = artifact
            ET.SubElement(plugin, POM + 'version').text = value[pin]
        tree.write(pom, encoding='utf-8', xml_declaration=True)
        goal = 'org.apache.maven.plugins:maven-dependency-plugin:' + value['mavenDependency']
        # The Maven super POM manages unrelated release/site/install plugins. None is executed
        # by these verify fixtures; do not warm an accidental release toolchain or its hooks.
        excluded = ('maven-clean-plugin,maven-install-plugin,maven-deploy-plugin,maven-site-plugin,'
                    'maven-release-plugin,maven-antrun-plugin,maven-assembly-plugin')
        verification.run(home, [SERIAL_COLLECTOR, '-f', str(pom), goal + ':go-offline', '-DexcludeArtifactIds=' + excluded], repository)
        # A superset of dependencies can mediate away versions needed by the smaller,
        # standalone consumer. Resolve its actual POM independently, removing only our
        # unpublished plugin (the separate core-model step covers that class realm).
        example = ET.parse(ROOT / 'examples/maven/pom.xml')
        example_plugins = example.getroot().find('p:build/p:plugins', NS)
        for plugin in list(example_plugins):
            if plugin.findtext('p:groupId', namespaces=NS) == 'io.github.sjacek.promptjig':
                example_plugins.remove(plugin)
        # Override the super POM's managed dependency-tool version in this warmup-only
        # model. The application does not use it; go-offline must not resolve that old default.
        tool = ET.SubElement(example_plugins, POM + 'plugin')
        ET.SubElement(tool, POM + 'artifactId').text = 'maven-dependency-plugin'
        ET.SubElement(tool, POM + 'version').text = value['mavenDependency']
        example_pom = pom.with_name('example-dependencies.xml')
        example.write(example_pom, encoding='utf-8', xml_declaration=True)
        verification.run(home, [SERIAL_COLLECTOR, '-f', str(example_pom), goal + ':go-offline', '-DexcludeArtifactIds=' + excluded], repository)
        # Resolve core as a dependency node, not as the root project's flattened model.
        # Maven must read even conflict-losing transitive POMs before mediation. A flattened
        # root BOM can otherwise hide these downloads as well as consumer-selected versions.
        library = copy.deepcopy(model)
        library.find('p:artifactId', NS).text = 'promptjig-core-warmup-testdata'
        library.remove(library.find('p:dependencies', NS))
        library.remove(library.find('p:dependencyManagement', NS))
        index = list(library).index(library.find('p:build', NS))
        dependencies = ET.Element(POM + 'dependencies')
        dependency = ET.SubElement(dependencies, POM + 'dependency')
        for key, text in [('groupId', 'io.github.sjacek.promptjig'), ('artifactId', 'promptjig-core'),
                          ('version', value['promptjig']), ('type', 'pom')]:
            ET.SubElement(dependency, POM + key).text = text
        library.insert(index, dependencies)
        core_pom = pom.with_name('core-dependencies.xml')
        ET.ElementTree(library).write(core_pom, encoding='utf-8', xml_declaration=True)
        private = pom.parent / 'core-model-repository'
        shutil.copytree(repository, private, dirs_exist_ok=True)
        generated = ROOT / 'core/build/publications/core/pom-default.xml'
        candidate = private / 'io/github/sjacek/promptjig/promptjig-core' / value['promptjig'] / ('promptjig-core-' + value['promptjig'] + '.pom')
        candidate.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(generated, candidate)
        entries = verification.load_checksums()
        identity = 'io.github.sjacek.promptjig:promptjig-core:pom:' + value['promptjig']
        if identity in entries:
            raise ValueError('Warmup must not override a reviewed external coordinate')
        entries[identity] = verification.digest(candidate)
        temporary_policy = pom.with_name('core-model-checksums.sha256')
        temporary_policy.write_text(''.join(k + ' ' + v + '\n' for k, v in sorted(entries.items())), encoding='utf-8')
        verification.run(home, [SERIAL_COLLECTOR, '-f', str(core_pom), goal + ':resolve'], private, temporary_policy)
        promote_external_downloads(private, repository)
        # Surefire selects this provider only at execution time; download it explicitly without running tests.
        verification.run(home, [SERIAL_COLLECTOR, '-f', str(pom), goal + ':get',
            '-Dartifact=org.apache.maven.surefire:surefire-junit-platform:' + value['mavenSurefire']], repository)


def promote_external_downloads(source, destination):
    """Export only reviewed external payloads from metadata-only warmup; never the local core model."""
    baseline = verification.load_checksums()
    for file in source.rglob('*'):
        if not file.is_file() or file.is_symlink():
            continue
        relative = file.relative_to(source).as_posix()
        identity = verification.artifact_identity(relative)
        if identity is None or identity.startswith('io.github.sjacek') or identity not in baseline:
            continue
        if verification.digest(file) != baseline[identity]:
            raise ValueError('Cannot promote a corrupted Maven dependency: ' + identity)
        target = destination / relative
        verification.contained(destination, target)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(target.suffix + '.part')
        verification.contained(destination, temporary)
        temporary.unlink(missing_ok=True)
        shutil.copyfile(file, temporary)
        temporary.replace(target)


def install_candidates(repository, output):
    """Trust only exact bytes from this invocation's Gradle staging, outside the download snapshot."""
    value = versions()['promptjig']
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-SNAPSHOT)?', value):
        raise ValueError('Invalid candidate version')
    entries = verification.load_checksums()
    for module, artifact in [('core', 'promptjig-core'), ('maven-plugin', 'promptjig-maven-plugin')]:
        relative = Path('io/github/sjacek/promptjig') / artifact / value
        for kind in ('pom', 'jar'):
            source = ROOT / module / 'build/staging-repository' / relative / f'{artifact}-{value}.{kind}'
            verification.contained(ROOT / module / 'build/staging-repository', source)
            if not source.is_file() or source.is_symlink():
                raise ValueError('Stage the current core and Maven plugin in Gradle before native acceptance')
            identity = f'io.github.sjacek.promptjig:{artifact}:{kind}:{value}'
            if identity in entries:
                raise ValueError('A local build candidate must not replace a reviewed external artifact')
            target = repository / relative / source.name
            verification.contained(repository, target)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            entries[identity] = verification.digest(source)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(''.join(key + ' ' + checksum + '\n' for key, checksum in sorted(entries.items())), encoding='utf-8')
    return output


def java_version(home):
    java = Path(home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    result = subprocess.run([str(java), '-version'], capture_output=True, text=True, check=True)
    match = re.search(r'(?:openjdk|java) version "(\d+)', result.stdout + result.stderr)
    if not match or int(match[1]) < 17:
        raise ValueError('Maven acceptance requires a verified JDK 17+')
    return match[1]


def reject_tampered_local_artifacts(home, repository, checksums, java_home, work, output):
    """Prove native enforcement independently of the archive reader, on both restored POMs and JARs."""
    project = work / 'tampered-restored-testdata'
    shutil.copytree(ROOT / 'examples/maven', project, ignore=shutil.ignore_patterns('target', '.idea'))
    version = versions()['st4']
    folder = repository / 'org/antlr/ST4' / version
    for kind in ('jar', 'pom'):
        target = folder / f'ST4-{version}.{kind}'
        original = target.read_bytes()
        log = output / f'rejected-restored-{kind}.log'
        try:
            # Keep the POM syntactically valid: only the checksum may reject this otherwise usable input.
            target.write_bytes(original + (b'\n<!-- changed restored bytes -->\n' if kind == 'pom' else b'\nchanged restored bytes\n'))
            with log.open('w', encoding='utf-8') as stream:
                try:
                    verification.run(home, [SERIAL_COLLECTOR, '-f', str(project / 'pom.xml'),
                        'io.github.sjacek.promptjig:promptjig-maven-plugin:' + versions()['promptjig'] + ':validate'],
                        repository, checksums, java_home, offline=True, stdout=stream, stderr=subprocess.STDOUT, timeout=120)
                except subprocess.CalledProcessError:
                    text = log.read_text(encoding='utf-8', errors='replace')
                    if ('ArtifactChecksumError' not in text or 'Checksums did not match' not in text
                            or f'org.antlr:ST4:{kind}:{version}' not in text):
                        raise ValueError('Tamper probe failed for a reason other than native checksum rejection; see ' + str(log))
                else:
                    raise ValueError('Native Maven accepted a modified restored ' + kind)
            if (project / 'target/reports/prompt-contracts/validate/validation.json').exists():
                raise ValueError('The goal ran despite a modified restored dependency')
        finally:
            target.write_bytes(original)


def invoker_results(reports, expected):
    """Require every named fixture to execute: skipped, duplicate or unrelated jobs are not green."""
    jobs = []
    for path in sorted(reports.glob('BUILD-*.xml')):
        result = ET.parse(path).getroot()
        project = (result.get('project') or '').replace('\\', '/').removesuffix('/pom.xml').split('/')[-1]
        jobs.append({'report': path.name, 'result': result.get('result'), 'project': project,
                     'executionCount': int(result.get('executionCount', '0'))})
    if (len(jobs) != len(expected) or {job['project'] for job in jobs} != set(expected)
            or any(job['result'] != 'success' or job['executionCount'] < 1 for job in jobs)):
        raise ValueError('Incomplete or unsuccessful Invoker selection: ' + json.dumps(jobs))
    return jobs


def require_native_runtime(text, version, java):
    """Check the launched runtime, not just the JDK requested for the matrix label."""
    if 'Apache Maven ' + version not in text or re.search(r'Java version: ' + re.escape(java) + r'\.', text) is None:
        raise ValueError('Native Maven/JDK did not match the selected matrix cell')


def verify(version, java_home):
    """Run native Invoker fixtures and the standalone example from restored downloads plus current staged bytes."""
    home = verification.distribution(version)  # Validate the exact pin before constructing output/work paths.
    java = java_version(java_home)
    output = ROOT / 'maven-plugin/build/reports/maven' / f'{version}-jdk-{java}'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'summary.json').unlink(missing_ok=True)
    parent = ROOT / 'maven-plugin/build/integration'
    parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='maven-' + version + '-', dir=parent))
    repository = work / 'repository'
    source = verification.CACHE / 'maven/repository'
    if not source.is_dir():
        raise ValueError('Warm or restore the Maven dependency snapshot before running native acceptance')
    shutil.copytree(source, repository)
    policy = install_candidates(repository, work / 'consumer-checksums.sha256')
    projects = work / 'source-projects'
    shutil.copytree(ROOT / 'maven-plugin/src/it/projects', projects)
    shutil.copytree(ROOT / 'examples/maven', projects / 'standalone-example',
                    ignore=shutil.ignore_patterns('target', '.idea'))
    expected = sorted(path.parent.name for path in projects.glob('*/pom.xml'))
    pom = render(work, home, repository, projects)
    log = output / 'maven.log'
    try:
        with log.open('w', encoding='utf-8') as stream:
            verification.run(home, ['-f', str(pom), 'org.apache.maven.plugins:maven-invoker-plugin:'
                + versions()['mavenInvoker'] + ':run'], repository, policy, java_home,
                offline=True, stdout=stream, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError:
        print(log.read_text(encoding='utf-8', errors='replace')[-7000:])
        raise
    finally:
        # Preserve the actual failure, including child compiler/test logs, before a CI runner disappears.
        reports = work / 'target/invoker-reports'
        if reports.is_dir():
            shutil.copytree(reports, output / 'invoker-reports', dirs_exist_ok=True)
        for file in (work / 'target/projects').rglob('*'):
            if file.is_file() and (file.name == 'build.log' or
                    (file.name.startswith('TEST-') and file.suffix == '.xml')):
                target = output / 'fixtures' / file.relative_to(work / 'target/projects')
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(file, target)
    reports = work / 'target/invoker-reports'
    if not reports.is_dir():
        raise ValueError('Invoker did not produce required reports')
    # Interpret the native result schema, not merely the parent Maven exit code.
    require_native_runtime(log.read_text(encoding='utf-8', errors='replace'), version, java)
    jobs = invoker_results(reports, expected)
    reject_tampered_local_artifacts(home, repository, policy, java_home, work, output)
    result = {'maven': version, 'jdk': java, 'candidate': versions()['promptjig'], 'fixtures': expected,
              'results': jobs, 'offline': True, 'rejectedTamperedLocalArtifacts': ['jar', 'pom'],
              'functionalCollector': 'Maven default', 'tamperProbeCollector': 'df',
              'dependencyVerification': 'enforce SHA-256, including local artifacts'}
    (output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print('PASS: Maven', version, '/ JDK', java, ':', len(jobs), 'native Invoker projects, including standalone example')


def main():
    # Windows redirected consoles can use a legacy code page; never mask a Maven failure
    # while printing a bounded diagnostic containing a replacement/non-ASCII character.
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(errors='backslashreplace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['warm', 'verify'])
    parser.add_argument('--maven-version', default='3.9.16')
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME', ''))
    arguments = parser.parse_args()
    if arguments.command == 'warm':
        warm()
    else:
        verify(arguments.maven_version, arguments.java_home)


if __name__ == '__main__':
    main()
