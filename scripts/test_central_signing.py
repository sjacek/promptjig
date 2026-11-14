"""Real Gradle/GPG smoke test using a disposable key; no Central credentials or upload.

Run explicitly on Linux with GnuPG and an authoring JDK, independently of the fast
offline protocol suite. Private material lives in a temporary GPG home and child
environment only. Never use a publisher's keyring for this test.
"""
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import unittest
import zipfile

import central_publication as central


class CentralSigningTest(unittest.TestCase):
    def test_real_gradle_publications_have_verifiable_signatures_and_no_private_key(self):
        self.assertIsNotNone(shutil.which('gpg'), 'Install GnuPG to run the explicit signing smoke test')
        with tempfile.TemporaryDirectory(prefix='promptjig-signing-test-') as temporary:
            home = Path(temporary)
            home.chmod(0o700)
            environment = {key: value for key, value in os.environ.items()
                           if not key.startswith(('MAVEN_', 'GRADLE_PUBLISH_', 'ORG_GRADLE_PROJECT_signing'))}
            environment['GNUPGHOME'] = str(home)
            passphrase = secrets.token_urlsafe(24)
            def gpg(*args, input=None):
                result = subprocess.run(['gpg', '--batch', '--pinentry-mode', 'loopback', *args],
                                        input=input, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        env=environment, timeout=120)
                self.assertEqual(0, result.returncode, 'Disposable GPG operation failed')
                return result.stdout
            try:
                gpg('--passphrase-fd', '0', '--quick-generate-key',
                    'Promptjig signing-testdata <fixture@example.invalid>', 'rsa2048', 'sign', '1d',
                    input=(passphrase + '\n').encode())
                exported = gpg('--passphrase-fd', '0', '--armor', '--export-secret-keys',
                               input=(passphrase + '\n').encode()).decode()
                self.assertIn('BEGIN PGP PRIVATE KEY BLOCK', exported)
                # Expose only the test key to the signing invocation, never a real Central token.
                environment.update(MAVEN_GPG_KEY=exported, MAVEN_GPG_PASSPHRASE=passphrase)
                # Secret-bearing configuration must never enter configuration cache. An unsigned
                # default build must also fail instead of silently skipping requested release signing.
                for secret_environment, cache_flag, expected in (
                        (environment, '--configuration-cache', b'--no-configuration-cache'),
                        (environment, None, b'--no-configuration-cache'),
                        ({k: v for k, v in environment.items() if not k.startswith('MAVEN_GPG_')},
                         '--no-configuration-cache', b'MAVEN_GPG_KEY and MAVEN_GPG_PASSPHRASE')):
                    rejected = subprocess.run(['bash', './gradlew', 'help', '-PcentralRelease=true',
                                               *([cache_flag] if cache_flag else []),
                                               '--no-daemon', '--offline', '--console=plain'], cwd=central.ROOT,
                                              env=secret_environment, stdout=subprocess.PIPE,
                                              stderr=subprocess.STDOUT, timeout=180)
                    self.assertNotEqual(0, rejected.returncode)
                    self.assertIn(expected, rejected.stdout)
                command = ['bash', './gradlew', ':core:publishAllPublicationsToCentralStagingRepository',
                           ':spring-ai:publishAllPublicationsToCentralStagingRepository',
                           ':maven-plugin:publishAllPublicationsToCentralStagingRepository', '-PcentralRelease=true',
                           '--no-configuration-cache', '--no-daemon', '--no-build-cache',
                           '--dependency-verification=strict', '--console=plain']
                result = subprocess.run(command, cwd=central.ROOT, env=environment, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, timeout=600)
                if result.returncode:
                    # This key is deliberately disposable; still avoid persisting or printing the key itself.
                    output = result.stdout.decode(errors='replace').replace(exported, '[test key omitted]')
                    self.fail('Signed Gradle staging failed:\n' + output[-5000:])
                files = central.publication_files()
                self.assertEqual(54, len(files))
                for module, artifact in central.LIBRARIES.items():
                    folder = central.ROOT / module / 'build/central-staging' / Path(*central.GROUP.split('.')) / artifact / central.version()
                    for signature in folder.glob('*.asc'):
                        gpg('--verify', str(signature), str(signature.with_suffix('')))
                # Signing must use working copies: no .asc or other byproducts
                # may pollute the portable set uploaded for the real release.
                central.publication_sboms.verify()
                bundle = central.bundle(output=home / 'bundle')
                with zipfile.ZipFile(bundle) as archive:
                    self.assertEqual(162, len(archive.namelist()))
                    for name in archive.namelist():
                        self.assertNotIn(b'BEGIN PGP PRIVATE KEY BLOCK', archive.read(name))
                        self.assertNotIn(passphrase.encode(), archive.read(name))
                print('Verified 27 real Gradle signatures, including 12 SBOMs; no publication performed.')
            finally:
                # Stop only the agent attached to this temporary home before removing it.
                subprocess.run(['gpgconf', '--homedir', str(home), '--kill', 'gpg-agent'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)


if __name__ == '__main__':
    unittest.main()
