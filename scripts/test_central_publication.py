"""Offline publication regressions: real bundles, injected HTTP, no publisher credentials."""
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

import central_publication as central

VERSION = '0.2.0'
ID = '28570f16-da32-4c14-bd2e-c1acc0782365'
SHA = 'a' * 40
SIGNATURE = b'-----BEGIN PGP SIGNATURE-----\nfixture-only\n-----END PGP SIGNATURE-----\n'


class HttpFixture:
    """Return queued responses while recording destinations, methods and request bodies."""
    def __init__(self, *responses):
        self.responses, self.requests = list(responses), []

    def open(self, request, timeout):
        self.requests.append(request)
        if not self.responses:
            raise AssertionError('Unexpected additional HTTP request')
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return io.BytesIO(response if isinstance(response, bytes) else json.dumps(response).encode())


def status(value='PUBLISHED', **overrides):
    return {'deploymentId': ID, 'deploymentName': f'Promptjig {VERSION}', 'deploymentState': value,
            'purls': [f'pkg:maven/{central.GROUP}/{artifact}@{VERSION}' for artifact in central.LIBRARIES.values()], **overrides}


class CentralPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'gradle').mkdir()
        (self.root / 'gradle/libs.versions.toml').write_text(f'[versions]\npromptjig = "{VERSION}"\n')
        self.output = self.root / 'output'
        self.environ = {'GITHUB_REPOSITORY': central.REPO, 'RELEASE_TAG': f'v{VERSION}', 'RELEASE_COMMIT': SHA,
                        'MAVEN_CENTRAL_USERNAME': 'fixture-user', 'MAVEN_CENTRAL_PASSWORD': 'fixture-token'}

    def stage(self, signed=True):
        for module, artifact in central.LIBRARIES.items():
            folder = self.root / module / 'build' / ('central-staging' if signed else 'staging-repository')
            folder /= Path(*central.GROUP.split('.')) / artifact / VERSION
            folder.mkdir(parents=True)
            prefix = artifact + '-' + VERSION
            dependency = (f'<dependencies><dependency><groupId>{central.GROUP}</groupId><artifactId>promptjig-core</artifactId>'
                          f'<version>{VERSION}</version></dependency></dependencies>') if module in ('spring-ai', 'maven-plugin') else ''
            if module == 'maven-plugin':
                dependency = dependency.replace('</dependencies>', ''.join(
                    '<dependency><groupId>org.apache.maven</groupId><artifactId>' + host
                    + '</artifactId><version>3.9.16</version><scope>provided</scope></dependency>'
                    for host in ('maven-plugin-api', 'maven-core')) + '</dependencies>')
            pom = (f'<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                   f'<groupId>{central.GROUP}</groupId><artifactId>{artifact}</artifactId><version>{VERSION}</version>'
                   '<name>Promptjig</name><description>Fixture</description><url>https://example.test</url>'
                   '<licenses><license><name>MIT</name><url>https://example.test/license</url></license></licenses>'
                   '<developers><developer><name>Fixture</name><url>https://example.test/author</url></developer></developers>'
                   '<scm><url>https://example.test</url><connection>scm:git:https://example.test</connection></scm>'
                   + ('<packaging>maven-plugin</packaging>' if module == 'maven-plugin' else '')
                   + dependency + '</project>')
            (folder / (prefix + '.pom')).write_text(pom)
            (folder / (prefix + '.module')).write_text(json.dumps({'component': {
                'group': central.GROUP, 'module': artifact, 'version': VERSION}}))
            for suffix, content in (('.jar', 'Fixture.class'), ('-sources.jar', 'Fixture.java'), ('-javadoc.jar', 'index.html')):
                with zipfile.ZipFile(folder / (prefix + suffix), 'w') as jar:
                    jar.writestr('META-INF/LICENSE', 'MIT fixture')
                    jar.writestr(content, 'fixture')
            if signed:
                for item in list(folder.iterdir()):
                    item.with_name(item.name + '.asc').write_bytes(SIGNATURE)
        return folder

    def test_unsigned_staging_is_offline_and_never_accepted_as_a_signed_bundle(self):
        self.stage(signed=False)
        self.assertEqual(15, len(central.publication_files(self.root, signed=False)))
        with self.assertRaises(central.PublicationError):
            central.bundle(self.root, self.output)

    def test_bundle_contains_exactly_all_signed_publications_and_computed_sidecars(self):
        self.stage()
        bundle = central.bundle(self.root, self.output)
        original = bundle.read_bytes()
        with zipfile.ZipFile(bundle) as archive:
            self.assertEqual(90, len(archive.namelist()))
            self.assertTrue(all(name.startswith('io/github/sjacek/promptjig/') for name in archive.namelist()))
            self.assertFalse(any('gradle-plugin' in name or 'metadata.xml' in name for name in archive.namelist()))
            for name in archive.namelist():
                if name.endswith(('.jar', '.pom', '.module')):
                    self.assertEqual(SIGNATURE, archive.read(name + '.asc'))
                    for algorithm in central.HASHES:
                        self.assertEqual(hashlib.new(algorithm, archive.read(name), usedforsecurity=False).hexdigest().encode(),
                                         archive.read(name + '.' + algorithm))
        self.assertEqual(original, central.bundle(self.root, self.output).read_bytes())

    def test_missing_signature_or_payload_rejects_bundle(self):
        folder = self.stage()
        signature = next(folder.glob('*.jar.asc'))
        signature.unlink()
        with self.assertRaisesRegex(central.PublicationError, 'Missing publication'):
            central.bundle(self.root, self.output)

    def test_unexpected_files_and_secret_files_are_not_silently_bundled(self):
        folder = self.stage()
        for name in ('gradle.properties', 'secret.asc', 'token.txt', 'other-version.jar'):
            with self.subTest(name=name):
                unwanted = folder / name
                unwanted.write_text('fixture-secret')
                with self.assertRaisesRegex(central.PublicationError, 'Unexpected staging'):
                    central.bundle(self.root, self.output)
                unwanted.unlink()

    def test_foreign_coordinate_and_missing_pom_metadata_are_rejected(self):
        folder = self.stage()
        pom = next(folder.glob('*.pom'))
        original = pom.read_text()
        for changed in (original.replace('<artifactId>promptjig-maven-plugin</artifactId>', '<artifactId>foreign</artifactId>'),
                        original.replace('<name>Fixture</name>', ''),
                        original.replace('<version>0.2.0</version></dependency>', '<version>0.1.0</version></dependency>')):
            with self.subTest(changed=changed):
                pom.write_text(changed)
                with self.assertRaises(central.PublicationError):
                    central.bundle(self.root, self.output)
        pom.write_text(original)

    def test_missing_javadoc_content_and_invalid_signature_are_rejected(self):
        folder = self.stage()
        signature = next(folder.glob('*.asc'))
        signature.write_text('not a signature')
        with self.assertRaisesRegex(central.PublicationError, 'PGP signature'):
            central.bundle(self.root, self.output)
        signature.write_bytes(SIGNATURE)
        with zipfile.ZipFile(next(folder.glob('*-javadoc.jar')), 'w') as jar:
            jar.writestr('META-INF/LICENSE', 'MIT')
        with self.assertRaisesRegex(central.PublicationError, 'Javadoc'):
            central.bundle(self.root, self.output)

    def test_maven_adapter_requires_plugin_packaging_and_provided_host_apis(self):
        folder = self.stage(signed=False)
        pom = next(folder.glob('*.pom'))
        original = pom.read_text()
        for changed in (original.replace('<packaging>maven-plugin</packaging>', '<packaging>jar</packaging>'),
                        original.replace('<scope>provided</scope>', '<scope>runtime</scope>')):
            with self.subTest(changed=changed):
                pom.write_text(changed)
                with self.assertRaises(central.PublicationError):
                    central.publication_files(self.root, signed=False)
        pom.write_text(original)

    def test_staging_symlinks_are_rejected(self):
        folder = self.stage()
        target = self.root / 'outside-secret'
        target.write_text('fixture-secret')
        link = folder / 'secret'
        try:
            link.symlink_to(target)
        except OSError:
            self.skipTest('Physical symlinks require OS privileges')
        with self.assertRaises(central.PublicationError):
            central.bundle(self.root, self.output)

    def test_foreign_module_identity_corrupt_jar_and_empty_sources_are_rejected(self):
        folder = self.stage()
        module = next(folder.glob('*.module'))
        original = module.read_bytes()
        changed = json.loads(original)
        changed['component']['version'] = '0.1.0'
        module.write_text(json.dumps(changed))
        with self.assertRaisesRegex(central.PublicationError, 'module coordinates'):
            central.bundle(self.root, self.output)
        module.write_bytes(original)
        sources = next(folder.glob('*-sources.jar'))
        with zipfile.ZipFile(sources, 'w') as jar:
            jar.writestr('META-INF/LICENSE', 'MIT')
        with self.assertRaisesRegex(central.PublicationError, 'Sources JAR'):
            central.bundle(self.root, self.output)
        sources.write_bytes(b'broken archive')
        with self.assertRaises(zipfile.BadZipFile):
            central.bundle(self.root, self.output)

    def test_bundle_size_limit_preserves_the_last_complete_bundle(self):
        self.stage()
        previous = central.bundle(self.root, self.output).read_bytes()
        with patch.object(central, 'MAX_BUNDLE', 1), self.assertRaises(central.PublicationError):
            central.bundle(self.root, self.output)
        self.assertEqual(previous, (self.output / 'bundle.zip').read_bytes())

    def test_identity_requires_matching_tag_sha_and_canonical_repository(self):
        self.assertEqual({'version': VERSION, 'tag': 'v' + VERSION, 'commit': SHA}, central.identity(self.environ, self.root))
        for key, value in (('RELEASE_TAG', 'main'), ('RELEASE_TAG', 'v0.1.0'),
                           ('RELEASE_COMMIT', ''), ('GITHUB_REPOSITORY', 'fork/promptjig')):
            with self.subTest(key=key, value=value), self.assertRaises(central.PublicationError):
                central.identity({**self.environ, key: value}, self.root)

    def test_stable_version_cannot_be_snapshot_or_shell_input(self):
        for value in ('0.2.0-SNAPSHOT', '01.2.0', 'v0.2.0', '0.2.0;false'):
            (self.root / 'gradle/libs.versions.toml').write_text(f'[versions]\npromptjig = "{value}"\n')
            with self.subTest(value=value), self.assertRaises(central.PublicationError):
                central.version(self.root)

    def ci_responses(self):
        names = ['Prepare verified dependencies', 'core-and-packaging', 'runtime-java17', 'runtime-pilot']
        names += [f'Gradle {g} / JDK {j}' for g in ('9.0.0', '9.1.0', '9.9.1')
                  for j in ('17', '21', '25') if (g, j) != ('9.0.0', '25')]
        names += [f'Maven {m} / JDK {j}' for m in ('3.9.16', '4.0.0-rc-7') for j in ('17', '21', '25')]
        run = {'id': 123, 'head_sha': SHA, 'status': 'completed', 'conclusion': 'success',
               'path': '.github/workflows/compatibility.yml'}
        jobs = [{'name': name, 'status': 'completed', 'conclusion': 'success'} for name in names]
        return {'workflow_runs': [run]}, {'total_count': len(names), 'jobs': jobs}

    def test_ci_requires_eighteen_executed_successful_jobs_for_exact_sha(self):
        http = HttpFixture(*self.ci_responses())
        central.require_ci({'commit': SHA}, 'fixture-github-token', http)
        self.assertEqual(2, len(http.requests))
        self.assertTrue(all(r.full_url.startswith('https://api.github.com/repos/sjacek/promptjig/') for r in http.requests))
        self.assertIn('head_sha=' + SHA, http.requests[0].full_url)

    def test_ci_rejects_missing_stale_failed_or_pending_runs(self):
        run, _ = self.ci_responses()
        for override in ({'head_sha': 'b' * 40}, {'status': 'in_progress'}, {'conclusion': 'failure'},
                         {'path': '.github/workflows/unrelated.yml'}):
            changed = copy.deepcopy(run)
            changed['workflow_runs'][0].update(override)
            with self.subTest(override=override), self.assertRaises(central.PublicationError):
                central.require_ci({'commit': SHA}, 'token', HttpFixture(changed))
        with self.assertRaises(central.PublicationError):
            central.require_ci({'commit': SHA}, 'token', HttpFixture({'workflow_runs': []}))

    def test_ci_rejects_malformed_api_responses(self):
        run, _ = self.ci_responses()
        for payloads in (([],), ({'workflow_runs': [None]},), (run, []), (run, {'jobs': [None]})):
            with self.subTest(payloads=payloads), self.assertRaises(central.PublicationError):
                central.require_ci({'commit': SHA}, 'token', HttpFixture(*payloads))

    def test_green_run_with_skipped_missing_duplicate_or_failed_jobs_is_rejected(self):
        run, jobs = self.ci_responses()
        for conclusion in ('skipped', 'failure', 'cancelled', None):
            changed = copy.deepcopy(jobs)
            changed['jobs'][4]['conclusion'] = conclusion
            with self.subTest(conclusion=conclusion), self.assertRaises(central.PublicationError):
                central.require_ci({'commit': SHA}, 'token', HttpFixture(run, changed))
        for changed in ({**jobs, 'jobs': jobs['jobs'][:-1]},
                        {**jobs, 'jobs': jobs['jobs'][:-1] + [jobs['jobs'][0]]}):
            with self.assertRaises(central.PublicationError):
                central.require_ci({'commit': SHA}, 'token', HttpFixture(run, changed))

    def test_redirect_handler_never_forwards_tokens(self):
        self.assertIsNone(central.NoRedirect().redirect_request(None, None, 307, '', {}, 'https://example.test'))

    def test_upload_is_once_and_id_is_persisted_before_polling(self):
        self.stage()
        http, saved = HttpFixture(ID.encode()), []
        state = central.identity(self.environ, self.root)
        central.upload_once(state, 'fixture-token', central.bundle(self.root, self.output),
                            lambda value: saved.append(copy.deepcopy(value)), http)
        self.assertEqual(1, len(http.requests))
        self.assertEqual(['UPLOAD_STARTING', 'UPLOADED'], [s['status'] for s in saved])
        self.assertEqual(ID, saved[-1]['deploymentId'])
        request = http.requests[0]
        self.assertTrue(request.full_url.startswith(central.API + '/upload?'))
        self.assertIn('publishingType=AUTOMATIC', request.full_url)
        self.assertIn(b'name="bundle"', request.data)
        self.assertNotIn('fixture-token', json.dumps(saved))

    def test_uncertain_upload_is_not_retried_and_retains_recovery_state(self):
        self.stage()
        http, saved = HttpFixture(urllib.error.URLError('secret-server-body')), []
        with self.assertRaisesRegex(central.PublicationError, 'uncertain') as error:
            central.upload_once(central.identity(self.environ, self.root), 'fixture-token',
                                central.bundle(self.root, self.output), lambda v: saved.append(copy.deepcopy(v)), http)
        self.assertEqual(1, len(http.requests))
        self.assertEqual('UPLOAD_UNCERTAIN', saved[-1]['status'])
        self.assertNotIn('secret', str(error.exception))

    def test_upload_with_invalid_id_does_not_retry(self):
        self.stage()
        http = HttpFixture(b'not-an-id')
        with self.assertRaises(central.PublicationError):
            central.upload_once(central.identity(self.environ, self.root), 'token',
                                central.bundle(self.root, self.output), lambda v: None, http)
        self.assertEqual(1, len(http.requests))

    def test_upload_http_failure_never_retries_even_when_status_would_be_retryable(self):
        self.stage()
        http = HttpFixture(urllib.error.HTTPError(central.API, 503, 'private-body', {}, io.BytesIO()))
        saved = []
        with self.assertRaisesRegex(central.PublicationError, 'uncertain'):
            central.upload_once(central.identity(self.environ, self.root), 'token',
                                central.bundle(self.root, self.output), lambda v: saved.append(copy.deepcopy(v)), http)
        self.assertEqual(1, len(http.requests))
        self.assertEqual('UPLOAD_UNCERTAIN', saved[-1]['status'])

    def test_only_published_with_all_coordinates_is_success(self):
        central.verify_release(status(), VERSION)
        for response in (status(deploymentName='Other release'), status(purls=[]),
                         status(purls=[f'pkg:maven/{central.GROUP}/promptjig-core@0.1.0'])):
            with self.subTest(response=response), self.assertRaises(central.PublicationError):
                central.verify_release(response, VERSION)

    def test_status_identity_duplicate_json_and_oversized_responses_are_rejected(self):
        cases = [status(deploymentId='another-id'), status('UNKNOWN'),
                 b'{"deploymentId":"one","deploymentId":"two"}', b'x' * (central.MAX_RESPONSE + 1)]
        for response in cases:
            with self.subTest(response=str(response)[:90]), self.assertRaises(central.PublicationError):
                central.read_status(ID, 'token', 30, HttpFixture(response))

    def test_poll_retries_status_only_and_validation_is_not_yet_success(self):
        saved, now = [], [0]
        def sleep(seconds):
            now[0] += seconds
        http = HttpFixture(urllib.error.HTTPError(central.API, 503, 'fixture', {}, io.BytesIO()),
                           status('VALIDATING'), status('VALIDATED'), status('PUBLISHED'))
        state = {**central.identity(self.environ, self.root), 'deploymentId': ID}
        central.poll(state, 'token', lambda s: saved.append(copy.deepcopy(s)),
                     opener=http, clock=lambda: now[0], sleep=sleep)
        self.assertEqual(['VALIDATING', 'VALIDATED', 'PUBLISHED'], [s['status'] for s in saved])
        self.assertTrue(all('/status?id=' + ID in r.full_url for r in http.requests))

    def test_poll_timeout_or_failed_validation_keeps_id_and_does_not_upload(self):
        state = {**central.identity(self.environ, self.root), 'deploymentId': ID}
        with self.assertRaisesRegex(central.PublicationError, 'timed out'):
            central.poll(state, 'token', lambda v: None, timeout=0, opener=HttpFixture())
        self.assertEqual(ID, state['deploymentId'])
        self.assertEqual('WAIT_TIMEOUT', state['status'])
        with self.assertRaisesRegex(central.PublicationError, 'validation failed'):
            central.poll(state, 'token', lambda v: None, opener=HttpFixture(status('FAILED')))

    def test_authentication_errors_do_not_retry_or_print_response_bodies(self):
        http = HttpFixture(urllib.error.HTTPError(central.API, 401, 'fixture-secret', {}, io.BytesIO(b'private')))
        with self.assertRaisesRegex(central.PublicationError, 'HTTP 401') as error:
            central.poll({'deploymentId': ID}, 'token', lambda v: None, opener=http)
        self.assertEqual(1, len(http.requests))
        self.assertNotIn('private', str(error.exception))

    def test_resume_requires_no_signing_keys_bundle_or_reupload(self):
        environ = {**self.environ, 'MAVEN_DEPLOYMENT_ID': ID, 'GITHUB_RUN_ATTEMPT': '2'}
        with patch.object(central, 'bundle', side_effect=AssertionError('Resume must not bundle')):
            central.publish(environ, self.root, self.output, opener=HttpFixture(status()))
        state = json.loads((self.output / 'state.json').read_text())
        self.assertEqual('PUBLISHED', state['status'])
        self.assertEqual(ID, state['deploymentId'])
        self.assertNotIn('fixture-token', json.dumps(state))

    def test_full_upload_records_one_deployment_and_waits_for_all_published_artifacts(self):
        self.stage()
        http = HttpFixture(ID.encode(), status('VALIDATING'), status())
        real_poll = central.poll
        with patch.object(central, 'poll', side_effect=lambda *a, **kw: real_poll(*a, **kw, sleep=lambda _: None)):
            central.publish(self.environ, self.root, self.output, opener=http)
        state = json.loads((self.output / 'state.json').read_text())
        self.assertEqual('PUBLISHED', state['status'])
        self.assertEqual(1, sum('/upload?' in r.full_url for r in http.requests))
        self.assertEqual(2, sum('/status?' in r.full_url for r in http.requests))
        self.assertEqual(VERSION, state['version'])
        self.assertEqual(SHA, state['commit'])
        self.assertEqual(hashlib.sha256((self.output / 'bundle.zip').read_bytes()).hexdigest(), state['bundleSha256'])
        self.assertNotIn('fixture-token', json.dumps(state))

    def test_new_upload_requires_credentials_and_cannot_rerun_or_overwrite_state(self):
        for environ in ({**self.environ, 'GITHUB_RUN_ATTEMPT': '2'},
                        {**self.environ, 'MAVEN_CENTRAL_PASSWORD': ''},
                        {**self.environ, 'MAVEN_DEPLOYMENT_ID': 'invalid'}):
            with self.subTest(environ=environ), self.assertRaises(central.PublicationError):
                central.publish(environ, self.root, self.output, opener=HttpFixture())
        central.save({'status': 'UPLOAD_STARTING'}, self.output / 'state.json')
        with self.assertRaisesRegex(central.PublicationError, 'state exists'):
            central.publish(self.environ, self.root, self.output, opener=HttpFixture())

    def test_recovery_preflight_rejects_reruns_or_bad_ids_before_any_upload(self):
        self.assertEqual('', central.recovery_input({}))
        self.assertEqual(ID, central.recovery_input({'MAVEN_DEPLOYMENT_ID': ID, 'GITHUB_RUN_ATTEMPT': '2'}))
        for environ in ({'GITHUB_RUN_ATTEMPT': '2'}, {'GITHUB_RUN_ATTEMPT': 'zero'},
                        {'MAVEN_DEPLOYMENT_ID': '../escape'}, {'MAVEN_DEPLOYMENT_ID': ID.upper()}):
            with self.subTest(environ=environ), self.assertRaises(central.PublicationError):
                central.recovery_input(environ)


if __name__ == '__main__':
    unittest.main()
