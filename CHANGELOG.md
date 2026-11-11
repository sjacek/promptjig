# Changelog

User-visible changes and compatibility notes for Promptjig.
Changes under **Unreleased** are not yet published.

## Unreleased

Candidate version: `0.1.0`. Plugin Portal publication is pending; available
locally through the included-build example.

### Added

- Generate Java input/output records, nested enums and compile-time contract
  metadata directly from schemas with `promptContractsGenerate`. Enable
  `generate {}` to compile them automatically with your application.
- Read generated API documentation for schema-required fields, nullable values,
  list handling, enum wire values and contract metadata.
- Use strict Java date/time and numeric types with Jackson 2 or 3. Generated
  lists default to empty and preserve the contract's original JSON enum values.
- Reuse generated sources from Gradle's relocatable build cache and remove
  stale generated types automatically when packages or contracts change.
- Use, modify and redistribute Promptjig under the MIT license, including in
  commercial projects. Distributed JARs include the license text.
- Define a prompt's inputs, expected JSON response and message templates in an
  v1 YAML contract. Run `promptContractsValidate` directly or as part of `check`
  to detect invalid contracts and unknown or unused template parameters.
- Keep prompt versions immutable with `promptContractsLock`: append new
  fingerprints explicitly and reject changed content under an existing version.
- Protect merged lock history in CI with `baselineLockFile`. Manually changed
  or removed baseline entries fail validation.
- Locate all contract/template errors through file, line and column diagnostics
  in the console and portable JSON/text reports. Reuse successful validation
  through Gradle's configuration cache and relocatable build cache.
- Compare prompt content using fingerprints that change with the contract or
  instructions and remain stable across Windows and Unix line endings.
- Try a complete certificate extraction example with synthetic data using
  `:core:spike`. Inspect rendering and response-validation results locally
  without a running model.
- Check structured JSON output against your local Docker Model Runner using
  `:core:modelSpike`. The example verifies schema enforcement on a pinned Qwen
  model; model calls run only when explicitly requested.

### Compatibility

- `PromptContract.load(Path)` no longer declares a checked `IOException`;
  contract loading failures are reported as `IllegalArgumentException` diagnostics.
- The plugin runs on Gradle 9.0+ and a supported JDK 17+. Its bundled validation
  libraries remain isolated from the consumer's buildscript dependencies.
- Building and running the experimental probes now requires JDK 21 or newer.
  The core library remains compatible with Java 17.
- Local builds now use the bundled Gradle 9.9.1 wrapper instead of 9.8.2.
  Run builds through `gradlew` or `gradlew.bat`; no separate Gradle installation
  is required.

### Security

- Upgrade the probes' transitive Jackson 3 dependencies to 3.1.8 to address
  [CVE-2026-110879](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph).

### Fixed

- Keep JSON/text report ordering stable when several findings share the same
  source location and message.

### Known limitations

- Spring AI 2.0.2 may reject valid templates containing `separator` or anonymous
  subtemplates when validation uses `THROW`. The example includes an alternative
  using `contract.output.enumText`; see the [experiment report](docs/stage0-report.md).
- The plugin is not yet available from Plugin Portal. The Maven adapter,
  generated models, Spring AI runtime and automatic response repair remain
  later stages. See the [Gradle guide](docs/gradle-plugin.md) for current setup.
