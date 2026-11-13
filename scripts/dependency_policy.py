"""Offline checks for reviewed Actions, Wrapper and the existing strict resolver policies."""
import hashlib
import json
from pathlib import Path
import re
import ci_dependency_cache as cache

ROOT = Path(__file__).resolve().parents[1]


def validate(root=ROOT):
    policy = json.loads((root / 'security/build-inputs.json').read_text())
    if policy.get('schemaVersion') != 1 or not policy.get('actions'):
        raise ValueError('Missing reviewed build-input policy')
    for file in (root / '.github').rglob('*.yml'):
        for action in re.findall(r'^\s*(?:-\s*)?uses:\s*([^\s#]+)', file.read_text(), re.M):
            if action.startswith('./.github/actions/'):
                if '..' in Path(action).parts or not (root / action / 'action.yml').is_file():
                    raise ValueError('Invalid local action')
                continue
            name, separator, revision = action.partition('@')
            expected = policy['actions'].get(name)
            if (separator != '@' or not re.fullmatch(r'[a-f0-9]{40}', revision)
                    or not expected or expected['sha'] != revision):
                raise ValueError('Unreviewed or mutable action: ' + action)
    jar = root / 'gradle/wrapper/gradle-wrapper.jar'
    if jar.is_symlink() or hashlib.sha256(jar.read_bytes()).hexdigest() != policy['wrapperJarSha256']:
        raise ValueError('Gradle Wrapper JAR checksum mismatch')
    wrapper = (root / 'gradle/wrapper/gradle-wrapper.properties').read_text()
    version = policy['wrapperVersion']
    if f'gradle-{version}-bin.zip' not in wrapper:
        raise ValueError('Wrapper version differs from reviewed policy')
    distributions = json.loads((root / 'gradle/ci-dependencies.json').read_text())['gradleDistributions']
    if 'distributionSha256Sum=' + distributions[version] not in wrapper:
        raise ValueError('Wrapper distribution checksum differs from reviewed policy')
    if root == cache.ROOT:
        cache.checksums()  # Reject broad trust exceptions and missing native checksums.
    print('Verified immutable Actions, Wrapper and strict dependency policy')


if __name__ == '__main__':
    validate()
