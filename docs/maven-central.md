# Maven Central releases

The published runtime library version is **0.2.0**. Its two coordinates are:

- `io.github.sjacek.promptjig:promptjig-core:0.2.0`
- `io.github.sjacek.promptjig:promptjig-spring-ai:0.2.0`

Both were published on 2026-11-13.
[The successful release workflow](https://github.com/sjacek/promptjig/actions/runs/43416483800)
confirmed `PUBLISHED`; public payloads, SHA-256 and GPG signatures were checked.
Plugin Portal distribution is independent;
publishing the Gradle plugin does not publish these Maven coordinates. The 0.2.0 release did not contain a Maven build adapter.
The next, **unreleased 0.3.0** candidate is read from `versions.promptjig` in
`gradle/libs.versions.toml` and stages three artifacts: both runtime libraries
and `promptjig-maven-plugin`. The standalone Spring AI pilot continues to pin
public runtime 0.2.0 through the separate `promptjigRuntime` catalog version.

## Verify locally without publisher credentials

With an authoring JDK (CI uses 25) and Python 3.12+:

```powershell
.\gradlew.bat :gradle-plugin:workflowTest :core:publishAllPublicationsToStagingRepository :spring-ai:publishAllPublicationsToStagingRepository :maven-plugin:publishAllPublicationsToStagingRepository --dependency-verification=strict
python -B scripts/central_publication.py verify-staging
python -B -m unittest discover -s scripts -p test_central_publication.py -v
```

Staging is local under each module's `build/staging-repository`; it does not
contact Central. The verifier checks coordinates, required POM metadata, matching
core/runtime versions, Gradle module identities, JAR contents, MIT licenses,
sources and Javadocs. Ordinary builds do not read signing credentials.

CI additionally runs a real signing test on Linux with GnuPG and a disposable
passphrase-protected key in a temporary GPG home:

```bash
python3 -B -m unittest discover -s scripts -p 'test_central_signing.py' -v
```

This invokes the actual signed Gradle publications, verifies all fifteen detached
signatures with GPG and inspects the ZIP for private-key material. It never uses
the publisher's keyring or uploads anything. Its signed outputs are test outputs,
not release artifacts. Every signing invocation generates fresh signatures.

## Configure the release environment

Create/protect GitHub environment `maven-central` with these secrets:

| Secret | Purpose |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal user-token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password |
| `MAVEN_GPG_KEY` | ASCII-armored private signing key, with actual newlines |
| `MAVEN_GPG_PASSPHRASE` | Nonempty key passphrase |

Use a Portal user token, not the account password. Verify that the publishing
account owns the namespace covering `io.github.sjacek.promptjig`. Make the public
signing key discoverable as required by Central before release. Do not put
credentials in the repository, command-line arguments or publication artifacts.
This change does not create secrets, export the owner's key or change account
settings.

Only the signing step receives private-key secrets. Gradle uses its built-in
Signing plugin with in-memory keys, configuration cache disabled, no daemon
reuse and no build-cache restore/save. Central token secrets reach only the
Python upload/status step. No publishing home is shared through dependency
snapshots. The workflow artifact retains only `state.json` for recovery.

## Publish a reviewed tag

1. Have the owner commit/push the prepared changes and wait for **Compatibility**
   to pass for that exact commit. The new workflow must also be present on the
   repository's default branch before GitHub accepts manual dispatches.
   Codex does not commit or push.
2. Have the owner create/push the immutable matching tag, for example `v0.3.0`.
3. Dispatch **Publish Maven Central artifacts** with that tag and an empty
   `deployment_id`. A tag/push alone never uploads.

The first job checks the tag against the catalog version, resolves the tag and
HEAD separately, rejects tracked/index changes, and requires the latest
Compatibility run on that SHA to have all eighteen required jobs successful,
including all eight Gradle/JDK and six Maven/JDK pairs. A skipped job is not accepted. This uses
the read-only GitHub Actions token; it needs no publisher secrets.

The protected publication job checks out the verified SHA rather than resolving
the tag again. It reruns acceptance/staging checks without credentials, signs the
two libraries and the Maven adapter into separate `build/central-staging` repositories and creates a
90-file bundle: JAR, sources, Javadocs, POM and Gradle module metadata for all three
publications, their PGP signatures, and MD5/SHA-1/SHA-256/SHA-512 sidecars. The exact
file allowlist excludes repository metadata, caches, work directories and keys.
MD5/SHA-1 serve Central's transport requirements; dependency verification remains
strict SHA-256 and no trusted sums are generated or changed by this procedure.

One authenticated upload requests `AUTOMATIC` publication. The workflow stores
the returned deployment ID immediately and polls until `PUBLISHED`, checking the
deployment name and all three Maven coordinates. `VALIDATED` alone is not success.
Verify public resolution of the new coordinates after publication before updating
either pilot to consume the release. Publication does not activate production
runtime in `tichodroma-svc`.

## Recover without repeating an upload

Upload is never automatically retried. Transient failures of **status reads**
are retried with bounded backoff under a 30-minute deadline; authentication,
identity and validation errors stop immediately. Credentials are sent only to
fixed HTTPS endpoints; redirects and ambient proxies are disabled.

For a timeout or failed job after upload, download the
`central-publication-<run-id>-<attempt>` artifact and inspect `state.json` and
the deployment in Central. Dispatch again with the same tag and its canonical
UUID as `deployment_id`. This skips rebuilding, signing and uploading; it needs
only the Central user token and rechecks the release identity and green CI.
The same status-only path handles a deployment already marked `PUBLISHED`.

A GitHub rerun without an explicit deployment ID is rejected before upload.
A local state file also prevents a second fresh upload. If the upload response
was lost, a crash occurred before the ID was recorded, or an unrelated new
dispatch has an empty ID, check Central manually first. Absence of a saved ID
does not prove the server rejected the bundle. Do not start a new upload merely
because the previous job failed. Failed validation requires inspecting Central's
diagnostics; published coordinates are immutable.

## References and verification limits

The implementation follows Sonatype's
[bundle requirements](https://central.sonatype.org/publish/requirements/),
[Publisher API](https://central.sonatype.org/publish/publish-portal-api/),
[Portal tokens](https://central.sonatype.org/publish/generate-portal-token/) and
Gradle's [Signing plugin](https://docs.gradle.org/current/userguide/signing_plugin.html).
GitHub documents the default-branch requirement for
[manual workflow dispatch](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).
The existing ShardPilot deployment recovery was used as the polling/recovery
reference, adapted to Gradle library publications instead of Maven deploy.

Local tests establish package generation, real signing, guards and recovery
against controlled transports. They do not establish namespace permissions,
production-key acceptance, configured GitHub secrets or an actual Central upload.
The updated CI/workflow must run after the owner's push; first publication and
public dependency resolution remain separate checks.

Before stage 4, local verification on 2026-11-13 passed 29 protocol/package tests, 31 workflow
tests and the real Gradle/GPG signing test in Ubuntu WSL. The normal offline
`check assemble :core:spike` build and configuration-cache reuse also passed;
unchanged tasks reused existing outputs. All three workflows passed actionlint
1.7.12. Windows protocol checks passed with the physical-symlink case skipped
for missing OS privileges; that case passed on Linux.
See [the preparation evidence](evidence/central-preparation-2026-11-13.json).
The [stage-4 report](stage4-report.md) records preparation of the third publication,
the Maven adapter, for the unreleased 0.3.0 candidate.
