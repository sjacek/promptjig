# Standalone Spring AI pilot

Extract a **fictional public event announcement** into five generated fields:
title, date, location, format and free admission. The example has its own small
Gradle build and uses no service application, database, Docker service or Spring
Boot startup. Its four authored Java classes use Lombok builders; the Java 17
input/output records and enum are generated from one Promptjig contract.

The default model returns a bundled synthetic response. This exercises the real
Spring AI `ChatClient`, classpath templates, generated types and runtime validator
without calling an inference backend. It demonstrates orchestration, not model
accuracy. All sample document and response content is fictional public test data.

## Run the small example

From the **Promptjig repository root**:

```powershell
.\gradlew.bat -p examples/runtime check run --configuration-cache --configuration-cache-problems=fail
```

On Linux/macOS, use `./gradlew` with the same arguments. The wrapper requires a
supported JDK 17+ and initially downloads Gradle/dependencies as needed. The
plugin, core and runtime are taken from this checkout, since the runtime library
has not yet been published. Select another checkout with
`-PpromptjigBuildDir=<path-relative-to-examples/runtime>`. Dependency versions
come from that checkout's `gradle/libs.versions.toml`.

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

`output.strict: false` allows nullable-field repairs with findings. Invalid dates,
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

Only `runLocal` enables inference. It accepts these environment variables:

| Variable | Meaning |
| --- | --- |
| `PROMPTJIG_MODEL` | Required immutable `sha256:` digest; names/tags are rejected |
| `PROMPTJIG_BASE_URL` | Defaults to `http://127.0.0.1:12434/engines/v1`; explicit loopback HTTP port/path required |
| `PROMPTJIG_TIMEOUT_SECONDS` | Request timeout, 1–600 seconds; default 120 |

The SDK uses zero retries, temperature 0 and a 1024-token output limit. The local
SDK client/transport is closed after the call. The example uses a fixed dummy
credential for the loopback backend and needs no cloud API key. It does not
install/start a model or provide application audit persistence. Use the bundled
public fixture; this example is not an ingestion flow for confidential documents.

## Tests, CI and packaging

`check` validates the lock/templates and runs deterministic tests of generation,
rendering, valid output, nullable repairs, malformed responses and empty output.
Loopback HTTP tests exercise the actual Spring AI/OpenAI SDK, schema envelope,
model digest, request timeout and zero retries after HTTP 401/429/503. They
distinguish provider-protocol failures from invalid assistant JSON returned with
HTTP 200. Startup checks reject resource/version drift before calling a model;
CLI tests verify synthetic defaults, explicit local opt-in and UTF-8 output.
No test calls an inference backend.
The GitHub compatibility workflow runs the example and its installed distribution.

```powershell
.\gradlew.bat -p examples/runtime installDist
.\examples\runtime\build\install\promptjig-runtime-pilot\bin\promptjig-runtime-pilot.bat
```

The installed launcher uses JAR resources rather than source-tree files. Test
reports are under `build/reports/tests/test/` within this example.
