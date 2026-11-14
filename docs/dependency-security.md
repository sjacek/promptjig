# Dependency security and release evidence

Promptjig has four Gradle-produced distributions: core, Spring AI, the Gradle
plugin and the Maven plugin. Maven 3.9.16/4.0.0-rc-7 are real consumer/Invoker
targets. There is no native Maven producer lifecycle and no Maven Site/Jetty
exception copied from another project.

## Blocking candidate inventories

`security_inventory.py` resolves actual Gradle models and native Maven consumer
models without running tests, applications, containers or fixture verification
hooks. `security_reports.py` translates the retained models to CycloneDX 1.6.
It does not add a generator plugin or change publication dependencies. Native
component identities/edges, configuration provenance, artifact SHA-256 values
and source input hashes accompany each SBOM. Validation checks graph identity,
reachability and exact regeneration; it is not a full third-party schema or
independent SBOM certification.

Each distribution has four **blocking** scopes:

| Scope | Included dependencies |
| --- | --- |
| runtime | Actual runtime classpath; Gradle also checks the exact nested `META-INF/promptjig/runtime.zip` set and bytes |
| build | Annotation processing and producer buildscript tooling; Maven adapter also includes native Maven compiler/resources/JAR/Invoker/dependency-tool/Surefire realms |
| test | Actual producer test, workflow, spike and optional Networknt 3 configurations; Gradle includes TestKit host graphs on all three supported Gradles and CI examples; Maven includes its native fixture graph and explicitly selected Jupiter provider |
| provided | Resolved compile-only dependencies, including host APIs and annotations |

The Maven dependency plugin reports **flat plugin realm membership**, not full
transitive paths. Those members are connected to their real plugin root and
identified as flat native realm evidence. Active tools are enumerated explicitly;
unexecuted inherited site/release/install tools are excluded from resolution,
not hidden after scanning. The Maven host/JDK and consumer-selected versions
remain the application's/toolchain owner's responsibility. Optional Networknt 3
is a test/host compatibility boundary, not an extra default core runtime.

Gradle API/TestKit/generated SDK JARs retain generic identities instead of invented
Maven coordinates. Reviewed third-party SDK JARs are mapped only when their exact
hash matches `security/gradle-sdk-artifacts.json` and official Maven Central
identity. Generic SDK entries are not a complete scan of Gradle/JDK/OS internals.

The patched descriptor-generator dependencies are pinned in `libs.versions.toml`
and applied to its buildscript classpath only. The initial 34 additional checksums
were independently compared with fresh Maven Central bytes and published sidecars;
the receipt is `security/descriptor-tooling-review.json`. Existing trust entries
were preserved. Two separately verified Maven checksums cover the native resources
plugin's Plexus 3.x patch. Native dependency-tool BeanUtils and Invoker Commons Lang
patches affect their real plugin realms, including warmup and fixtures; the
standalone Maven example uses the same resources patch. Gradlex 1.0.3 retains its compatible Plugin Tools API; these pins
do not force build-tool versions onto a consuming application's runtime.

The subsequent `junit-bom:5.13.1` Gradle `.module` checksum was reviewed separately
after a clean CI resolver requested metadata absent from the earlier trust list.
`security/junit-bom-metadata-review.json` records fresh Central/Portal downloads,
their actual redirect destinations and matching official SHA-256 sidecars. This
adds one exact metadata artifact without changing dependency versions, replacing
old hashes, adding trust exceptions or collecting sums in CI.

The Invoker's archive tooling explicitly selects Snappy **0.5**, the fix for
[CVE-2024-36124](https://github.com/dain/snappy/security/advisories/GHSA-8wh2-6qhj-h7j9).
Its pin is rendered from the same catalog into the non-inherited Invoker plugin
dependencies for warmup, native inventory and acceptance tests. It is not an
application dependency. `security/snappy-tooling-review.json` records official
JAR/POM/source downloads and sidecar comparisons; the three SHA-256 entries are
in `.mvn/checksums.sha256`. The existing Gradle IDE checksum policy is unchanged;
its read-only official-download check also passes. Snappy 0.5 remains compatible
with the supported JDKs (Java 8 bytecode); a future parent-tool upgrade can replace
the legacy decompressor rather than changing consumer runtimes.

## Scanner and evidence validation

`security/scan-policy.json` pins Grype **0.121.3**, all four scopes and a maximum
database age of **120 hours**. Every run refreshes the DB in CI and records the
native scanner version, DB build date/schema/source and validity. Full findings
JSON includes Unknown/Negligible/Low/Medium/High/Critical and unfixed findings.
High/Critical in any blocking scope fail the job, including when no fix exists.
There are no project vulnerability/VEX/package suppressions. Grype's four built-in
non-Java OS-header defaults cannot match these JVM inventories.

A metadata-only Log4j 2.14.1 positive control must produce its known Critical
finding and native blocking exit. No vulnerable JAR is downloaded or executed.
Every scope is attempted after an individual scope error, preserving its JSON,
stderr and exit status. Missing/stale input, changed candidate bytes, malformed
graphs, a different scanner/DB, hidden findings or an inconsistent native exit
cannot produce a passing aggregate. Previous green summaries are invalidated
before work starts. Local reports explicitly record a dirty working tree; a base
commit plus uncommitted input hashes is not hosted CI proof for that commit.

## Integrity, cache and publication

The existing [download-only cache](ci-dependency-cache.md) is retained. Preparation
runs once, then compatibility matrices, scanning and offline acceptance run in
parallel. Forks/PRs consume same-run artifacts without publishing secrets or cache
write permission. Only the trusted default-branch producer writes the shared key.
Configuration cache, daemons, compiled project artifacts, test workspaces and
credentials are not dependencies. Restoring a snapshot never authorizes its bytes.

Gradle's native strict verification checks producer, TestKit and example downloads.
Native Maven's pinned extension enforces SHA-256 on new and restored JARs/POMs,
before goals run. Positive and negative probes require the intended native error:
valid bytes pass, altered JAR/POM bytes fail, and valid bytes without an expected
checksum fail. Ordinary CI never derives or updates trusted checksums.

`security/build-inputs.json` records official Action tag/commit comparisons and
the official Wrapper JAR checksum. All repository-owned workflows/composite
actions use reviewed 40-character SHAs. `dependency_policy.py` rejects moving
refs, unknown actions, changed Wrapper bytes and weakened native policy. The
Wrapper ZIP checksum is also checked against the distribution policy. Pinning
a top-level action does not replace review of its transitive actions/container.

Both Portal and Central publishing workflows require the latest successful Compatibility
run for the **exact tagged SHA**, including every matrix cell and both new jobs,
`dependency-security` and `offline-security` (20 jobs total). Missing, skipped,
pending, failed, duplicate or old job sets reject publication. Publishing remains
manual and credentials remain limited to the publishing steps. GitHub branch/tag/
environment protections require the owner's separate acceptance; see the review.

The compile/test Maven API graph is patched independently of the executable host.
This does **not** replace bundled libraries in a user's Maven or Gradle installation.
For example, stock Maven 3.9.16 contains Guava 33.6.0; the recorded Grype DB rates
GHSA-xxph-c9ww-hj94 High, while the [Guava maintainer advisory](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94)
rates it Moderate and identifies patched 33.7.2 variants. No severity override is
applied to the blocking producer scopes. Compatibility testing of an older host
with trusted synthetic inputs and no publishing credentials is not approval of
that host for untrusted projects. Consumers must patch their toolchains separately;
the [Maven release history](https://maven.apache.org/docs/3.10.0/release-notes.html)
is a separate upgrade source. Full host/JDK/OS distribution audits remain outside
the four plugin/library inventories, rather than being labeled vulnerability-free.

## Reproduce locally

Use JDK 25 for authoring, JDK 21 in `WARMUP_JAVA_HOME`, Python 3.11+, a previously
warmed snapshot and the pinned Grype executable. Native Maven consumers still
support JDK 17/21/25. On Windows use `gradlew.bat` and a suitable Python executable.
Do not supply publishing credentials.

```bash
python3 -B scripts/dependency_policy.py
python3 -B -m unittest discover -s scripts -p 'test_security*.py' -v
python3 -B scripts/security_inventory.py
python3 -B scripts/security_reports.py prepare --commit "$(git rev-parse HEAD)"
python3 -B scripts/security_reports.py scan --scanner /path/to/grype --refresh
```

Changing build/source/security inputs invalidates native evidence. Regenerate
instead of editing an inventory, accepting a previous green summary or weakening
the checksum/scanner policy. A missing dependency requires warmup with the current
reviewed pins; do not turn offline or strict verification off to make acceptance
green. For a new dependency, review its origin, version, bytes and published
checksums separately and commit only the intentional policy delta.

For isolated acceptance, allow several GB of disposable disk space for snapshot
staging and build outputs. `/tmp` is a small memory mount; the runner stages large
files under its controlled `/work/tmp` directory.

```bash
docker build -f testing/security/Dockerfile -t promptjig-security-audit:local testing/security
python3 -B scripts/offline_security.py
```

Reports live under `build/security/reports/` and `build/security/offline/<run>/evidence/`.
`offline/latest.json` identifies the latest attempt. Each offline attempt freezes
its own source/runner snapshot; retries do not overwrite an active or failed run.
CI uploads graphs, SBOMs, all findings, failed logs, raw traces and native test XML.
Security artifact names include run ID and attempt, preserving earlier failures
when a job is retried. Uploads exclude scanner databases and disposable
dependency/test workspaces.
Inspect `failures.json`, per-scope `scanner.log`/`scan-status.json`, the database
identity, `container-policy.json`, native test results and raw traces when diagnosing
a failure. An absent passing aggregate is a failure, not a zero-vulnerability report.

## Published versions are a different cohort

For the 0.3.0 candidate, the complete audited SBOM set is also prepared as
[versioned release attachments](sbom-publication.md): signed classifier JSON
files in Central and a metadata-only classifier JAR for the Portal implementation.
The release workflows restore the set from the exact accepted Compatibility run
and reject source/JAR/hash mismatches rather than silently rebuilding a different
inventory during publication. Older registry versions remain unchanged.

```bash
python3 -B scripts/published_artifact_audit.py
python3 -B scripts/published_dependency_audit.py prepare
python3 -B scripts/published_dependency_audit.py scan --scanner /path/to/grype --database-directory /path/to/grype-db
```

These commands freshly download Portal 0.1.0 and Central core/Spring AI 0.2.0,
verify metadata/sidecars, marker linkage, Java base bytecode, source content against
pinned tag commits and available public signatures. Central has ten verified
payload signatures; Portal files are unsigned. The source tags are a lightweight
0.1.0 tag and an unsigned annotated 0.2.0 tag. Binary reproducibility from a tag
and independent publisher identity certification are not claimed. The Portal source
archive covers its adapter; embedded core 0.1 has no separately published source
archive in this audit and retains a generic internal identity with its actual hash.

Native runtime graphs are tied to fresh JAR hashes; the Portal inventory includes
its actual nested runtime JARs. Historical conflict-losing module metadata absent
from the current candidate policy is explicitly reviewed in a separate supplement,
used only in the isolated published-audit project. Published runtime scans do not
reconstruct historical build/test toolchains and never stand in for candidate
acceptance. Results remain under `build/security/published/`. The Maven adapter
and other 0.3.0 registry audits must be repeated **after** publication; before then
only local packages can be checked.

A clean report means no matching advisories were reported for that inventory and
database at that time. It is not proof that no vulnerabilities exist. Local
acceptance and the previous green main pipeline are not confirmation that these
new controls have executed on GitHub runners.
