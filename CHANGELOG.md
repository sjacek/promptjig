# Changelog

Changes to Promptjig's features, compatibility and availability.
Changes under **Unreleased** are not yet published.

## Unreleased

Candidate version: `0.1.0`. Plugin Portal publication is pending; available
locally through the included-build example.

### Added

- Try Promptjig with Spring AI in a small standalone application that reads a
  fictional public event announcement into generated Java types. Run the
  synthetic demo without an inference backend, inspect response repairs and
  rejections, or explicitly select a local model by digest.
- Define prompt inputs, expected JSON responses and `.st` message templates in
  YAML contracts. Run `promptContractsValidate` or `check` to catch invalid
  schemas, missing templates and unknown or unused parameters before calling a model.
- Find contract and template errors by file, line and column in console output
  and JSON/text reports.
- Protect prompt versions with `promptContractsLock`: record new versions and
  reject changes to existing versions. Use `baselineLockFile` in CI to detect
  rewritten or deleted version history.
- Compare prompt versions using fingerprints that remain unchanged across
  Windows and Unix line endings.
- Generate Java inputs, outputs and enums with `promptContractsGenerate`.
  Enable `generate {}` to compile generated models with your application.
  Models support Jackson 2/3, Java date/time types and precise numbers;
  absent lists become empty. Generated documentation explains required fields,
  nullable values and enum values.
- Speed up repeated builds with configuration cache and build cache support.
  Generated files are refreshed when contracts or package names change, and
  obsolete generated types are removed automatically.
- Render system and user messages with `promptjig-spring-ai`, using validated
  input parameters and enum values from the contract. Load contracts from the
  application classpath, including packaged JARs; invalid contracts and duplicate
  IDs fail loading at startup.
- Read model responses into generated Java models. The default reader replaces
  invalid nullable fields with null, removes invalid list entries and unknown
  properties, and fills missing required nullable fields and arrays. Each repair
  is reported. Malformed JSON and unrepairable structures reject the whole response.
- Require a fully schema-compliant response with `output.strict: true`, which
  rejects responses needing any repair.
- Inspect response findings by JSON Pointer, rule, action, value type and length
  without exposing extracted values or unknown property names.
- Use response schemas, version identifiers, fingerprints and sensitivity labels
  when configuring model requests and recording application audit evidence.
  Your application controls model selection, data policy and audit storage.
- Use, modify and redistribute Promptjig under the MIT license, including in
  commercial projects.

### Compatibility

- Use the Spring AI runtime alongside ChatClient in applications that resolve
  Networknt JSON Schema Validator 3.x. Standalone applications using 2.x remain supported.
- Validate decimal enum values at full precision with either validator family,
  including values that differ beyond the precision of a Java `double`.

- The Gradle plugin requires Gradle 9.0+ and a supported JDK 17+. You can use
  your own Jackson dependencies in build scripts without conflicts with the plugin.
- The core and Spring AI runtime libraries require Java 17+. The runtime supports
  Spring AI 2.0.x and generated models for Jackson 2 or 3.
- For core API callers, `PromptContract.load(Path)` now reports loading failures
  through `IllegalArgumentException` instead of a checked `IOException`.

### Security

- Use Jackson 3.1.8 in the Spring AI runtime and examples to address
  [CVE-2026-110879](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph).

### Fixed

- Render item enum values for nullable array fields, including fields and items
  defined through local references.
- Render valid ST4 templates with list separators and anonymous subtemplates
  without Spring AI's false parameter-validation errors.
- Get consistently ordered JSON/text diagnostics when several findings share
  the same source location and message, making reports easier to compare.

### Known limitations

- Published artifacts are not yet available. Use the local setup in the
  [Gradle guide](docs/gradle-plugin.md) and [runtime guide](docs/spring-ai.md).
- Maven build integration is not yet available.
