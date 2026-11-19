# Standalone Spring AI pilot

Extract a **fictional public event announcement** into five generated fields:
title, date, location, format and free admission. The example has its own small
Gradle build and uses no service application, database, Docker service or Spring
Boot startup. Its authored Java classes use Lombok builders; the Java 17
input/output records and enum are generated from one Promptjig contract.

The default model returns a bundled synthetic response. This exercises the real
Spring AI `ChatClient`, classpath templates, generated types and runtime validator
without calling an inference backend. It verifies the contract-to-application
flow. All sample document and response content is fictional public test data.

## Pilot responsibilities and next steps

Use this example to learn and verify Promptjig's contract workflow: author a
schema and `.st` templates, validate them in the build, preserve version history,
generate Java types, then render messages and read a response. Extend tests when
a concrete contract or integration boundary needs coverage.

| Responsibility | Owner |
| --- | --- |
| Contract/template validation, version fingerprints, lock/baseline and Java generation | Promptjig core and Gradle plugin |
| Resource loading, rendering and contract-based input/output validation | Thin Promptjig Spring AI runtime, using Spring resources/templates and Jackson |
| Model configuration, `ChatClient` calls and transport lifecycle | This example and Spring AI |
| Audit persistence, transaction rollback and fallback | The consuming application |

Keep the existing corpus, synthetic replay and reports as verification aids.
Optional local-model runs exercise the same contract with an explicitly selected
backend. Schema acceptance and factual correctness remain separate observations;
this example does not establish a general model-quality guarantee or a training
workflow. Prompt changes require a new contract version and a lock update.

The complementary `tichodroma-svc` pilot verifies application integration,
dependency compatibility, encrypted E1 audit, transactions, deadlines,
circuit-breaker behavior and fallback. It uses synthetic responses and loopback
HTTP in its dedicated CI job; E1 database guarantees have their own integration
tests. Its scope and adoption steps are recorded in the service repository's
`doc/PROMPTJIG_RUNTIME_PILOT.md`.

The regression corpus now has 25 reviewed cases and JSON/Markdown reports (see
below). Keep inference explicit and required CI deterministic.
Both pilots consume released core/runtime libraries from Maven Central. The
small pilot stays on **0.3.1** pending a separate consumer upgrade. Release
**0.4.0** is now available in both registries; producer releases and changes
on `main` do not automatically update this consumer.

## Run the small example

From the **Promptjig repository root**, with Python 3.12+ for policy preparation:

```powershell
python -B scripts/ci_dependency_cache.py prepare
.\gradlew.bat -p examples/runtime check run --dependency-verification=strict --configuration-cache --configuration-cache-problems=fail
```

The preparation command copies the repository's reviewed checksum policy into
the standalone example builds. It does not download dependencies or generate
new trusted checksums. This step is needed on a fresh checkout: the example's
`gradle/verification-metadata.xml` is an ignored copy of the shared policy.
Repeat preparation after a reviewed policy update; do not edit that copy.

On Linux/macOS, use `./gradlew` with the same arguments. The wrapper requires a
supported JDK 17+ and initially downloads Gradle/dependencies as needed. The
build plugin resolves version **0.3.1** from Plugin Portal; core and Spring AI runtime
resolve version **0.3.1** from Maven Central. No Promptjig source build is included.
Versions are pinned in the repository's `gradle/libs.versions.toml`.
For explicit library development only, supply
`-PpromptjigBuildDir=<path-relative-to-examples/runtime>` to substitute all three
modules together. This override is not used by the normal pilot or its CI.

Historical verification of runtime 0.2.0 on 13 November 2026 passed all 78 tests on Windows/JDK 25
and Ubuntu WSL/JDK 17. The Linux run used an isolated copy containing only this
example, its catalog and wrapper, with offline dependencies restored from the
verified CI snapshot. Both packaged synthetic demos passed. The
[verification record](../../docs/evidence/published-runtime-consumer-2026-11-13.json)
includes public JAR hashes and confirms that no source build was included.
The [0.3.1 acceptance record](../../docs/evidence/runtime-pilot-0.3.1-2026-11-14.json)
covers **81 tests** on Windows/JDK 25 and isolated Linux/JDK 17, plus both packaged
synthetic demos. The Linux run restored verified dependencies, had no producer
sources or network, and reused configuration cache on a second invocation.
Actual plugin/core/runtime JAR hashes match the public release. The three added
cases check the published reader's strict default, explicit `true` and explicit
`false` using isolated contract copies; authored resources and their frozen lock
stay intact. These are historical local acceptance records; required CI reruns
deterministic checks against the published pins. The real-model results below
remain historical.

Expected output includes:

```text
mode=synthetic; contract=events.announcement-extraction-v1; accepted=true; findings=0
```

The following JSON contains `Wieczór z Javą`, `2026-12-10`,
`Przykładowy Dom Kultury`, `IN_PERSON` and `true`. The date binds to `LocalDate`
and format to the generated enum. Files are UTF-8; use a UTF-8 terminal for Polish
characters. Input braces such as `{example}` remain literal document data.

## Follow the contract workflow

All paths in the table are relative to `examples/runtime`:

| Step | Authored input or generated result |
| --- | --- |
| Author the contract | `src/main/resources/prompts/events/announcement/announcement-extraction.prompt.yaml` defines input, output and template names |
| Author instructions | `system.st` and `user.st` beside the contract; `documentText` is a named input parameter |
| Validate and preserve history | `promptContractsValidate` checks the contract, templates and `prompt-contracts.lock.json`; ordinary builds never update the lock |
| Generate and compile | `promptContractsGenerate` writes `AnnouncementExtractionInput`, `AnnouncementExtractionOutput` and `AnnouncementExtractionPromptContract` under `build/generated/sources/prompt-contracts/java/main`; `compileJava` runs generation automatically |
| Render and read | `AnnouncementPilot` loads the generated `RESOURCE`, checks version/fingerprint, calls `PromptRuntime.prompt` with generated input and `PromptRuntime.read` with generated output |

To inspect the build boundary without running the demo:

```powershell
.\gradlew.bat -p examples/runtime promptContractsValidate compileJava --dependency-verification=strict
```

Validation reports are under `build/reports/prompt-contracts/validate` in this
example. Generated files are build outputs: edit the contract or `.st` resources,
then regenerate. The constants describe the authored schema and resource identity;
constructing a Java record alone does not validate all schema constraints.

The runtime part can be used without a `ChatClient` or model call:

```java
var runtime = PromptRuntime.builder().binding(PromptJsonBinding.jackson3()).build();
var contract = PromptContractRegistry.load(
        new ClassPathResource(AnnouncementExtractionPromptContract.RESOURCE))
        .get(AnnouncementExtractionPromptContract.ID);
var messages = runtime.prompt(contract, new AnnouncementExtractionInput(documentText));
var result = runtime.read(contract, responseJson, AnnouncementExtractionOutput.class);
```

Here `documentText` and `responseJson` are caller-supplied data. The generated
classes are in `example.announcement.generated`; the runtime classes are in
`io.github.sjacek.promptjig.springai`, and `ClassPathResource` is from Spring.
`messages` can then be passed to Spring AI by the application, as the pilot does.
The runtime itself neither invokes a model nor saves audit data.

Input must match `input.schema` before rendering: a missing/null `documentText`,
wrong type or unexpected property is rejected without repair or coercion.
`output.strict` controls response reading only. An accepted response satisfies
the reader's policy; use its findings and review the extracted facts separately.

## Change the contract deliberately

Authored templates and schema are under `src/main/resources/prompts/events/announcement`.
`AnnouncementPilot` loads the generated `RESOURCE` path and verifies the packaged
contract's version/fingerprint at startup. Model options receive the contract's
bare schema; Spring AI adds the OpenAI `json_schema` envelope. Existing model
options are preserved when adding that schema.

`output.strict: false` explicitly allows nullable-field repairs with findings.
This example already selects repairing mode, so its contract and lock do not
change for runtime 0.3.1. Since 0.2.0, omitting this field rejects every schema violation.
Invalid dates,
unknown enum values and wrongly typed nullable fields become null; unknown
properties are removed. Malformed JSON and incompatible root structures reject
the response. Backend strict schema mode and reader strictness are independent.
An accepted response still needs application-level review.

For a deliberate schema or `.st` change, increment the contract version from 1
to 2 (or to the next unused version), then explicitly append it to the lock:

```powershell
.\gradlew.bat -p examples/runtime promptContractsLock --dependency-verification=strict
.\gradlew.bat -p examples/runtime check run --dependency-verification=strict
```

Review the contract/template and lock diffs together. Historical versions and
fingerprints must remain unchanged; the new version adds an entry.
Running `check` before updating the lock deliberately fails for an unrecorded
version. Changing frozen version 1 without increasing its version also fails;
the lock task does not overwrite the old fingerprint. Update reviewed response
fixtures if the intended schema or meaning has changed.

Protect history against a **trusted target-branch lock**, not a copy of an
already edited working-tree lock. For a local review, use an up-to-date
`origin/main` ref and save its lock outside the example:

```powershell
$baseline = Join-Path $env:TEMP 'promptjig-runtime-baseline.json'
git show origin/main:examples/runtime/prompt-contracts.lock.json |
    Set-Content -LiteralPath $baseline -Encoding utf8
.\gradlew.bat -p examples/runtime check "-Ppromptjig.baselineLockFile=$baseline" --dependency-verification=strict
```

The configured baseline must exist. Deleting or changing any historical entry
fails validation even if today's contract still matches its current lock entry.
CI supplies a baseline from the PR target or preceding push commit and also
checks packaged execution. Neither the baseline nor a normal build creates
trusted dependency checksums. Dependencies remain subject to the repository's
strict verification metadata. Run offline only after reviewed dependencies are
available; do not regenerate checksums to make a failed download pass.

## Optional real local model

Use the same bundled fictional document with Docker Model Runner's OpenAI-compatible
endpoint. The model must already be installed and reachable. Read its full digest
with `docker model inspect <installed-model>` and set it explicitly:

```powershell
$env:PROMPTJIG_MODEL = 'sha256:<64-lowercase-hex-digits-from-inspect>'
.\gradlew.bat -p examples/runtime runLocal
```

`runLocal`, `runLocalSuite` and `runLocalCase` explicitly enable inference. They accept these environment variables:

| Variable | Meaning |
| --- | --- |
| `PROMPTJIG_MODEL` | Required immutable `sha256:` digest; names/tags are rejected |
| `PROMPTJIG_BASE_URL` | Defaults to `http://127.0.0.1:12434/engines/v1`; explicit loopback HTTP port/path required |
| `PROMPTJIG_TIMEOUT_SECONDS` | Request timeout, 1–600 seconds; default 600 for local CPU inference |
| `PROMPTJIG_MAX_TOKENS` | Generation budget, 1–8192 tokens; default 4096, including model reasoning |

The SDK uses zero retries, temperature 0 and the configured generation budget. The local
SDK client/transport is closed after the call. The example uses a fixed dummy
credential for the loopback backend and needs no cloud API key. It does not
install/start a model or provide application audit persistence. Use the bundled
public fixture; this example is not an ingestion flow for confidential documents.

### Evaluate several announcements

Run the original announcement plus four additional fictional cases on the selected local model:

```powershell
$env:PROMPTJIG_MODEL = 'sha256:<64-lowercase-hex-digits-from-inspect>'
$env:PROMPTJIG_TIMEOUT_SECONDS = '600'
$env:PROMPTJIG_MAX_TOKENS = '4096'
.\gradlew.bat -p examples/runtime runLocalSuite --configuration-cache --configuration-cache-problems=fail
```

| Fixture | Expected distinction |
| --- | --- |
| `public-event-testdata` | In-person event, explicitly free admission |
| `online-event-testdata` | Online event, stated URL, explicitly free admission |
| `paid-event-testdata` | In-person workshop, explicitly paid admission (`free: false`) |
| `missing-details-testdata` | Only a title; date, location, format and admission must all be null |
| `missing-price-testdata` | Known date/place/format; admission must remain null |

Each case uses a separate request with no conversation history and no retries.
The runner prints the model digest, contract version/fingerprint, per-case expected
and actual generated values, validation finding count, elapsed extraction time and
a final pass/fail count. A pass requires every field to match and zero findings;
schema acceptance alone does not pass. Provider failures are recorded separately
and the remaining cases still run. Any failed case makes the command fail.
Each result includes provider `finishReason`, token usage, optional reasoning-token
usage and assistant text length. Metadata is captured before empty-output checks
and JSON validation. Missing counters remain null; reasoning text is not recorded.
`finishReason: length` rejects the response even if its partial text is valid JSON.
The timeout applies to each request, including initial model loading.
All fixtures are bundled classpath resources; no sample URL is fetched.

### Full regression corpus and saved quality reports

The original five-case suite above remains available for quick experiments.
The full **25-case corpus** adds hybrid events, ambiguous and corrected dates,
registration deadlines, leap days, missing years, date ranges, conflicting
venues, optional donations, mixed admission prices, missing titles/URLs,
literal braces and instructions quoted as document data. Every source is
fictional public test data, with a separately reviewed expected JSON answer.

Check the evaluator and packaged corpus **without a model**:

```powershell
.\gradlew.bat -p examples/runtime regressionReport --configuration-cache --configuration-cache-problems=fail
```

Read `build/reports/regression/synthetic/quality-report.md` and
`quality-report.json` under this example. The mode is `SYNTHETIC_REPLAY`: it
replays expected answers to check rendering, reading, comparisons and report
generation. **Its scores do not measure model quality.** Missing provider token
counters stay null. The same replay runs from the installed distribution in CI;
reports are included in the `runtime-pilot-reports` artifact.

To evaluate an already installed local model on all 25 cases:

```powershell
$env:PROMPTJIG_MODEL = 'sha256:<64-lowercase-hex-digits-from-inspect>'
$env:PROMPTJIG_MAX_TOKENS = '4096'
.\gradlew.bat -p examples/runtime runLocalRegression -PpilotReportDir=build/reports/regression/qwen-baseline --configuration-cache --configuration-cache-problems=fail
```

The default destination is `build/reports/regression/local/` under this example.
`-PpilotReportDir` selects a destination relative to the working directory from
which the launcher runs (`examples/runtime` for the Gradle tasks), or an absolute
path. Choose a fresh directory for each comparison: files in the selected
directory are replaced. The installed launcher accepts
`--regression-report=<directory>` or `--local-regression-report=<directory>`.
Local inference makes one independent request per case, with temperature 0,
zero retries and the configured per-case timeout. A full run may take much
longer than the five-case smoke suite. It is not attached to `check` or CI.

The report records corpus revision/content SHA-256, contract version/fingerprint,
model digest and settings, and per-case expected/actual values, finish reason,
usage, findings count and duration. Resource hashes use UTF-8 content with LF
line endings so Windows and Linux runs identify the same reviewed corpus.
It retains neither raw provider bodies, exception messages nor reasoning text.
JSON report format 2 adds `failureDiagnostics` and `callTiming` to each case.
The diagnostics retain cause class names, a bounded/cycle-safe cause-chain flag,
an available HTTP status and a UTC observation time. Call intervals use UTC for
runner correlation; elapsed durations still use a monotonic clock. Successful
calls have no failure diagnostics. Historical format 1 reports are preserved.
`TIMEOUT` requires a recognized timeout exception in the cause chain.
`INTERRUPTED_IO` also occurs when this SDK's total call timeout expires, but
the generic exception type cannot distinguish it from other interruption.
Neither a message containing "timeout" nor a duration near 600 seconds proves
the cause. Keep unknown failures and unavailable token counters explicit.
Reader findings include safe field pointers, rules and repair/rejection actions,
so a repaired null can be investigated even when it matches the expected value.
Interpret its measurements separately:

- **Case pass:** all five fields exactly match, the reader accepted the answer,
  no repair was needed and no provider/truncation error occurred.
- **Field match:** normalized actual equals expected. A repaired answer can
  match fields but still fail its case. Strings are compared exactly; there is
  no fuzzy matching, date guessing or automatic oracle update.
- **Known/unknown facts:** separate totals show extracted stated facts and
  correctly preserved nulls. A rejected or unavailable response gets no field
  credit, including fields whose expected value is null.
- **Outcomes:** distinguish wrong facts, repaired or rejected JSON, truncation,
  response errors and provider errors. Schema acceptance alone is not a pass.
- **Usage:** sums include only reported counters and state how many cases
  reported each counter; a partial sum is not the total cost of the full run.

Both reports are saved before a failed comparison makes the command exit with
failure. Remaining cases still run after a provider or response failure. A
missing/invalid reviewed fixture instead fails preflight before model access.
These cases expose specific regressions, not general model accuracy.

The corpus manifest is `src/main/resources/regression/announcements-testdata.json`.
Its entries list IDs, tags and review notes; sources live in `documents/` and
oracles in `responses/`. Review the stated and unknown facts when adding or
changing an oracle, then increment the corpus `version`. In particular, the
single-date/single-price contract cannot represent an unresolved date range or
different prices for hybrid attendance, so those ambiguous fields remain null.
Changing these fixture files does not change the authored prompt. A deliberate
`.st` or schema change still requires a new contract version and lock entry.

These few reviewed examples check specific behavior, not overall model accuracy.
The real-model suite is optional and is never attached to `check` or regular CI.

The [recorded local run on 12 November 2026](../../docs/evidence/runtime-pilot-local-model-2026-11-12.json)
used Qwen 3.5 9B Q4_K_XL at its pinned digest with the previous 1024-token limit.
The original case passed; all four added cases raised an exception before a
generated output could be compared. Engine logs recorded 1024 generated tokens
for each failed case. Budget exhaustion is a possible cause, but the individual
exception messages and API finish reasons were not captured in that run.
The run therefore does not establish model correctness for the added cases.

The subsequent [token-budget investigation and full rerun](../../docs/evidence/runtime-pilot-token-budget-2026-11-12.json)
reproduced the online failure at 1024 tokens: Spring AI reported `LENGTH` and zero
final assistant characters. Raising only the generation budget to 4096 yielded
**5/5 passes**, each with `STOP`, matching facts and no validation findings.
The five completion counts were 1005, 2255, 1225, 1428 and 3477. In particular,
the title-only announcement retained four null fields, and absent pricing remained
null rather than becoming free or paid admission. The full rerun took 22m 12s on
this local setup; these timings are observations, not a performance guarantee.
Both measured runs used a 600-second request timeout, now also the local default.

To diagnose one bundled case at a controlled budget, use the same validator and
comparison rules without running the other four announcements:

```powershell
$env:PROMPTJIG_MAX_TOKENS = '1024'
.\gradlew.bat -p examples/runtime runLocalCase -PpilotCase=online-event-testdata
```

Only known fixture IDs are accepted. Set the budget back to 4096 before running
`runLocalSuite`; the command never automatically increases budgets or retries failed cases.

## Tests, CI and packaging

`check` validates the lock/templates and runs deterministic tests of generation,
rendering, valid output, nullable repairs, malformed responses and empty output.
Input-policy tests check required/non-null input, wrong types, extra properties
and reserved-metadata injection for the strict default and both explicit output
policies. Startup tests also reject changed schemas/reader policies and missing
or malformed resources before model access. Loopback HTTP tests exercise the actual Spring AI/OpenAI SDK, schema envelope,
model digest, request timeout and zero retries after HTTP 401/429/503. They
distinguish provider-protocol failures from invalid assistant JSON returned with
HTTP 200. Startup checks reject resource/version drift before calling a model;
CLI tests verify synthetic defaults, explicit local opt-in and UTF-8 output.
Suite tests check unknown facts, wrong but schema-valid answers, repaired nulls,
provider failures and continuing after a rejected response using deterministic doubles.
SDK transport tests verify configured budgets, finish reasons, usage capture and
rejection of length-limited responses with both empty and schema-valid assistant text.
No test calls an inference backend.
The GitHub compatibility workflow runs the example and its installed distribution.

```powershell
.\gradlew.bat -p examples/runtime installDist
.\examples\runtime\build\install\promptjig-runtime-pilot\bin\promptjig-runtime-pilot.bat
```

The installed launcher uses JAR resources rather than source-tree files. Test
reports are under `build/reports/tests/test/` within this example.
