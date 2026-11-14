# SBOM release attachments

Maven Central **0.3.0** includes the audited CycloneDX 1.6 JSON attachments.
The published Portal **0.3.0** plugin is usable but is missing its separate SBOM
JAR: the publisher selected only files that existed, and its task graph did not
build that classifier. The **unreleased 0.3.1** patch fixes that dependency for
upload and `--validate-only`. Existing versions remain immutable; no attachments
are added retroactively. Portal 0.1.0 and Central 0.2.0 also have no SBOM attachments.
This is publication of inventory documents, not an SBOM attestation or a claim
that a distribution contains no vulnerabilities.

## Registry formats

Maven Central receives four additional JSON artifacts for each of
`promptjig-core`, `promptjig-spring-ai` and `promptjig-maven-plugin`:

| Scope | Classifier | File suffix |
| --- | --- | --- |
| runtime | `cyclonedx` | `-cyclonedx.json` |
| build | `cyclonedx-build` | `-cyclonedx-build.json` |
| test | `cyclonedx-test` | `-cyclonedx-test.json` |
| provided | `cyclonedx-provided` | `-cyclonedx-provided.json` |

Each JSON is a separate signed payload, with `.asc`, `.md5`, `.sha1`, `.sha256`
and `.sha512` sidecars in the release bundle. SHA-256/SHA-512 are suitable for
integrity comparison; MD5/SHA-1 exist only for repository protocol compatibility.
Three modules now produce **27 payloads / 27 signatures / 162 bundle files**.
See [Central requirements](https://central.sonatype.org/publish/requirements/)
and [Gradle attachment semantics](https://docs.gradle.org/current/userguide/publishing_customization.html).
Classifiers are requested explicitly; they do not change application dependency
selection or add build/test/provided dependencies to the runtime classpath.

The Plugin Portal path uses a separate implementation artifact,
`promptjig-gradle-plugin-<version>-sbom.jar`, rather than JSON classifiers. Its
only payloads are `META-INF/sbom/{runtime,build,test,provided}.cdx.json`, a small
cohort manifest and the normal JAR manifest/MIT license. The POM-only plugin
marker remains a pointer to the implementation and receives no SBOM artifacts.
No extra Portal PGP signing is introduced.

The reviewed plugin-publish **2.2.1** client collects all implementation
publication artifacts, including classifier JARs. The offline
`verifyPortalPublicationArtifacts` task exercises that exact selection method
without running its network/upload action or reading publishing credentials.
That collector check alone cannot detect a missing producer if staging already
built the archive. The 0.3.1 regression additionally invokes the real
`publishPlugins` task after deleting the classifier, in both publisher modes
and with configuration-cache reuse. Only the upload action is replaced; native
SBOM verification and JAR production execute unchanged. Missing or modified
audited inputs must stop the task before request collection, even with a cached JAR.
These checks verify the pinned client's request preparation; actual registry
availability/acceptance is confirmed only after an authorized publication.
The [CycloneDX publisher guide](https://github.com/CycloneDX/cyclonedx-gradle-plugin#publishing-the-direct-sbom)
also documents the Portal JSON-classifier limitation.

The main plugin JAR is unchanged. Its hash is stored in each SBOM and the
separate SBOM JAR's manifest. Keeping the documents outside the main JAR avoids
an impossible self-hash cycle and lets consumers verify the exact plugin bytes.
Central signing uses per-module working copies under
`build/publication-sbom-inputs/`, so generated `.asc` files cannot modify or
pollute the portable audited set. The signing test revalidates that set afterward.

## From successful CI to release

1. `dependency-security` resolves and scans all four scopes of all four modules.
   `publication_sboms.py export` revalidates every native graph, candidate hash,
   source input and scoped scan against the successful aggregate/positive control.
2. The job exports the unchanged 16 JSON documents plus portable manifests under
   `build/publication-sboms/`. The manifests contain relative source names and
   hashes, never local cache paths, credentials, model inputs or scanner workspaces.
3. Before staging can hide a missing producer, CI exercises clean Portal
   publication through `test_portal_publication.py`, without credentials or upload.
   It then stages all attachments, checks Central/Portal contents and runs real Gradle
   signing with a disposable GPG test key. Only after success does it upload the
   `publication-sboms` artifact for that run, with 30-day retention.
4. Each release guard returns the exact accepted Compatibility run ID. The
   `restore-publication-sboms` composite action downloads that run's named
   artifact. It never searches for a branch's latest artifact or accepts an
   arbitrary user-supplied run ID.
5. After rebuilding the reviewed release, verification checks the current Git
   SHA, complete source hash set, module/version/scope, JSON hash/graph/serial
   and actual main JAR SHA-256. Changed, missing, foreign, duplicate, symlinked or
   unexpected files fail before signing/upload. Central's final bundler also
   verifies that all 12 JSON attachments describe its actual staged JARs and
   the release commit.

A successful regenerated set may replace the named release-input artifact on a
job retry. Failed raw security evidence keeps its separate run/attempt artifact
name. A failed scan/export/signing job cannot authorize a release, even if an
older artifact still exists. Expired or missing release inputs require a new
successful Compatibility run; there is no unverified regeneration during release.
Status-only Central recovery does not download SBOMs, rebuild, sign or reupload
an already accepted deployment.

## Local verification without publishing

First prepare dependencies and the complete current candidate inventory/scans
as described in [dependency security](dependency-security.md). Then:

```bash
python3 -B scripts/publication_sboms.py export --commit "$(git rev-parse HEAD)"
python3 -B -m unittest discover -s scripts -p 'test_portal_publication.py' -v
./gradlew :core:publishAllPublicationsToStagingRepository :spring-ai:publishAllPublicationsToStagingRepository :maven-plugin:publishAllPublicationsToStagingRepository :gradle-plugin:publishAllPublicationsToStagingRepository -PpublicationSboms=true --offline --dependency-verification=strict --configuration-cache --configuration-cache-problems=fail
python3 -B scripts/central_publication.py verify-staging
python3 -B scripts/publication_sboms.py verify-portal
./gradlew :gradle-plugin:verifyPortalPublicationArtifacts -PpublicationSboms=true --offline --dependency-verification=strict --no-configuration-cache
```

Use `gradlew.bat` on Windows. Set `PROMPTJIG_PYTHON` to the full path of a suitable
Python 3.11+ executable if `python` on PATH is unsuitable. The local staging task
supports configuration cache; the explicit Portal client-inspection task reads
a live publisher model and intentionally does not. No command above uploads.
The regression uses an init script under `testing/publication/` to replace the
publisher's actions with a check of its actual selected files and archive bytes.
Its artifact inputs, options, producer dependencies and strict verification stay intact;
an unreachable loopback URL provides an additional safeguard against upload.
It restores intentionally damaged audited inputs in `finally` blocks. Run it
serially against the prepared set; do not run a release concurrently in that tree.

Bootstrap staging without `-PpublicationSboms=true` remains available while
warming/building the candidate before native inventories exist. It is not a
release: signed Central staging always enables/requires SBOMs, and
`publishPlugins` rejects a call without SBOM attachment mode. Central's bundler
does not accept signed bootstrap output without the complete attachments.

The Linux real-signing smoke test now runs after export, in the security job.
Run `test_central_signing.py` locally only after preparing the complete SBOM set.
Its disposable signatures are test output, never a publication authorization.

For a mismatch, inspect `build/publication-sboms/manifest.json`, module manifests,
the original `build/security/native/` and per-scope reports. Rebuild/reinventory
the real candidate; do not edit SBOM hashes, suppress the check, or substitute
documents from a different run. Full source/build/toolchain boundaries remain
as documented in [the security review](security-review-2026-11-13.md).
