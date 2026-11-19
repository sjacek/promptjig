---
title: Run the examples
permalink: /examples/
---

Run these commands from a Promptjig checkout using its wrapper. On Windows use
`.\gradlew.bat`; on Linux/macOS use `./gradlew`.

The examples retain their reviewed, published version pins. The small Spring AI
pilot uses Portal plugin **0.3.1** and Central core/runtime **0.3.1**; the
validation-only Gradle consumer uses Portal **0.1.0**, and the standalone Maven
example uses **0.3.0**. The small pilot's upgrade is independent of those examples.
The installation guides show the current 0.4.0 release for new applications.

## Public announcement: no model required

```shell
python -B scripts/ci_dependency_cache.py prepare
./gradlew -p examples/runtime check run --dependency-verification=strict --configuration-cache --configuration-cache-problems=fail
```

On a fresh checkout, use Python 3.12+ to prepare the examples' ignored verification
files from the repository's reviewed checksum policy before invoking Gradle. Preparation does
not download dependencies or approve new checksums. Repeat it when the reviewed
policy changes; the strict flag alone does not create a checksum policy.

The small independent application extracts five fields from a fictional public
announcement: title, date, location, event format and free admission. It uses the
real Spring AI `ChatClient` with a bundled synthetic model response. No inference
backend, service application or database is required.

Use the pilot to verify the contract workflow: build validation, generated types,
packaged resources and Spring AI integration. The application owns model calls
and options; Promptjig renders and validates the contract boundary. The existing
corpus and reports help check that boundary with reviewed fictional data.

Expected output includes:

```text
mode=synthetic; contract=events.announcement-extraction-v1; accepted=true; findings=0
```

This checks orchestration and response reading, not model accuracy. Its contract
explicitly selects `output.strict: false` to demonstrate repairs with findings;
the [first-contract walkthrough](getting-started.md) leaves
that field absent to demonstrate the strict default.

Browse [the example source](https://github.com/sjacek/promptjig/tree/main/examples/runtime).
Authored resources are under `src/main/resources/prompts/events/announcement`.
Fictional announcements are under `src/main/resources/documents/*-testdata.txt`;
reviewed response fixtures are under `src/main/resources/responses/*-testdata.json`.
Its README describes the scenario options.

## Contract workflow in the small pilot

1. Author `announcement-extraction.prompt.yaml`, `system.st` and `user.st` under
   the example's `src/main/resources/prompts/events/announcement` directory.
   The schema requires a string `documentText`; the user template inserts it
   as a named parameter.
2. Run build validation and compile the generated Java types:

   ```shell
   ./gradlew -p examples/runtime promptContractsValidate compileJava --dependency-verification=strict
   ```

   `compileJava` runs generation automatically. Inspect the generated input,
   output and contract constants under
   `examples/runtime/build/generated/sources/prompt-contracts/java/main`.
   Validation reports are under `examples/runtime/build/reports/prompt-contracts/validate`.
3. Use the generated input with `PromptRuntime.prompt` and the generated output
   with `PromptRuntime.read`. `AnnouncementPilot` demonstrates the intervening
   Spring AI call and checks that packaged resources match the generated
   version/fingerprint before model access.
4. For a deliberate contract/template change, increment its version, explicitly
   run `promptContractsLock`, then run `check`. Review the resource and lock diffs
   together. Preserve historical entries and use a trusted target-branch lock
   through `-Ppromptjig.baselineLockFile=...` to protect CI history.

The input is always validated strictly, including when the output permits
repairs. Missing/null input, wrong types and unexpected fields are rejected.
The pilot explicitly selects `output.strict: false`; the strict default and
both explicit policies are tested using isolated contract copies. Constructing
a generated Java record alone does not validate every schema rule.

Follow the [complete example workflow](https://github.com/sjacek/promptjig/blob/main/examples/runtime/README.md#follow-the-contract-workflow)
and [version-history guide](versioning.md) for lock/baseline commands. Keep the
published 0.3.1 pins for this example until its separate consumer upgrade.
Release 0.4.0 is available in both registries; publication alone does not update
the example's dependency pins.
Producer changes on `main` are separate from the libraries used by this example.

## Regression reports for 25 announcements

Check the reviewed fictional corpus and report generation without a model:

```shell
./gradlew -p examples/runtime regressionReport --configuration-cache --configuration-cache-problems=fail
```

Open `examples/runtime/build/reports/regression/synthetic/quality-report.md` or
`quality-report.json`. This is a **synthetic replay**, clearly labelled in both
files; its scores check the evaluator and do not measure model quality.
The corpus includes ambiguous dates/venues, hybrid events, different admission
prices, missing facts and instructions quoted inside the announcement.

After selecting an installed model digest, use `runLocalRegression` for a real
evaluation. Reports go to `examples/runtime/build/reports/regression/local/` by
default. Select another destination with `-PpilotReportDir=<directory>` to keep
separate experiments; files in that destination are replaced.

Reports compare every expected and actual field and separately count accepted
answers, repairs, rejections, truncation and provider failures. Unknown facts
must remain null; an unavailable answer earns no field credit. A repaired
answer may match normalized fields but still fail the case. Each run retains
dataset/contract identities, model settings, finish reasons, reported token
usage and time. Both reports are saved even when a case fails, and the command
then exits with failure. The remaining announcements still run after a failed
request. These measurements describe the reviewed corpus, not general accuracy.

Required CI runs only replay and deterministic tests, and uploads their reports
with `runtime-pilot-reports`. See the
[corpus and report instructions](https://github.com/sjacek/promptjig/blob/main/examples/runtime/README.md#full-regression-corpus-and-saved-quality-reports)
for oracle review rules, field comparisons and output paths.

## Optional local-model checks

The same example has explicit `runLocal`, `runLocalCase`, `runLocalSuite` and `runLocalRegression`
tasks. They are separate from ordinary `check` and CI. Configure a supported local
OpenAI-compatible endpoint, install a model and select its digest as described
in the [local-model instructions](https://github.com/sjacek/promptjig/blob/main/examples/runtime/README.md).
These tasks perform real inference; the default command above does not.

The original five-case suite includes in-person, online, paid and incomplete
announcements; the full regression run uses 25 cases. Compare schema acceptance and factual correctness separately.
Keep model digest, contract identity, finish reason, token usage and duration
with each experiment. A response ending because of the token limit is incomplete;
do not treat a blank or truncated response as evidence that nullable fields are
the correct answer.

JSON report format 2 also records safe failure categories, cause class names,
HTTP status when available and UTC call intervals for runner correlation.
`TIMEOUT` requires a recognized timeout cause. Generic `INTERRUPTED_IO`, even
near the configured deadline, does not establish the exact cause. Error messages,
provider bodies and reasoning text are excluded. Keep historical format 1
reports unchanged and use a separate output directory for every comparison.

## Validation-only Gradle consumer

```shell
./gradlew -p examples/gradle check
```

This resolves the published plugin without building Promptjig from source.
For your own application, begin with the public-announcement contract in
[the walkthrough](getting-started.md).

## Maven example

The [standalone Maven example](https://github.com/sjacek/promptjig/tree/main/examples/maven)
exercises lifecycle generation, Java compilation, Jackson binding and packaged
resources using the published **0.3.0** Maven plugin from Central:

```shell
cd examples/maven
mvn verify
```

No private staging repository or local Promptjig installation is needed.
The source acceptance harness separately tests the current candidate in a private
copy; it does not rewrite the example's published version pin. See
[Maven availability and setup](maven.md) to use 0.4.0 in your own project.
