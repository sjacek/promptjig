# Standalone Spring AI pilot

Extract a **fictional public event announcement** into five generated fields:
title, date, location, format and free admission. The example has its own small
Gradle build and uses no service application, database, Docker service or Spring
Boot startup. Its authored Java classes use Lombok builders; the Java 17
input/output records and enum are generated from one Promptjig contract.

The default model returns a bundled synthetic response. This exercises the real
Spring AI `ChatClient`, classpath templates, generated types and runtime validator
without calling an inference backend. It demonstrates orchestration, not model
accuracy. All sample document and response content is fictional public test data.

## Pilot responsibilities and next steps

This example is the place to experiment with models, prompts and request
settings. Extend its fictional public announcements with reviewed expected
answers, compare extraction of stated and missing facts, and record model
digests, contract identities, finish reasons, token usage and response times.
Keep schema acceptance and factual correctness as separate measurements.
Prompt changes require a new contract version and a lock update.

The complementary `tichodroma-svc` pilot verifies application integration,
dependency compatibility, encrypted E1 audit, transactions, deadlines,
circuit-breaker behavior and fallback. It uses synthetic responses and loopback
HTTP in its dedicated CI job; E1 database guarantees have their own integration
tests. Its scope and adoption steps are recorded in the service repository's
`doc/PROMPTJIG_RUNTIME_PILOT.md`.

Next, expand the reviewed public fixture set and compare model settings with
reproducible reports. Keep inference explicit and required CI deterministic.
Both pilots now consume the released core/runtime libraries from Maven Central;
default service runtime adoption remains a separate reviewed change.

## Run the small example

From the **Promptjig repository root**:

```powershell
.\gradlew.bat -p examples/runtime check run --configuration-cache --configuration-cache-problems=fail
```

On Linux/macOS, use `./gradlew` with the same arguments. The wrapper requires a
supported JDK 17+ and initially downloads Gradle/dependencies as needed. The
build plugin resolves version 0.1.0 from Plugin Portal; core and Spring AI runtime
resolve version 0.2.0 from Maven Central. No Promptjig source build is included.
Versions are pinned in the repository's `gradle/libs.versions.toml`.
For explicit library development only, supply
`-PpromptjigBuildDir=<path-relative-to-examples/runtime>` to substitute all three
modules together. This override is not used by the normal pilot or its CI.

Local verification on 13 November 2026 passed all 78 tests on Windows/JDK 25
and Ubuntu WSL/JDK 17. The Linux run used an isolated copy containing only this
example, its catalog and wrapper, with offline dependencies restored from the
verified CI snapshot. Both packaged synthetic demos passed. The
[verification record](../../docs/evidence/published-runtime-consumer-2026-11-13.json)
includes public JAR hashes and confirms that no source build was included.
These are local checks; the changed configuration still needs a hosted CI run.

Expected output includes:

```text
mode=synthetic; contract=events.announcement-extraction-v1; accepted=true; findings=0
```

The following JSON contains `Wieczór z Javą`, `2026-12-10`,
`Przykładowy Dom Kultury`, `IN_PERSON` and `true`. The date binds to `LocalDate`
and format to the generated enum. Files are UTF-8; use a UTF-8 terminal for Polish
characters. Input braces such as `{example}` remain literal document data.

## Change the contract deliberately

Authored templates and schema are under `src/main/resources/prompts/events/announcement`.
`AnnouncementPilot` loads the generated `RESOURCE` path and verifies the packaged
contract's version/fingerprint at startup. Model options receive the contract's
bare schema; Spring AI adds the OpenAI `json_schema` envelope. Existing model
options are preserved when adding that schema.

`output.strict: false` explicitly allows nullable-field repairs with findings.
This example already selects repairing mode, so its contract and lock do not
change for runtime 0.2.0. Omitting this field in 0.2.0 rejects every schema violation.
Invalid dates,
unknown enum values and wrongly typed nullable fields become null; unknown
properties are removed. Malformed JSON and incompatible root structures reject
the response. Backend strict schema mode and reader strictness are independent.
An accepted response still needs application-level review.

For a deliberate schema or `.st` change, increment the contract version, then:

```powershell
.\gradlew.bat -p examples/runtime promptContractsLock check
```

Do not rewrite an existing lock entry or edit generated sources under `build/`.

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
Loopback HTTP tests exercise the actual Spring AI/OpenAI SDK, schema envelope,
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
