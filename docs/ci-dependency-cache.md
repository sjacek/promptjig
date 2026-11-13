# CI dependency snapshots

Compatibility prepares downloads once before starting the eight Gradle/JDK
consumer combinations and the runtime, packaging and example jobs. Each consumer
downloads the `ci-dependencies` artifact from its own workflow run. A pull request,
including one from a fork, does not need publishing secrets or cache write access.

## What is shared

`gradle/ci-dependencies.json` declares Gradle 9.0.0, 9.1.0 and 9.9.1 with their
official distribution SHA-256 values, and the three checked-in Gradle examples.
The producer uses JDK 25; the preparation fixture uses JDK 21, which all three
consumer Gradle versions support. The existing JDK 17/21/25 matrix is unchanged.

The archive contains only:

- External JAR, POM and Gradle module metadata downloads from the producer's
  `modules-2` cache and a separate TestKit `modules-2` cache.
- Resolver metadata for those caches, including the formats used by the supported
  Gradle versions. This is resolver state, not an authorization to trust bytes.
- Pinned Gradle distribution ZIPs. Every consumer verifies their SHA-256 and
  extracts fresh installations. Extracted tools and Wrapper `.ok` markers are
  never restored from a shared cache.

Preparation resolves every resolvable producer configuration and the examples
`gradle`, `generation` and `runtime`. Its isolated TestKit fixture resolves the
host Jackson 3 dependency used by the functional tests with each consumer Gradle
version. JUnit Jupiter belongs to the producer/example tests and is resolved
there; Promptjig does not use Spock or TestNG fixtures.

Preparation runs dependency resolution and compiles the TestKit helper when
needed. It does not execute tests, apply the plugin to functional-test fixtures,
start containers, call a model, or invoke verification hooks. Normal consumers
still execute all existing tests and example checks after preparation succeeds.

Promptjig currently has **no Maven build, Maven adapter or Maven Invoker
projects**. There are no Maven dependencies to warm or export. Unlike ShardPilot,
this implementation therefore does not install a Maven version, fabricate POMs
or copy Maven settings. The policy rejects an authored `pom.xml` or a nonempty
`mavenProjects` declaration until verified Maven resolution and Invoker warmup
are implemented deliberately. Maven support cannot silently bypass preparation.

Project classes, locally built plugin publications, TestKit project directories,
Invoker workspaces, test results, reports, build caches, configuration cache,
daemon state, init scripts, Gradle properties, Maven settings, signing keys and
publishing credentials are excluded. The allowlist in
`scripts/ci_dependency_cache.py` governs both export and restore.

## Cache keys and lifetime

Only `warm-cache` reads the cross-run GitHub cache. Its key is
`dependencies-v1-<OS>-<architecture>-<dependency-input-hash>`. The hash includes
build scripts, version catalogs, Gradle properties, wrappers, the verification
policy, the distribution/example manifest, warmup scripts and TestKit fixtures,
and the workflow/composite action configuration. A restore prefix with the same
schema version, OS and architecture allows an older snapshot to supply unchanged
downloads. Preparation always resolves missing dependencies, even on an exact hit.

Only a successful `push` run on the canonical repository's default branch may
save a new cross-run key. Matrix jobs never save it; Gradle's separate action
cache is disabled, including in the release workflow. Keys are immutable, so
concurrent default-branch runs may encounter an already-created key without
replacing its contents. Every successful preparation uploads a fresh same-run
artifact, retained for one day, regardless of cross-run write permissions.

Changing an included dependency input selects a new key automatically. Bump the
`dependencies-v1` prefix when changing the snapshot format or compatibility
boundary. To discard a damaged snapshot without a source change, delete that
key in GitHub Actions caches and rerun. A checksum mismatch is a failure and
must not be hidden by falling back to unverified downloads.

## Verification and trust

`gradle/verification-metadata.xml` is the reviewed SHA-256 baseline, including
POM/module metadata. The initial baseline was checked against Maven Central and
Gradle Plugin Portal. No shared cache can extend it. The cache scripts verify
known artifact bytes during export and restore; Gradle's native strict
verification also checks requested dependencies during actual builds. Unused
versions from an older snapshot may be restored as candidates, but do not become
trusted and are omitted from the next exported archive.

IDE imports also request dependency source JARs. Their SHA-256 values are pinned
individually in the same policy so IntelliJ can import the Gradle model with
strict verification enabled. When upgrading dependencies, review available
source artifacts as well as binary/metadata artifacts. CI preparation does not
need to download optional IDE sources to run its builds and tests.
This also covers source/module artifacts for the Groovy libraries bundled with
the authoring Gradle SDK, fetched by IntelliJ from Gradle's own repository.

The reviewed policy is copied into isolated example builds and every TestKit
fixture. These example copies are generated and ignored by Git. The publication
test additionally hashes only the exact current candidate's local staged files
into its temporary fixture policy. This permits testing the newly built plugin
without trusting its entire group or putting its publications in the snapshot.

There is no CI command that writes verification metadata. When intentionally
updating a dependency, maintainers must collect proposed hashes locally, compare
each new artifact with its official repository through an independent download
or checksum, inspect the policy diff and run strict checks before committing.
Never approve a changed checksum solely because it came from a restored cache.
Gradle's verification behavior is described in its
[dependency verification guide](https://docs.gradle.org/current/userguide/dependency_verification.html).

Archive restore rejects traversal, absolute paths, links, duplicate/case-colliding
names, unexpected content and oversized entries before writing to the live
cache. Verified files are staged and replaced individually; existing hardlinks
cannot redirect writes to an outside file. Distribution ZIP checksums are
validated before extraction, including on cache hits.

## Failures and local diagnosis

Consumers depend on `warm-cache` and explicitly fail when it failed or was
skipped. They do not disappear as successful skipped checks. A missing same-run
artifact also fails restore. Workflow cancellation remains a cancellation.

Check `Prepare verified dependencies` first. Its log identifies distribution
verification and each resolved configuration. A native dependency-verification
failure links to Gradle's detailed report. Investigate changed or missing hashes;
do not disable strict verification or generate trusted sums in CI. For an
artifact-download failure, confirm that preparation completed and the artifact
belongs to the same run. Forks should restore and prepare normally without secrets.
Failed preparation uploads `dependency-preparation-diagnostics` with dependency
verification and problem reports, when available. It is separate from the
download-only snapshot and is never consumed as a dependency cache. No Gradle
user homes, publishing settings or daemon logs are included.
If the one-day artifact has expired, rerun the complete workflow so preparation
can recreate it before consumers restart.

Check a changed verification policy against an empty dependency cache as well
as a restored one. Cached resolver descriptors can avoid re-reading parent POMs,
masking missing parent checksums that a fresh runner needs. The initial cold CI
run exposed this for `jackson-dataformats-text:2.22.4`; its parent POM checksum
was reviewed against Maven Central's SHA-256 and SHA-512. On Windows, a unique
temporary Gradle home exercises fresh producer resolution without removing an
existing cache:

```powershell
$coldGradleHome = Join-Path $env:TEMP ('promptjig-cold-' + [guid]::NewGuid())
$previousGradleHome = $env:GRADLE_USER_HOME
try {
    $env:GRADLE_USER_HOME = $coldGradleHome
    .\gradlew.bat ciResolveDependencies -I scripts/ci_dependency_warmup.gradle --no-configuration-cache --dependency-verification=strict --console=plain
} finally {
    $env:GRADLE_USER_HOME = $previousGradleHome
}
```

The full `warm` command owns its fixed `build/ci-dependencies` homes; run it in a
fresh checkout to test all producer, TestKit and example dependencies cold.

On Windows with the producer JDK selected and JDK 21 installed:

```powershell
python -B -m unittest discover -s scripts -p 'test_ci_dependency*.py' -v
$env:WARMUP_JAVA_HOME = 'C:\path\to\jdk-21'
python -B scripts/ci_dependency_cache.py restore
python -B scripts/ci_dependency_cache.py warm
python -B scripts/ci_dependency_cache.py pack
```

`restore` accepts a missing cross-run snapshot as a cold start. Consumer jobs
use `restore --required` and reject a missing snapshot. `warm` owns only
`build/ci-dependencies`; it does not stop or export a developer's global Gradle
home. `build/ci-tools` holds freshly extracted tools and stays outside the archive.
The Python tests cover archive handling without Gradle or network access. The
Gradle regression test also corrupts a restored artifact and requires native
strict verification to reject it offline.

A successful local warmup/restore establishes the download and verification
behavior on that machine. It does not establish GitHub cache permissions,
artifact transfer or success of every hosted matrix job. After pushing, inspect
the preparation cache-hit/miss, artifact upload/download and test reports for all
eight combinations. The first trusted default-branch run seeds the cross-run
cache; a subsequent run confirms its actual reuse.

## Local cold-cache regression, 2026-11-13

The missing Jackson parent POM failure was reproduced in Ubuntu WSL with an
empty resolver home and the new entry removed. After restoring the reviewed
entry, the complete warmup passed with new producer and TestKit resolver homes.
Only the pinned Gradle ZIPs were seeded; no existing module metadata or project
outputs were imported into that cold preparation.

The resulting snapshot contained 386 downloaded files. Restoring it into a new
home passed seven offline resolution checks: producer, three TestKit Gradle
versions and three examples. All 20 cache security tests passed on Linux,
including physical symlinks. The eight supported Gradle/JDK pairs passed all
160 test executions with configuration cache enabled and no skipped cases.
Full packaging, Java 17 core/runtime checks, the examples and the packaged
synthetic runtime demo also passed. Windows workflow tests passed all 26 cases;
actionlint 1.7.12 accepted both workflows.

These are local results, recorded in
[the verification evidence](evidence/ci-cold-cache-2026-11-13.json).
The original [hosted run](https://github.com/sjacek/promptjig/actions/runs/43321358718)
failed before the fix. A new hosted run after the owner's commit/push must still
confirm artifact transfer, all required jobs and subsequent cross-run cache reuse.

## Published Promptjig consumers

The standalone runtime pilot resolves core/Spring AI 0.2.0 from Maven Central
and the independent Gradle plugin 0.1.0 from Plugin Portal. Warmup resolves those
external releases too. `publishedModules` in `gradle/ci-dependencies.json` is an
exact coordinate allowlist for downloaded Promptjig release files; each file
still requires its reviewed SHA-256 from `verification-metadata.xml`. Project
outputs, snapshots and other locally built Promptjig artifacts remain excluded.
Changing the catalog, checksum policy or allowlist invalidates the cache key.

Regression tests exercise JAR/POM/module payloads in both Gradle and TestKit
homes. They reject forged payloads before changing any live files, exclude
unreviewed classifiers and other project versions, and require exact release
coordinates. Warmup tests also require the runtime example to resolve without
source substitution and propagate a failed resolution before subsequent work.

`RuntimePilotSettingsTest` executes the authored example settings and catalog in
minimal offline TestKit consumers, without injecting the producer plugin. It
guards the default source-free mode and explicit substitution of all three
modules, including configuration-cache reuse. These settings tests run in the
existing Gradle/JDK consumer matrix; the full runtime pilot separately verifies
the actual public library binaries and application behavior.

The [published-consumer verification](evidence/published-runtime-consumer-2026-11-13.json)
records a complete warmup and a 400-file snapshot. An isolated Java 17 consumer
passed all 78 tests offline after restoring it, without producer sources. All
21 cache tests passed on Linux, including rejection of locally rebuilt bytes
under an allowlisted release coordinate. The 321 existing artifact checksums
were retained; ten reviewed public-release artifacts were added explicitly.
