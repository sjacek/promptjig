---
title: Run the examples
permalink: /examples/
---

Run these commands from a Promptjig checkout using its wrapper. On Windows use
`.\gradlew.bat`; on Linux/macOS use `./gradlew`. The small runtime example uses
the published Portal plugin {{ site.releases.gradle_plugin }} and Central libraries
{{ site.releases.runtime }} by default.

## Public announcement: no model required

```shell
./gradlew -p examples/runtime check run --configuration-cache --configuration-cache-problems=fail
```

The small independent application extracts five fields from a fictional public
announcement: title, date, location, event format and free admission. It uses the
real Spring AI `ChatClient` with a bundled synthetic model response. No inference
backend, service application or database is required.

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

## Local-model experiments

The same example has explicit `runLocal`, `runLocalCase` and `runLocalSuite`
tasks. They are separate from ordinary `check` and CI. Configure a supported local
OpenAI-compatible endpoint, install a model and select its digest as described
in the [local-model instructions](https://github.com/sjacek/promptjig/blob/main/examples/runtime/README.md).
These tasks perform real inference; the default command above does not.

The five reviewed scenarios include in-person, online, paid and incomplete
announcements. Compare schema acceptance and factual correctness separately.
Keep model digest, contract identity, finish reason, token usage and duration
with each experiment. A response ending because of the token limit is incomplete;
do not treat a blank or truncated response as evidence that nullable fields are
the correct answer.

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
resources. Its build plugin is **unreleased {{ site.releases.maven_plugin }}**,
so it currently runs in source acceptance with privately staged artifacts.
It is not yet an independently resolvable Central consumer. See
[Maven availability and setup](maven.md).
