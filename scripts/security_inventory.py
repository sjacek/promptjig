"""Resolve producer and actual consumer graphs, without running tests or verification hooks."""
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

import ci_dependency_cache as cache
import maven_integration as maven
import maven_verification as verification
import security_reports as reports

ROOT = reports.ROOT
OUTPUT = ROOT / 'build/security/native/consumers'


def gradle_graphs():
    environment = dict(os.environ)
    java21 = environment.get('WARMUP_JAVA_HOME')
    if not java21:
        raise ValueError('Set WARMUP_JAVA_HOME to a JDK supported by every TestKit Gradle (21)')
    environment['JAVA_HOME'] = java21
    graphs, evidence = [], []
    init = ROOT / 'scripts/security_consumer_inventory.gradle'
    home = ROOT / 'build/ci-tools/gradle'
    for version in cache.policy()['gradleDistributions']:
        fixture = OUTPUT / ('testkit-' + version)
        fixture.mkdir(parents=True, exist_ok=True)
        (fixture / 'settings.gradle').write_text("rootProject.name = 'security-host-testdata'\n")
        (fixture / 'build.gradle').write_text("plugins { id 'java' }\nrepositories { mavenCentral() }\n"
            + "dependencies { implementation 'tools.jackson.core:jackson-databind:" + maven.versions()['jackson3'] + "' }\n")
        (fixture / 'gradle').mkdir(exist_ok=True)
        (fixture / 'gradle/verification-metadata.xml').write_bytes((ROOT / 'gradle/verification-metadata.xml').read_bytes())
        output = fixture / 'native.json'
        command = [str(home / ('gradle-' + version) / 'bin' / ('gradle.bat' if os.name == 'nt' else 'gradle'))]
        subprocess.run([*command, '-p', str(fixture), 'securityConsumerInventory', '-I', str(init),
            '-PsecurityOutput=' + str(output), '--offline', '--no-daemon', '--no-configuration-cache',
            '--dependency-verification=strict', '--console=plain', '--gradle-user-home',
            str(ROOT / 'build/ci-dependencies/testkit')], check=True, env=environment, cwd=ROOT, timeout=300)
        graphs.extend(reports.read(output))
        evidence.append(output)
    for name in cache.policy()['examples']:
        output = OUTPUT / (Path(name).name + '-gradle.json')
        subprocess.run([str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')), '-p', name,
            'securityConsumerInventory', '-I', str(init), '-PsecurityOutput=' + str(output),
            '-PpromptjigPluginBuild=' + str(ROOT), '--offline', '--no-configuration-cache',
            '--dependency-verification=strict', '--console=plain'], check=True, env=environment, cwd=ROOT, timeout=300)
        graphs.extend(reports.read(output))
        evidence.append(output)
    return graphs, evidence


def artifact(group, name, version, path):
    path = Path(path)
    if not path.is_file() or path.is_symlink():
        raise ValueError('Missing native Maven artifact: ' + str(path))
    identity = {'kind': 'module', 'group': group, 'name': name, 'version': version}
    return {'component': group + ':' + name + ':' + version, 'identity': identity,
            'name': path.name, 'file': str(path), 'sha256': reports.digest(path)}


def plugin_graphs(path, version):
    """Maven reports flat realm membership. Preserve that, without inventing transitive paths."""
    graphs, current = [], None
    for line in path.read_text().splitlines():
        match = re.fullmatch(r'(\s+)([^ :]+):([^ :]+):jar:([^ :]+):(.+)', line)
        if not match:
            if line.strip() and line.strip() != 'The following plugins have been resolved:':
                raise ValueError('Unexpected native plugin inventory line: ' + line)
            continue
        indent, group, name, release, file = match.groups()
        item = artifact(group, name, release, file)
        if len(indent) == 3:
            current = {'project': 'maven-' + version, 'configuration': name,
                'source': 'native-maven-resolve-plugins-flat-realm', 'root': item['component'], 'components': [], 'artifacts': []}
            graphs.append(current)
        if current is None:
            raise ValueError('Maven realm has no plugin root')
        if not any(a['component'] == item['component'] for a in current['artifacts']):
            current['artifacts'].append(item)
    for graph in graphs:
        for item in graph['artifacts']:
            graph['components'].append({'id': item['component'], 'identity': item['identity'],
                'dependencies': [a['component'] for a in graph['artifacts'] if a['component'] != graph['root']]
                    if item['component'] == graph['root'] else []})
    expected = {'maven-' + name + '-plugin' for name in ('compiler', 'resources', 'jar', 'surefire', 'invoker', 'dependency')}
    if {graph['configuration'] for graph in graphs} != expected:
        raise ValueError('Incomplete native Maven build-tool selection')
    return graphs


def tree_graph(path, version, repository):
    tree = reports.read(path)
    nodes, artifacts = {}, {}
    def visit(node, root=False):
        group, name, release = (node[key] for key in ('groupId', 'artifactId', 'version'))
        identity = group + ':' + name + ':' + release
        children = [visit(child) for child in node.get('children', [])]
        nodes[identity] = {'id': identity, 'identity': {'kind': 'module', 'group': group, 'name': name, 'version': release},
                           'dependencies': children}
        if not root and node.get('type') == 'jar':
            classifier = '-' + node['classifier'] if node.get('classifier') else ''
            file = repository / group.replace('.', '/') / name / release / (name + '-' + release + classifier + '.jar')
            artifacts[identity] = artifact(group, name, release, file)
        return identity
    root = visit(tree, True)
    return {'project': 'maven-' + version, 'configuration': 'fixture-tests-and-selected-surefire-provider',
            'source': 'native-maven-dependency-tree', 'root': root, 'components': list(nodes.values()), 'artifacts': list(artifacts.values())}


def maven_graphs():
    build, tests, evidence = [], [], []
    repository = verification.CACHE / 'maven/repository'
    values = maven.versions()
    for version in cache.policy()['mavenDistributions']:
        home = verification.distribution(version)
        directory = OUTPUT / ('maven-' + version)
        pom = maven.render(directory, home, repository, ROOT / 'maven-plugin/src/it/projects')
        tree = ET.parse(pom)
        plugins = tree.getroot().find('p:build/p:plugins', maven.NS)
        for name in ('Compiler', 'Resources', 'Jar', 'Surefire'):
            node = ET.SubElement(plugins, maven.POM + 'plugin')
            ET.SubElement(node, maven.POM + 'artifactId').text = 'maven-' + name.lower() + '-plugin'
            ET.SubElement(node, maven.POM + 'version').text = values['maven' + name]
        # Surefire selects this provider at test execution; its native graph must
        # be resolved explicitly without launching any tests.
        dependency = ET.SubElement(tree.getroot().find('p:dependencies', maven.NS), maven.POM + 'dependency')
        for key, value in [('groupId', 'org.apache.maven.surefire'), ('artifactId', 'surefire-junit-platform'),
                           ('version', values['mavenSurefire']), ('scope', 'test')]:
            ET.SubElement(dependency, maven.POM + key).text = value
        tree.write(pom, encoding='utf-8', xml_declaration=True)
        realm = directory / 'plugins.txt'
        dependencies = directory / 'tree.json'
        goal = 'org.apache.maven.plugins:maven-dependency-plugin:' + values['mavenDependency']
        with (directory / 'resolution.log').open('w', encoding='utf-8') as log:
            verification.run(home, [maven.SERIAL_COLLECTOR, '-f', str(pom), goal + ':resolve-plugins',
                '-DincludeArtifactIds=maven-compiler-plugin,maven-resources-plugin,maven-jar-plugin,maven-surefire-plugin,maven-invoker-plugin,maven-dependency-plugin',
                '-DoutputAbsoluteArtifactFilename=true', '-DoutputFile=' + str(realm)], repository,
                offline=True, stdout=log, stderr=subprocess.STDOUT, timeout=300)
            verification.run(home, [maven.SERIAL_COLLECTOR, '-f', str(pom), goal + ':tree', '-DoutputType=json',
                '-DoutputFile=' + str(dependencies)], repository, offline=True, stdout=log, stderr=subprocess.STDOUT, timeout=300)
        build.extend(plugin_graphs(realm, version))
        tests.append(tree_graph(dependencies, version, repository))
        evidence.extend([realm, dependencies, pom])
    return build, tests, evidence


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    reports.invalidate()
    cache.prepare()
    wrapper = str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
    gradle, gradle_files = gradle_graphs()
    build, tests, maven_files = maven_graphs()
    # Included-build examples use the consumer JDK and can rebuild project JARs.
    # Capture producer bytes last, after those builds, on the authoring JDK.
    subprocess.run([wrapper, 'securityInventories', '--offline', '--dependency-verification=strict',
        '--no-configuration-cache', '--console=plain'], cwd=ROOT, check=True)
    for distribution in reports.DISTRIBUTIONS:
        file = ROOT / 'build/security/native' / (distribution + '.json')
        native = reports.read(file)
        native['consumerEvidence'] = []
        if distribution == 'gradle-plugin':
            native['scopes']['test'].extend(gradle)
            evidence = gradle_files
        elif distribution == 'maven-plugin':
            native['scopes']['build'].extend(build)
            native['scopes']['test'].extend(tests)
            evidence = maven_files
        else:
            evidence = []
        native['consumerEvidence'] = [{'file': path.relative_to(ROOT).as_posix(), 'sha256': reports.digest(path)} for path in evidence]
        native['consumersResolved'] = True
        reports.write(file, native)


if __name__ == '__main__':
    main()
