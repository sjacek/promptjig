"""The trace observer must detect failed outbound calls and fail on unfamiliar IP syntax."""
import json
import copy
from pathlib import Path
import shutil
import tempfile
import unittest
import dependency_policy
from offline_security import network_attempts, validate_container


class TraceObserverTest(unittest.TestCase):
    def test_isolation_rejects_host_network_root_write_extra_mounts_and_missing_restrictions(self):
        evidence = {'network': 'none', 'readOnlyRoot': True, 'user': '10001:10001', 'capDrop': ['ALL'],
            'environmentNames': ['PATH', 'JAVA_HOME', 'HOME', 'SOURCE_COMMIT'],
            'securityOptions': ['no-new-privileges'], 'mounts': [{'destination': name, 'writable': name in {'/work', '/evidence'}}
                for name in ('/inputs/source', '/inputs/ci-dependencies.tar', '/runner/offline_security.py', '/work', '/evidence')]}
        validate_container(evidence)
        for key, value in [('network', 'host'), ('readOnlyRoot', False), ('user', '0'), ('capDrop', []), ('securityOptions', [])]:
            changed = copy.deepcopy(evidence)
            changed[key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'isolation'):
                validate_container(changed)
        changed = copy.deepcopy(evidence)
        changed['environmentNames'].append('GITHUB_TOKEN')
        with self.assertRaisesRegex(ValueError, 'environment'):
            validate_container(changed)
        evidence['mounts'].append({'destination': '/var/run/docker.sock', 'writable': False})
        with self.assertRaisesRegex(ValueError, 'isolation'):
            validate_container(evidence)
    def test_failed_outbound_ipv4_ipv6_and_dns_are_evidence(self):
        trace = '\n'.join([
            '1 connect(3, {sa_family=AF_INET, sin_port=htons(443), sin_addr=inet_addr("198.51.100.1")}, 16) = -1 ENETUNREACH',
            '2 connect(3, {sa_family=AF_INET6, sin6_port=htons(443), inet_pton(AF_INET6, "2001:db8::1", &sin6_addr)}, 28) = -1 ENETUNREACH',
            '3 sendto(4, "x", 1, 0, {sa_family=AF_INET, sin_port=htons(53), sin_addr=inet_addr("127.0.0.53")}, 16) = 1'])
        attempts = network_attempts(trace)
        self.assertEqual(['198.51.100.1', '2001:db8::1', '127.0.0.53'], [item['address'] for item in attempts])
        self.assertTrue(attempts[-1]['dns'])

    def test_internal_gradle_transport_and_unix_sockets_are_distinct_from_egress(self):
        trace = '\n'.join([
            '1 connect(3, {sa_family=AF_INET, sin_port=htons(32100), sin_addr=inet_addr("127.0.0.1")}, 16) = 0',
            '2 connect(3, {sa_family=AF_INET6, sin6_port=htons(32100), inet_pton(AF_INET6, "::ffff:127.0.0.1", &sin6_addr)}, 28) = 0',
            '3 connect(3, {sa_family=AF_UNIX, sun_path="/tmp/local"}, 32) = 0'])
        self.assertEqual([], network_attempts(trace))

    def test_unknown_ip_destination_cannot_be_reported_as_no_network(self):
        with self.assertRaisesRegex(ValueError, 'Unparsed IP'):
            network_attempts('1 connect(3, {sa_family=AF_INET, unknown_encoding}, 16) = -1')


class BuildInputsTest(unittest.TestCase):
    def test_reviewed_policy_accepts_actual_workflows_and_rejects_mutable_actions_or_wrapper(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('.github', 'security', 'gradle'):
                shutil.copytree(dependency_policy.ROOT / name, root / name)
            dependency_policy.validate(root)
            workflow = root / '.github/workflows/compatibility.yml'
            original = workflow.read_text()
            policy = json.loads((root / 'security/build-inputs.json').read_text())
            sha = policy['actions']['actions/checkout']['sha']
            workflow.write_text(original.replace('actions/checkout@' + sha, 'actions/checkout@v7'))
            with self.assertRaisesRegex(ValueError, 'mutable action'):
                dependency_policy.validate(root)
            workflow.write_text(original)
            (root / 'gradle/wrapper/gradle-wrapper.jar').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'Wrapper JAR'):
                dependency_policy.validate(root)


if __name__ == '__main__':
    unittest.main()
