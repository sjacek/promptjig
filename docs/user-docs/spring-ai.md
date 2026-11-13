---
title: Spring AI runtime
permalink: /spring-ai/
---

`io.github.sjacek.promptjig:promptjig-spring-ai:{{ site.releases.runtime }}` is
published in Maven Central. It targets Java 17+ and Spring AI 2.0.x, tested with
{{ site.releases.spring_ai }}. It supplies contract loading, rendering and response
reading. Your application supplies the model client, model options and any
required audit persistence. There is no Spring Boot auto-configuration.

## Add the library

In `gradle/libs.versions.toml`:

```toml
[versions]
promptjig = "{{ site.releases.runtime }}"

[libraries]
promptjig-runtime = { module = "io.github.sjacek.promptjig:promptjig-spring-ai", version.ref = "promptjig" }
```

```groovy
repositories { mavenCentral() }
dependencies { implementation libs.promptjig.runtime }
```

Maven consumers can declare the same artifact as an ordinary dependency; the
Maven build plugin is not needed to use the published runtime:

```xml
<dependency>
  <groupId>io.github.sjacek.promptjig</groupId>
  <artifactId>promptjig-spring-ai</artifactId>
  <version>{{ site.releases.runtime }}</version>
</dependency>
```

Use your application's Spring AI dependency management and provider client.
Apply a build plugin separately if you need generated models.

## Load contracts at startup

Create eager beans in an application configuration class. The imports are from
`io.github.sjacek.promptjig.springai`, Spring's `context.annotation` and
`org.springframework.core.io.support.ResourcePatternResolver`.

```java
@Bean
@Lazy(false)
PromptContractRegistry promptContracts(ResourcePatternResolver resources) {
    return PromptContractRegistry.load(resources);
}

@Bean
PromptRuntime promptRuntime() {
    return PromptRuntime.builder().binding(PromptJsonBinding.jackson3()).build();
}
```

The default pattern is `classpath*:prompts/**/*.prompt.yaml`. Templates resolve
relative to each contract, including inside JARs, and are snapshotted after
validation. Invalid resources or duplicate IDs fail registry creation. With an
empty match the registry is empty; require expected IDs at startup with `get(id)`.
Use `load(resolver, pattern)` when selecting a subset of packaged contracts.

Choose `PromptJsonBinding.jackson2()` or `.jackson3()` to match the generated
models' Jackson family. Built-in bindings use isolated strict mappers with Java
time support. Custom bindings must provide equivalent strictness and be thread safe.

## Render and call your model

Using the generated classes from [the walkthrough](getting-started.md):

```java
var contract = registry.get(AnnouncementExtractionPromptContract.ID);
var input = new AnnouncementExtractionInput(documentText);
var messages = runtime.prompt(contract, input);
```

The runtime strictly validates input before rendering named parameters with
`PromptTemplate`/`SystemPromptTemplate`. It preserves message order and does not
re-evaluate template-looking source text.

Pass these messages to the application's Spring AI client. If your provider
supports structured output, `runtime.responseFormat(contract)` supplies the bare
output-schema JSON. The provider adapter supplies its envelope, name, strictness
and model options. Preserve your selected model, token limits and timeouts when
adding that schema; a fresh provider-options builder can carry unwanted defaults.

Check response presence and the provider's finish reason before reading text.
Treat a length-limited response as incomplete even if it happens to parse as JSON.
The runtime does not perform network calls, retries or fallback.

## Read the response

Persist the raw response first when your application requires audit, then read it:

```java
PromptReadResult<AnnouncementExtractionOutput> result =
        runtime.read(contract, rawJson, AnnouncementExtractionOutput.class);
if (result.accepted()) {
    // Persist findings and apply the application's review policy before use.
    var announcement = result.value();
} else {
    // Persist rejection findings and apply the application's fallback policy.
}
```

`accepted()` means schema validation and binding succeeded. It does not establish
factual correctness or approve a business operation. Rejection has no typed value
or partially repaired tree. Findings can accompany an accepted value in repairing mode.

## Repairing mode

Omitted `output.strict` and explicit `true` reject any schema violation. Only
explicit `output.strict: false` enables these repairs:

| Condition | Result in repairing mode |
| --- | --- |
| Invalid JSON, duplicate keys, trailing content, non-object root | Reject whole response |
| Invalid nullable scalar: type, enum, date, date-time or pattern | Replace with null |
| Invalid array element, including a wrong nested field | Remove the whole element |
| Unknown object property | Remove the property |
| Missing required nullable field | Fill with null |
| Missing required array | Fill with empty array |
| Missing optional field/list | No finding; generated model uses null/empty list |
| Missing other required value, wrong container structure or binding failure | Reject whole response |

An explicitly allowed null is valid. An enum excluding null cannot be repaired
to null. Valid array elements retain their original order. Integer forms such as
`1.0` are canonicalized without accepting numeric strings or fractions.

Findings carry JSON Pointers, keywords, action (`NULLIFIED`, `REMOVED`, `FILLED`,
`REJECTED`), type and length rather than raw values. Keep raw source/model text
out of ordinary diagnostic logs. `sensitivity` is metadata; the caller enforces
where information may be sent, stored or displayed.

## Upgrade from the development runtime

The 0.1 development runtime repaired responses when `output.strict` was omitted.
With {{ site.releases.runtime }}, leave it absent for strict rejection or explicitly
set `false` to retain repairs. Adding that field changes the fingerprint:
increment a frozen contract's version and append a lock entry.
Record the runtime version alongside the contract identity when comparing old
and new runs; unchanged contracts can now select a different read policy.
