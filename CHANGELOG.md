# Changelog

Changes to Promptjig's features, compatibility and availability.
Changes under **Unreleased** are not yet published.

## Unreleased

### Added

- Download a CycloneDX SBOM alongside each new release. Maven Central provides
  signed JSON files for runtime, build, test and provided dependencies; the
  Gradle Plugin Portal distribution carries the same scopes in a separate SBOM
  JAR. Each inventory identifies the main JAR by its SHA-256.

- Follow a dedicated user guide for installation, contracts, Java generation,
  Spring AI integration, version history and troubleshooting, with a fictional
  public-announcement walkthrough.
- Validate prompt contracts in Maven projects before compilation. Contract and
  template errors appear together in the build log and JSON/text reports.
- Generate Java 17 records, enums and contract constants during `mvn verify`,
  with a choice of Jackson 2 or 3. Maven compiles the generated sources
  automatically.
- Protect contract version history in Maven builds. Explicitly append new
  versions with `promptjig:lock`, and use a CI baseline to detect rewritten
  history. Normal validation never changes your lock file.
- Try Maven validation and generation with a small fictional public-announcement
  example, without connecting a model. The Maven adapter is planned for 0.3.0
  and is not yet available from Maven Central.

### Fixed

- Verify Maven integrations with a patched archive decompressor, removing a known
  JVM-crash vulnerability from the integration test tooling.

### Changed

- Load Spring AI contracts from local resources with clearer startup failures:
  remote URLs are rejected before connection, and local template symlinks cannot
  escape the contract directory.
- Keep build diagnostics readable when file names or error text contain control
  characters, Unicode line separators or text-direction controls.
- Release checks now reject known High/Critical dependency findings and incomplete
  security evidence before either publishing destination can be used.
- Run the standalone Spring AI pilot with core/runtime 0.2.0 from Maven Central,
  without building Promptjig from source. The build plugin uses its independent
  Portal release 0.1.0.

## 0.2.0 — 2026-11-13

The core and Spring AI runtime libraries are available in Maven Central.
The Gradle plugin remains available as version 0.1.0 in Plugin Portal.

### Changed

- Model answers must now match the response contract by default. Answers with
  invalid fields are rejected, allowing your application to handle the failure
  instead of using a repaired answer.
- If your application needs partially usable answers, add `output.strict: false`
  before upgrading from the 0.1 development runtime. Each repair is reported so
  you can show fields requiring review. Adding this setting changes the contract
  fingerprint; follow the [upgrade guide](docs/spring-ai.md#runtime-migration)
  to update its version and lock.

### Added

- Use `promptjig-spring-ai` to render messages from your contract and read model
  answers into generated Java models. Invalid prompt resources are detected
  when contracts are loaded.
- Prepare signed core and Spring AI library packages for Maven Central, including
  sources and Javadocs. The manual release workflow checks the tested release
  commit and can resume an accepted deployment without uploading it again.
  Both libraries are publicly available as version 0.2.0.
- Find out which response fields were rejected or repaired without including
  extracted values or unexpected field names in validation findings. Contract
  versions, fingerprints and sensitivity labels are available for your
  application's audit records.
- Try Promptjig in a small standalone Spring AI example using fictional public
  event announcements. Start with the bundled demo or connect a local model.
- Compare extracted facts with reviewed answers for five announcements, including
  online events, paid admission and missing information. Run all cases or repeat
  one; incorrect facts, repairs and model failures are reported as failed cases.
- See whether a local model completed its answer, how many tokens it used and how
  long it took. Adjust the response limit and timeout for your machine; the example
  allows up to ten minutes per request by default and rejects truncated answers.

### Compatibility

- Use the Spring AI runtime alongside ChatClient in applications that resolve
  Networknt JSON Schema Validator 3.x. Standalone applications using 2.x remain supported.
- Check allowed decimal values without losing precision, including very long
  decimal values.
- The core and Spring AI runtime libraries require Java 17+. The runtime supports
  Spring AI 2.0.x and generated models for Jackson 2 or 3.
- For core API callers, `PromptContract.load(Path)` now reports loading failures
  through `IllegalArgumentException` instead of a checked `IOException`.

### Security

- Use Jackson 3.1.8 in the Spring AI runtime and examples to address
  [CVE-2026-110879](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph).

### Fixed

- Include the allowed list-item values in rendered prompts even when the list
  permits null or its definition uses a local schema reference.
- Use ST4 list separators and anonymous subtemplates without incorrect
  unknown-parameter errors from Spring AI.
- Run the standalone example against a local, unpublished Promptjig version
  without requiring that plugin version to be available in the Gradle Plugin Portal.

### Known limitations

- Remote publication of the standalone core and Spring AI runtime libraries
  remains pending. Use the included-build setup in the [runtime guide](docs/spring-ai.md).
- Maven build integration is not yet available.

## 0.1.0

### Added

- Install `io.github.sjacek.promptjig` version `0.1.0` from the
  [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.sjacek.promptjig/0.1.0).
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
- Use, modify and redistribute Promptjig under the MIT license, including in
  commercial projects.

### Compatibility

- The Gradle plugin requires Gradle 9.0+ and a supported JDK 17+. You can use
  your own Jackson dependencies in build scripts without conflicts with the plugin.

### Fixed

- Get consistently ordered JSON/text diagnostics when several findings share
  the same source location and message, making reports easier to compare.
