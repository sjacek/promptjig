# Pre-release security review — 13 November 2026

This is a maintainer implementation/review, **not an independent audit**.
ShardPilot's security review, remediation, dependency-integrity and published
artifact reports supplied recommendations. Their findings and exceptions are
not evidence about Promptjig and are not imported as vulnerability exclusions.

## Baseline

Base commit: `244ebd65795d75fe9ddab77ef2a62e8f2686be59`.
Working branch: `feature/security-review-0.3.0`. Candidate: **0.3.0**.
There are four distributions: core, Spring AI, Gradle adapter and Maven adapter.
All are produced by the same Gradle build; native Maven is an acceptance
consumer, not a second producer. Portal 0.1.0 and Central core/runtime 0.2.0
are the published baselines. New working-tree controls are not attributed to
those immutable releases or their previous green CI.

## Applicability and acceptance matrix

| Recommendation | Promptjig applicability | Existing evidence | Gap | Change | Acceptance | Limits |
| --- | --- | --- | --- | --- | --- | --- |
| Private disclosure and trust boundaries | All four distributions and CI | Read-only ordinary jobs; separate publishing secrets | No formal policy; private reporting disabled | SECURITY.md, boundary guide and concrete settings proposal | Review links and read GitHub setting separately | A document cannot enable a repository setting |
| Resolved SBOMs and vulnerability gates | Embedded Gradle runtime, ordinary libraries, provided Maven API and tooling | Native Gradle resolution and reviewed dependency policies | No scoped scan gate | Bind CycloneDX inventories to native graphs, candidate bytes and source cohort | Positive/negative report controls and actual pinned-scanner runs | Advisory database and embedded SDK coverage are bounded |
| Build integrity | Producer, TestKit, examples and native Maven | Strict Gradle verification; pinned distributions; Maven local checksum verifier; cache rejection tests | Moving Action refs and incomplete missing-sum controls | Immutable Action policy and exact native rejection checks | Correct bytes pass; changed bytes and missing trust fail for the intended reason | Checksums do not prove benign code |
| Published registry bytes | Portal 0.1.0 and Central core/runtime 0.2.0 | Historical publication and consumer evidence | Fresh registry/source/signature cohort needed | Separate registry audit, never substitute candidates | Re-download, inspect metadata/bytecode/hashes/signatures and compare proper tags | Portal evidence may be unsigned; candidate Maven remains local |
| Offline execution observation | Validate, lock/baseline, generation/compile and synthetic runtime | Source-level offline schema loading; deterministic examples | No positive-control network observer | Restricted Linux execution and retained raw traces | Observer sees blocked deliberate IP attempt; measured cases pass offline | Observed scenarios are not a global no-network guarantee |
| Parser/resource/report handling | YAML, ST4, JSON Schema and model JSON | Existing malformed-input, local-ref, containment and strict/repair tests | Review report escaping and resource scheme boundaries | Focused regressions and justified hardening | Synthetic traversal/external-reference/control-text tests | No claim of exhaustive DoS or malicious-project isolation |
| Release acceptance | Portal and Central | Manual publication; Central exact-SHA 18-job guard | Security gate absent; Portal guard weaker | Require security evidence for the same commit before either destination | Stale, missing, skipped, failed or conflicting reports reject publication | Remote settings and published-consumer checks are separate |

## Observed GitHub settings and proposed changes

Read-only API checks on 2026-11-13 found private vulnerability reporting **disabled**,
`main.protected=false` and no repository rulesets. The existing `maven-central`
environment had four named publication secrets but no reviewer protection.
Secret values were not read. These findings describe the repository settings,
not a code vulnerability or a successful compromise.

Concrete proposal for the owner's acceptance, not applied by this work:

1. Enable GitHub Private Vulnerability Reporting.
2. Require pull requests and the complete compatibility/security checks on `main`,
   prohibit force-push/deletion, and review permitted administrative bypasses.
3. Protect release tags `v*` from updates/deletion and limit tag creation to release maintainers.
4. Add a required reviewer to `maven-central` and `plugin-portal`; keep ordinary
   compatibility/scan jobs secret-free. Review environment branch/tag rules.

## Verification status

The following results were obtained locally from the working tree based on the
commit above. Candidate reports explicitly retain `sourceDirty: true`, hashes of
the reviewed inputs and candidate JARs. These results are not a green CI run of
the base commit, nor an independent audit.

| Check | Observed result | Local evidence |
| --- | --- | --- |
| Four candidate distributions, four scopes each | 16/16 accepted; no High/Critical; one Medium retained in Maven build tooling | `build/security/reports/summary.json`, per-scope SBOM/findings/status and `build/security/native/` |
| Scanner control | Metadata-only vulnerable Log4j fixture produced the expected Critical finding and blocking native exit | `build/security/reports/positive-control.*` |
| Python regression suite | 132 tests passed on Linux, including real Gradle signing with a disposable fixture key | `build/security/local-linux-checks/python-tests.log`, `result.json` and frozen `source-inputs.json` |
| Gradle adapter and workflow regression suites | 22 functional tests and 34 workflow tests passed; configuration-cache check passed | `gradle-plugin/build/test-results/test/` and `workflowTest/` |
| Native Maven consumers | Maven 3.9.16/JDK 21 and 4.0.0-rc-7/JDK 25 each passed 14 Invoker scenarios, including the standalone example, plus checksum controls | `maven-plugin/build/reports/maven/3.9.16-jdk-21/` and `4.0.0-rc-7-jdk-25/` |
| Dependency integrity | Correct bytes accepted; modified cached bytes and missing expected checksums rejected by native Gradle/Maven verification; original trust preserved | Native Gradle test XML, Maven summaries and the two reviewed tooling receipts in `security/` |
| IDE source/SDK coverage | Read-only enforcement and official-download verification passed for 214 source/SDK artifacts and one exact unpublished-source record | `build/ide-dependency-verification/`; concurrent IDE work is preserved separately |
| Local Central packages | Three publications / 15 payloads accepted; strict verification and Javadoc checks passed | `{core,spring-ai,maven-plugin}/build/staging-repository/`; no upload |
| Published registry artifacts | Fresh Portal 0.1.0 and Central core/Spring AI 0.2.0 audits passed; all three runtime scans reported no findings | `build/security/published/summary.json`, `scan-summary.json` and per-distribution evidence |
| Isolated offline scenarios | All five cases passed on actual JDK 17 and 25; 132 core, 53 Spring AI and 25 Maven unit tests per run; blocked outbound positive control observed | The two immutable run directories listed below |
| Workflow and policy validation | Actionlint, immutable Action/Wrapper policy and whitespace checks passed locally | Workflow tests and `scripts/dependency_policy.py` |

Candidate scans used **Grype 0.121.3**, commit
`cd1f23eb50dfb52647090158f453c7359172591f`, and a valid **v6.1.10** database
built `2026-11-13T06:20:21Z`. The recorded database download checksum is
`7776d82a5be335571ee9aa77093375659ceaae3fe324b9ed4ba2ba94abbaa36e`.
Reports retain the native scanner/DB identities, findings of every severity and
the actual exits. No project vulnerability exclusion was added.

Offline evidence is retained under:

- JDK 25.0.5: `build/security/offline/44d8c42170024b589b224fa48dfd75cd/`.
- JDK 17.0.20.1: `build/security/offline/3194877d2e8e4460a05ff9d52c1cff99/`.

Each run freezes its own source and runner, records the image/isolation policy,
native test XML and raw `strace` output. No external connection attempts were
observed in validation, lock/baseline checks, generation/compilation, runtime
tests or the packaged synthetic demo. The observer detected the deliberately
blocked `198.51.100.1:443` control. Historical locks remained unchanged. This is
evidence for these scenarios, not proof that arbitrary application/model calls
cannot communicate. The offline snapshots precede later report-validator/workflow
refinements and the 14 November IDE-warning cleanup. That cleanup also
renamed/inverted the resource-location predicate and made Gradle model types and
access explicit; it is not part of the frozen offline evidence. Current
Java/script/workflow tests cover those edits separately, and native graph
comparison confirmed unchanged dependency identities, edges and selected
configurations. No later offline run is claimed for the edited sources.

Fresh registry checks verified Java 17 base bytecode, published metadata and
sidecars, marker linkage, source-JAR coverage against the pinned release commits,
and ten available Central payload signatures against public fingerprint
`0AE0A30C093FDE0EA02E7502ADF2B8E6FA34B930`. Portal payloads are unsigned; the
0.1.0 tag is lightweight and the 0.2.0 annotated tag is unsigned. These facts
are recorded rather than treated as signed source provenance. Embedded core
0.1 has no separate published source archive in this audit. Binary
reproducibility from tags was not established.

## Remediation and remaining limits

The initial native scans exposed outdated descriptor-generator and active Maven
tooling dependencies. Reviewed pins now patch their actual classpaths/realms
(Guava, jsoup, Plexus, Commons Lang and BeanUtils), with 34 additional Gradle
checksums and two Maven checksums independently compared with official bytes and
sidecars. The receipts are `security/descriptor-tooling-review.json` and
`security/maven-tooling-review.json`; no existing trusted hash was replaced.
The Maven API compile/test graph is patched without replacing the host's runtime.

One **Medium** finding remains visible: `org.iq80.snappy:snappy:0.4`,
`GHSA-8wh2-6qhj-h7j9`, in native Maven Invoker build tooling; the database lists
0.5 as fixed. It does not reach a published library runtime. No suppression or
severity override was used. Evaluate the compatible tool update separately;
the implemented acceptance threshold blocks High/Critical, including unfixed
findings.

The inventory is not a complete audit of the Maven/Gradle executable, JDK or OS.
In particular, stock Maven 3.9.16 bundles Guava 33.6.0, reported High by this DB;
patching the adapter's compile graph does not patch that executable. See
[the toolchain boundary](dependency-security.md#integrity-cache-and-publication).
Older-host compatibility tests use synthetic trusted inputs and no publishing
credentials; they do not approve that host for untrusted projects.

The Windows full Python run could not generate its disposable GPG fixture key
because the local GPG agent failed. The full suite, including that real signing
test, passed inside the restricted Linux environment. Windows functional/workflow
tests, native Maven consumers, checksum enforcement and scans passed separately.
Linux symlink containment was exercised; Windows can skip that fixture when the
OS denies symlink creation. Full resource-exhaustion/performance tests and a
complete executable-toolchain audit remain separate work.

Read-only GitHub checks still show the settings described above. Previously
green Compatibility run **43496961833** had 18 jobs at the base commit; Docs run
**43496961924** was also green. They do **not** include the new security controls.
New CI requires 20 successful jobs, including `dependency-security` and
`offline-security`; every Gradle/JDK and Maven/JDK combination must execute on
the new commit before release. Only selected combinations were rerun locally.

## Next release steps

1. Finish/review the concurrent IDE dependency changes together with the policy
   diff, then let the owner commit and push the reviewed candidate.
2. Require a fresh green 20-job Compatibility run for that exact commit. Inspect
   retained scanner findings, offline traces, checksum controls and matrix test
   counts; do not substitute the older green main run.
3. Obtain the owner's decision on private reporting and repository/tag/environment
   protections above. The prepared files do not enable those settings.
4. Only then create the release tag and use the manual publication workflows.
   Repeat the registry audit for the actual 0.3.0 distributions after publication;
   local staging cannot establish registry availability or published bytes.

No commit, push, tag, external artifact publication or repository-protection
mutation was performed by this task. No model was downloaded or called.
