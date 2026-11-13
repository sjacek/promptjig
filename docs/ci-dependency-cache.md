# CI dependency snapshots

Compatibility prepares downloads once before starting eight Gradle/JDK
and six Maven/JDK combinations, plus the runtime, packaging and example jobs. Each consumer
downloads the `ci-dependencies` artifact from its own workflow run. A pull request,
including one from a fork, does not need publishing secrets or cache write access.

## What is shared

`gradle/ci-dependencies.json` declares Gradle 9.0.0, 9.1.0 and 9.9.1 with their
official distribution SHA-256 values, and the three checked-in Gradle examples.
The producer uses JDK 25; the preparation fixture uses JDK 21, which all three
consumer Gradle versions support. The JDK 17/21/25 Gradle matrix is unchanged.
The same policy pins Maven 3.9.16 and 4.0.0-rc-7 ZIPs for native acceptance on
JDK 17/21/25; Maven 4 is a preview target.

The archive contains only:

- External JAR, POM and Gradle module metadata downloads from the producer's
  `modules-2` cache and a separate TestKit `modules-2` cache.
- Resolver metadata for those caches, including the formats used by the supported
  Gradle versions. This is resolver state, not an authorization to trust bytes.
- Pinned Gradle distribution ZIPs. Every consumer verifies their SHA-256 and
  extracts fresh installations. Extracted tools and Wrapper `.ok` markers are
  never restored from a shared cache.
- External Maven JAR/POM downloads, pinned Maven distribution ZIPs and the
  independently pinned native checksum-verification extension. Extracted Maven
  executables are recreated from the verified ZIP on every use.

Preparation resolves every resolvable producer configuration and the examples
`gradle`, `generation` and `runtime`. Its isolated TestKit fixture resolves the
host Jackson 3 dependency used by the functional tests with each consumer Gradle
version. JUnit Jupiter belongs to the producer/example tests and is resolved
there; Promptjig does not use Spock or TestNG fixtures.

Preparation runs dependency resolution and compiles the TestKit helper when
needed. It does not execute tests, apply the plugin to functional-test fixtures,
start containers, call a model, or invoke verification hooks. Normal consumers
still execute all existing tests and example checks after preparation succeeds.

`mavenProjects` declares the Invoker fixtures and standalone Maven example.
Preparation renders the integration driver with pins from `libs.versions.toml`
and separately resolves the actual standalone example. It resolves core through
its Gradle-generated POM in a private, metadata-only repository, so dependency
mediation matches a real plugin class realm without building a core JAR. The
temporary policy hashes that exact POM; only reviewed external downloads are
promoted to the shared repository. This covers conflict-losing POMs too, and
prevents the core BOM from overriding consumer-selected versions.
Build plugins, their dependencies, the Invoker Groovy hook engine and the JUnit
Platform provider are resolved with `dependency:go-offline`, `dependency:resolve`
and `dependency:get`. Preparation does not
execute fixture hooks, test phases, compilation or plugin installation. A new
authored POM outside the declared roots is rejected. Actual acceptance clones
fixtures into private workspaces, stages the current core/adapter only there,
and runs all fixtures and the standalone example offline.

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
Maven settings/checksum policy, bootstrap scripts, Invoker inputs, Maven example
sources, and the workflow/composite action configuration. A restore prefix with the same
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

Maven uses `.mvn/checksums.sha256` for exact external JAR/POM coordinates and
`security/dependency-verification.json` to pin its bootstrap extension. Native
enforcement checks SHA-256 for **local/restored artifacts as well as downloads**,
including snapshots. Current candidate bytes are hashed into an ephemeral
per-run policy, outside the shared snapshot. No whole group is trusted. Unused
Maven coordinates absent from current policy are omitted when restoring older
snapshots and resolved again if needed. Corrupt known bytes fail restore.
The harness supplies explicit clean settings and never uses the ambient
`~/.m2/repository`. POSIX workspace paths must not contain whitespace because
Maven expands JVM options through its shell launcher.

Preparation and the two corruption probes select
`-Daether.dependencyCollector.impl=df`. With the pinned Maven 4 preview,
the checksum extension's fatal POM rejection can leave a parallel collector's
future unfinished. Serial traversal propagates that rejection to the CLI.
Resolver documents [equivalent dependency results for both collectors](https://maven.apache.org/resolver/configuration.html).
Normal Invoker acceptance retains Maven's default collector, including the
`-T2` reactor scenario. The probes have a 120-second limit; a timeout or an
unrelated failure fails the check and never counts as checksum rejection.
They alter a JAR and a syntactically valid POM, require the native checksum
error for that exact artifact, and check that the goal did not produce a report.

IDE imports also request dependency source JARs. Compatibility preparation now
checks their coverage and independently downloads them on **every** run, including
exact cache hits, comparing bytes with the reviewed SHA-256 values. The gate covers
every binary coordinate in the baseline, including classifier-only dependencies,
plus sources and module metadata for the Groovy libraries discovered in the running
Wrapper distribution. It catches dependency/plugin and Wrapper updates before the
matrix can succeed with an incomplete IDE policy. Native strict verification remains enabled.

After reviewing an intended dependency/plugin version and its binary/metadata
baseline, or updating the Wrapper, run from the repository root:

```shell
python3 -B scripts/ide_dependency_verification.py update
python3 -B scripts/ide_dependency_verification.py check --verify-downloads
```

Use `python` on Windows. The updater downloads missing artifacts from Maven Central
or the Gradle Plugin Portal, checks a published checksum sidecar, and adds SHA-256
entries to `gradle/verification-metadata.xml`. It preserves existing checksums;
neither local resolver caches nor CI may approve new hashes. Review the policy diff
and `build/ide-dependency-verification/review.json` with the version update. A sidecar
checks repository consistency, not publisher identity. The Wrapper still configures
the build in strict mode, so new binary/plugin checksums must be reviewed first.

The only source-availability records allowed in `gradle/ide-source-availability.json`
are exact source coordinates confirmed absent with HTTP 404 from both repositories,
bound to the reviewed binary SHA-256. This accommodates the empty Guava
`listenablefuture` placeholder; it never adds a native trust exception. Network,
authentication, rate-limit and server failures are not accepted as absence. If the
sources are later published, the online check fails until their checksum is reviewed.
CI cannot run the update command. For a local coverage check without source downloads,
use `check` without `--verify-downloads` (ordinary Wrapper dependency resolution remains).

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

For Maven failures, check `maven-plugin/build/reports/maven/<Maven>-jdk-<JDK>`
and its native Invoker reports. A missing checksum requires maintainer review
against the official repository before adding it to `.mvn/checksums.sha256`;
there is no collect mode or automatic trust update. `verify` is offline, so a
missing artifact there means preparation did not cover an actual build input.
Review the POM/catalog and warmup together. Never fix it by disabling native
verification, reusing `~/.m2` or ignoring a failed fixture.

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

For the Maven adapter's preparation, fresh snapshot restore and six native
Maven/JDK combinations, see the [stage-4 report](stage4-report.md).
