---
title: Generate Java models
permalink: /java-generation/
---

Generation turns the same checked schemas into Java 17 records, enums and
contract constants. With Gradle, opt in using `promptContracts.generate`.
The Maven plugin uses its `generate` goal. Both validate the lock and
any supplied baseline before writing sources.

For `events.announcement-extraction`, generation produces:

- `AnnouncementExtractionPromptContract`: `ID`, `VERSION`, `VERSION_REF`,
  `FINGERPRINT`, `RESOURCE` and `OUTPUT_SCHEMA_JSON` constants.
- `AnnouncementExtractionInput`: the input record.
- `AnnouncementExtractionOutput`: the response record with any nested types.

`VERSION_REF`, such as `events.announcement-extraction-v1`, can be used in Java
annotations. `RESOURCE` names the contract's classpath resource. Constructing a
record does not perform schema validation; use the runtime to validate inputs
and read model responses.

## Supply Jackson dependencies

Generated code uses the shared Jackson annotation package. It imports no
Spring AI or Promptjig runtime implementation classes. Your application supplies
the mapper and matching annotation dependencies, for example Jackson 3 through
`gradle/libs.versions.toml`:

```toml
[versions]
jackson = "3.1.8"

[libraries]
jackson-bom = { module = "tools.jackson:jackson-bom", version.ref = "jackson" }
jackson-databind = { module = "tools.jackson.core:jackson-databind" }
```

```groovy
repositories { mavenCentral() }
dependencies {
    implementation platform(libs.jackson.bom)
    implementation libs.jackson.databind
}
```

Select `jackson = 'v3'` in the generator for this example. Jackson 2 uses `v2`
and needs its Java time module for dates; Jackson 3 includes Java time support.

## Schema mapping

| Schema | Java type |
| --- | --- |
| `string` | `String` |
| `string` with `format: date` | `LocalDate` |
| `string` with `format: date-time` | `OffsetDateTime` |
| `boolean` | `Boolean` |
| `integer` | `BigInteger` |
| `number` | `BigDecimal` |
| Scalar `enum` | Nested enum preserving the original JSON values |
| `object` | Nested record |
| `array` | `List<T>` |
| Local `$ref` | Referenced generated type |

Scalar fields use reference types. Missing optional scalar values remain null;
null arrays are normalized to empty lists and lists are defensively copied.
Nullable enum values map to null rather than an extra enum constant. Java-safe
component names retain their original JSON property name through `@JsonProperty`.

Since **0.4.0**, the generator maps the JSON property `java` to the Java component
`java_`, including in records without lists. JSON keeps the original key.
This avoids compilation errors when records also contain lists.

## Upgrade generated models

**Availability:** version 0.4.0 was published on 2026-11-18 in Maven Central
and Gradle Plugin Portal. These steps describe migration from 0.3.x. Installation
guides use 0.4.0; the bundled small pilot retains 0.3.1 pending a separate
consumer upgrade.

1. Upgrade the generator used by your build: the Gradle plugin or Maven plugin.
   Upgrade core/runtime libraries to the matching release when your application
   uses them; changing a runtime dependency alone does not regenerate models.
2. Regenerate from your authored contracts and rebuild all callers. With the
   upgraded plugin, `./gradlew clean check` or `mvn clean verify` removes stale
   build output, generates sources and compiles the application. Preserve
   existing generation settings and the contract lock.
3. For each JSON property named `java`, replace direct Java calls such as
   `output.java()` with `output.java_()`. This applies to input, output and nested
   records, including records without lists. Update any reflection-based code
   that looks up the old accessor. Rebuild downstream code that uses regenerated
   models so it no longer expects the previous method.
4. Keep the JSON key `java`. Regenerated records still read and write that key
   through `@JsonProperty`; the Java rename does not rename the wire field.

If one object contains **both `java` and `java_`**, generation fails before
writing files because both would become `java_`. Choose a distinct authored
property name for one of them, update affected templates and callers, increment
the contract version and explicitly append its new lock entry. Review that
wire-format change with your consumers; do not edit generated code to work
around the collision.

Upgrading the generator and regenerating unchanged contracts does **not**
require a contract-version increment or lock rewrite: their fingerprint is
based on the authored contract and templates. Only a deliberate contract/template
change, such as resolving a property collision, requires a new version and lock
entry. Response reading remains strict by default; existing explicit
`output.strict: false` continues to opt into repairs.

## Output ownership

Keep generated files under build output, apart from authored Java and resources.
An invalid contract or Java name collision leaves the previous output untouched.
Unchanged files retain their bytes and modification times. A successful run
tracks owned files in `.promptjig-generated.json` and removes only stale owned
sources when contracts or packages change. Unrelated files are preserved.

No timestamps or checkout paths enter generated sources. Reports describe
unrepresentable names, JVM limits and unsupported recursive array shapes.
Use distinct contract ID suffixes to prevent two contracts from producing the
same top-level Java type name.
