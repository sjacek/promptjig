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
