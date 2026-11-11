# Java source generation

Enable generation in a Java project:

```groovy
promptContracts {
    generate {
        packageName = 'com.example.ai.prompt'
        jackson = 'v3' // v2 is the default
    }
}
```

The `generate {}` block enables automatic wiring into Java `main`.
`compileJava` then runs `promptContractsGenerate` first. The Java and Promptjig
plugins may be applied in either order. Without the block, validation-only
projects retain their existing lifecycle. Set `generate.enabled = false` to
disable automatic source wiring; explicitly requesting the generation task
still generates sources using its configured settings.

Generation independently validates contracts, templates, current lock and any
configured baseline. It never updates version history. For a new version, run
`promptContractsLock compileJava` explicitly; task ordering ensures the lock is
updated first. A changed existing version still fails without rewriting history.

## Generated API

For `patient.disability.certificate-extraction`, the last ID segment produces:

- `CertificateExtractionPromptContract`: compile-time constants `ID`, `VERSION`,
  `VERSION_REF`, `FINGERPRINT`, `RESOURCE` and `OUTPUT_SCHEMA_JSON`;
- `CertificateExtractionInput`: input record;
- `CertificateExtractionOutput`: output record with nested records and enums.

Generated Javadoc describes schema-required/optional components, nullability,
list normalization, original enum wire values and compile-time metadata. It
also states the boundary explicitly: record construction does not run schema
validation. The generated public API is itself checked with full Javadoc
validation in the core tests.

`VERSION_REF` has the form `patient.disability.certificate-extraction-v1` and
can be used in annotation arguments. `RESOURCE` defaults to `prompts/` followed
by the contract path relative to the configured source root. If those files
are packaged under another classpath directory, set `generate.resourcePrefix`
to that directory, for example `ai/prompts`. The consumer owns resource packaging.

| Schema | Java |
| --- | --- |
| `string` | `String` |
| `string`, `format: date` | `LocalDate` |
| `string`, `format: date-time` | `OffsetDateTime` |
| `boolean` | `Boolean` |
| `integer` | `BigInteger` |
| `number` | `BigDecimal` |
| scalar `enum` | nested enum with original JSON values |
| `object` | nested record |
| `array` | `List<T>` |
| local `$ref` | referenced generated type, including recursive objects |

Scalar fields use reference types so absent optional values remain `null`.
Nullable enum values map to a null reference, not an extra enum constant.
Record constructors normalize null arrays to empty lists and defensively copy
lists. This also covers absent arrays when Jackson constructs a record.

JSON property names are preserved by `@JsonProperty`, even when their Java
component names need escaping. For example, `class` becomes `class_`, and
`hyphen-name` becomes `hyphenName`. Numeric enum values preserve numeric JSON
representation; numbers are not converted to strings.

Jackson 2 and 3 share the `com.fasterxml.jackson.annotation` API;
[Jackson's annotations documentation](https://github.com/FasterXML/jackson-annotations)
explains this compatibility. The selected family is tracked in generation
inputs and source headers. No databind, Spring AI or Promptjig runtime imports
are generated. The consumer supplies compatible Jackson annotations and its
chosen mapper through its own dependency catalog. Jackson 2 date support needs
the Java time module; Jackson 3 includes it. Mapper settings still control
coercions and timezone adjustment. Generating typed records does not replace
runtime schema validation or the response-repair stage.

## Output ownership and reproducibility

Sources go to `build/generated/sources/prompt-contracts/java/main`. The task's
output directory can be overridden explicitly, but must stay inside the
project and separate from contracts, templates, the lock and baseline.

Generation plans every file before writing. Invalid inputs or Java naming
collisions leave the previous output untouched. Unchanged files retain their
bytes and modification times. On success, `.promptjig-generated.json` tracks
owned files; package changes and removed contracts delete only stale owned
sources. Other files are preserved, and authored Java files are never silently
overwritten. Filesystem write failures are reported as task failures.

Object keys, component declarations and output filenames are sorted; no
timestamps or checkout paths enter generated sources. Both generated sources
and reports support configuration cache and relocatable build cache. Reports
are under `build/reports/prompt-contracts/generate/`.

Generation reports errors for duplicate top-level type names, component/enum
names that collapse to the same Java identifier, records exceeding 254
components, strings exceeding JVM constant limits, and recursive arrays without
a concrete object boundary. These cases cannot be represented by the selected
Java API. Use distinct ID suffixes or schema/property names where necessary.

## Run the local example

From the repository root, with JDK 21+ for the authoring build:

```powershell
.\gradlew.bat -p examples/generation check '-PpromptjigPluginBuild=../..' --configuration-cache
```

The example compiles generated records with Java 17 APIs, reads a synthetic
response with Jackson 3 and uses `VERSION_REF` in an annotation. It calls no model.
