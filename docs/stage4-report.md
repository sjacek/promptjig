# Stage 4 — Maven adapter

The Maven adapter is implemented for the **unreleased 0.3.0** candidate in
`feature/maven-plugin`. The source baseline is
`a085c9f4ba458cfa217ba81219e4ea773d725476`. Public runtime libraries remain
0.2.0 and the Portal plugin remains 0.1.0; the Spring AI pilot keeps those pins.

## Delivered behavior

`io.github.sjacek.promptjig:promptjig-maven-plugin` is built by the existing
Gradle producer using `org.gradlex.maven-plugin-development:1.0.3`. The
descriptor is generated from the goals and their documented Maven parameters.
The publication POM supplies the same-version core dependency; Maven host APIs
are `provided` and are not bundled in the plugin JAR.

- `validate` defaults to `validate`, aggregates core findings and writes JSON/text
  reports without changing history.
- `generate` defaults to `generate-sources`, validates first, generates Java 17
  records/enums/constants for Jackson 2 or 3, then registers the source root.
- `lock` has no default lifecycle phase. Explicit invocation appends new versions
  and refuses historical fingerprint changes.
- Every goal accepts an explicit optional baseline selected by the owning
  repository. A configured missing baseline fails; no branch name is imposed.

The adapter delegates contract rules, fingerprints, history protection and Java
generation to core. Required helper parameters use Lombok `@NonNull`; test
fixtures use Lombok builders. The [Maven guide](maven-plugin.md) documents all
paths, options and failure reports. The [standalone Maven example](../examples/maven/README.md)
uses the fictional public-announcement contract and makes no model calls.
The [changelog](../CHANGELOG.md) describes the resulting Maven user workflows.

## Dependency preparation and CI

One preparation job warms the producer, TestKit, Gradle examples, Maven plugins,
Invoker/Groovy/JUnit Platform and the standalone Maven example. Consumer and
plugin dependency graphs are resolved separately: the core BOM must not override
consumer versions, and Maven may need POMs for versions later rejected during
conflict mediation. Core's generated POM is staged as a metadata-only dependency
in a private repository with an exact temporary checksum. Maven resolution uses
no compiled core JAR and runs neither Maven test phases nor Invoker hooks.

The 1,395-file download snapshot was exported and restored into a fresh
consumer cache. Own candidate JARs/POMs, compiled outputs, Invoker/TestKit work
directories, reports, daemon/configuration-cache state, settings and credentials
are excluded. The 331 existing Gradle artifact policies were preserved unchanged;
the policy now contains 623 artifact entries. Maven has its own reviewed
SHA-256 baseline and a pinned native verifier. Neither preparation nor CI
automatically accepts new trusted sums.

CI gains six Maven/JDK jobs. They require successful preparation, restore the
same-run artifact, stage current candidates and execute native acceptance
offline. Cross-run cache writes remain restricted to the canonical default
branch's preparation job. The Central release guard now requires all **18**
Compatibility jobs, including every Maven cell, to execute successfully.

Preparation and corruption probes use serial dependency collection (`df`) to
avoid the pinned Maven 4 preview's unfinished-future behavior after a checksum
extension rejection. Functional Invoker builds keep Maven's default collector;
the parallel reactor runs with `-T2`. Verification stays in enforce mode for
both new downloads and local/restored files. Each corruption probe requires
the exact native checksum error, checks that the goal did not run, restores the
original file and fails on an unrelated error or a 120-second timeout.
See [cache operation and diagnosis](ci-dependency-cache.md).

## Local verification — 2026-11-13

| Native Maven | Consumer JDK | Invoker projects | Model-binding JUnit tests | Result |
| --- | --- | ---: | ---: | --- |
| 3.9.16 | 17 | 14 | 6 | Passed |
| 3.9.16 | 21 | 14 | 6 | Passed |
| 3.9.16 | 25 | 14 | 6 | Passed |
| 4.0.0-rc-7 | 17 | 14 | 6 | Passed |
| 4.0.0-rc-7 | 21 | 14 | 6 | Passed |
| 4.0.0-rc-7 | 25 | 14 | 6 | Passed |

The final Ubuntu WSL matrix ran **84 Invoker project executions and 36
model-binding tests**, with no skipped cases. Every cell also rejected a
modified local JAR and a syntactically valid modified POM. Maven's version
header confirmed the selected Maven/JDK pair. Coverage includes native goal
discovery, default phases, both Jackson targets, automatic source-root
registration, packaged resources, explicit locks, immutable history, baseline
tampering, missing locks, template errors, output containment, custom paths,
empty modules, a two-module parallel reactor and the independent example.

Additional checks passed:

- Windows `check assemble :core:spike`, with strict dependency verification and
  configuration-cache problems treated as failures; the spike was offline.
- Fifteen adapter/packaged-descriptor tests, workflow guards, and Java 17
  core/runtime/adapter tests in WSL.
- Forty-six cache/bootstrap/security tests and thirty publication protocol tests
  on Linux, including physical link handling and fail-closed matrix selection.
- An additional fourteen-project native Maven 3.9/JDK 25 run on Windows and
  native local-artifact corruption checks.
- Three unsigned local publications (15 payloads), plus a real Gradle/GPG test
  verifying 15 signatures with a disposable key and a 90-entry bundle.
- Actionlint 1.7.12 for Compatibility and both manual release workflows;
  whitespace and preservation of the existing checksum policy.

Machine-readable observations are in
[the stage-4 evidence](evidence/maven-plugin-2026-11-13.json). Detailed native
logs and summaries remain under `maven-plugin/build/reports/maven` in the Linux
rehearsal; copied results are under `build/stage4-linux-native-reports` in the
working checkout. Windows reports are in `maven-plugin/build/reports/maven`.

## Supplemental comments and regression tests — 2026-11-13

Adapter and packaged-descriptor coverage now contains **25 tests**, ten more
than the initial rehearsal above. It checks preservation of generated files and
history after rejection, baseline enforcement during generation, invalid generator
options, source-root registration after report failure, independent reports,
malformed YAML, existing source roots, CLI property bindings and parameter defaults.
Comments explain Maven injection and the separate core-operation/report-write steps.

Three additional Python regressions bring cache/bootstrap coverage to **49 tests**.
They cover XML escaping of Unicode/special-character paths, a missing numeric
catalog alias and preservation of child failure logs while removing stale success
summaries. The alias test exposed a template check which missed `@jackson3@`;
rendering now rejects that unresolved token before replacing the driver POM.

Generation, custom-path, reactor and standalone-example hooks now require nonempty
Surefire execution with zero failures, errors and skipped tests. Supplemental
local verification passed:

- `:maven-plugin:check` on Windows/JDK 25 and Linux/JDK 17, including strict
  dependency verification and configuration-cache checks: 25 tests each.
- All 49 cache/bootstrap Python tests on Linux, including physical link handling.
- Maven 3.9.16 and Maven 4.0.0-rc-7 on JDK 17, offline: 28 Invoker projects and
  12 binding tests in total, with no skipped tests. Both runs also rejected
  modified restored JAR and valid-XML POM bytes using native checksum enforcement.

These two cells were rerun after the supplemental changes; the earlier six-cell
matrix remains the initial rehearsal. New logs and reports are under
`build/comments-tests-native.log`, `build/comments-tests-maven.log` and
`build/comments-tests-native-reports`. No hosted CI run is claimed here.

## Limits and next confirmation

These are local results. The owner must commit/push before the new jobs can
confirm GitHub runner behavior, fork artifact delivery and cross-run cache reuse.
Maven 4 compatibility covers the pinned RC7 preview. The first WSL export filled
RAM-backed `/tmp`; the completed rehearsal used disk-backed `/var/tmp` and
retained the source, reports and JDKs.

Publication is prepared, with no upload or publisher-account change. The Maven
adapter and example become independently consumable after a reviewed 0.3.0 tag
passes hosted CI and its artifacts are published. The existing pilots continue
to use public 0.2.0 libraries.
