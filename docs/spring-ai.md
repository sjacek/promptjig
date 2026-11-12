# Spring AI runtime

The 0.2.0 candidate, `io.github.sjacek.promptjig:promptjig-spring-ai:0.2.0`,
targets Java 17+ and Spring AI 2.0.x (tested with 2.0.2). It is a library, without a model client,
Boot auto-configuration, transport, logging or audit persistence. Remote Maven
publication remains pending. Use the local checkout before publication:

```groovy
// settings.gradle, explicit local path supplied by the developer
includeBuild(promptjigCheckout) {
    dependencySubstitution {
        substitute module('io.github.sjacek.promptjig:promptjig-core') using project(':core')
        substitute module('io.github.sjacek.promptjig:promptjig-spring-ai') using project(':spring-ai')
    }
}
```

Declare the library through the consumer's `libs.versions.toml`. Apply the
Gradle plugin separately when generating models. The runtime dependency does
not generate sources or check the version lock.

```toml
[versions]
promptjig = "0.2.0"
[libraries]
promptjig-runtime = { module = "io.github.sjacek.promptjig:promptjig-spring-ai", version.ref = "promptjig" }
```

```groovy
dependencies { implementation libs.promptjig.runtime }
```

## Load at startup

Create the registry in an eager application startup bean, using the application's
resource resolver. A regular singleton `@Bean` works when lazy initialization
is disabled; applications using lazy initialization must explicitly make this
bean eager. Invalid UTF-8, YAML, schema or ST4 resources and duplicate IDs throw
`IllegalArgumentException`, preventing that bean's creation.

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

The default pattern is `classpath*:prompts/**/*.prompt.yaml`. Template basenames
resolve relative to each contract, including within JARs, and are snapshotted
after validation. No temporary extraction directory is needed. Use
`load(resolver, pattern)` for an explicit subset when old and new versions are
packaged together: the selected subset must have unique IDs. No resources yields
an empty registry; `get(id)` rejects a missing expected contract. Load expected
IDs in startup configuration when their presence is required.

Core's `PromptContract.parse(name, text, templateSource)` provides the same
contract/template validation for other resource systems without depending on Spring.
Its loader must supply decoded UTF-8 template text relative to the owning resource.

## Render messages

```java
var contract = registry.get(CertificateExtractionPromptContract.ID);
var input = new CertificateExtractionInput(ocrText);
List<Message> messages = runtime.prompt(contract, input);
```

Input is serialized and validated strictly against `input.schema` before
rendering. Missing required values, extra properties and wrong types fail
without retaining serialization exception details. Optional missing attributes
are available as null. `contract` cannot be supplied by the input.

Each authored `.st` resource is rendered through `SystemPromptTemplate` or
`PromptTemplate` in message order. Values are passed as named attributes;
template-looking input is not evaluated a second time. ST4 uses host-platform
line separators, including within multiline attributes. Calls have independent
parameter maps and rendering state.

Reserved attributes derived from `output.schema`:

| Attribute | Contents |
| --- | --- |
| `contract.output.fields` | Top-level output names in lexical order |
| `contract.output.enums.<field>` | Non-null enum wire values, in schema order |
| `contract.output.fieldText` | Output names joined with comma-space |
| `contract.output.enumText.<field>` | Enum wire values joined with comma-space |

For array fields, enum values come from `items`; local references are resolved.
Spring's lexical validation is disabled after core's ST4 AST checks, so valid
separators and anonymous local variables work. This does not disable contract,
input, ST4 syntax or unknown/unused parameter validation. Nested property
spelling inside a map remains ST4's responsibility.

Add model options in application code. `responseFormat(contract)` supplies the
bare output schema JSON; the caller supplies the backend's `json_schema`
envelope, name, strictness and model options. It does not switch transport mode.

## Read an already audited response

Commit raw output to the application's audit before calling:

```java
PromptReadResult<CertificateExtractionOutput> result =
        runtime.read(contract, rawJson, CertificateExtractionOutput.class);
// Persist contract identity and result.violations() before returning a usable draft.
if (!result.accepted()) {
    // Apply the application's invalid-response/fallback policy.
}
```

Since 0.2.0, the default policy rejects every schema violation before JSON
binding. Omitted `output.strict` and explicit `true` have the same behavior.
Rejection returns no typed value. Schema-valid responses, including explicitly
allowed nulls, remain accepted without findings.

Repairs require an explicit choice in the contract, for example:

```yaml
output:
  format: json
  strict: false
  schema:
    type: object
    additionalProperties: false
    properties:
      title: {type: [string, 'null']}
```

The following outcomes apply only to that repairing mode and follow the
contract's restricted schema vocabulary:

| Response condition | Outcome with `output.strict: false` |
| --- | --- |
| Invalid JSON, duplicate keys, trailing content, non-object root | Reject whole response |
| Invalid nullable scalar: type, enum, date, date-time or pattern | Replace with null |
| Invalid array element, including a wrong nested field | Remove the whole element |
| Unknown object property | Remove property |
| Missing required nullable field | Fill with null |
| Missing required array | Fill with empty array |
| Missing optional field/list | No violation; generated model uses null/empty list |
| Missing other required value or wrong object/array structure | Reject whole response |
| Failure to bind the normalized tree | Reject whole response |

An explicitly allowed null is valid. A nullable type whose enum excludes null
does not permit null as a repair. Arrays retain valid elements in their original
order. Integer values such as `1.0` are canonicalized without accepting numeric
strings or fractional integers. Large integers/decimals retain precision.

Rejected results have no typed value or partially repaired tree. Accepted
results in repairing mode can still contain findings, so callers must inspect/audit
them. This choice does not change input validation, which always rejects invalid
input, or the backend's `response_format.json_schema.strict` option.

### Upgrade from the 0.1 development runtime

Previously, an omitted `output.strict` selected repairing mode. Review those
contracts before using 0.2.0: leave the field absent to require valid responses,
or explicitly set it to `false` to retain repairs. Explicit `true` and `false`
keep their behavior. Direct core callers still pass the policy explicitly to
`ContractResponseValidator.read(rawJson, strict)`.

Adding `output.strict: false` changes the authored contract and fingerprint.
For a frozen lock entry, increment the contract version and append a new entry.
For an unfrozen entry, follow your repository's baseline policy when regenerating
the lock; `promptContractsLock` never overwrites an existing fingerprint. The
runtime does not rewrite contracts, lock history or previously stored audit data.
Record the runtime version alongside contract identity when comparing old and
new runs, because an unchanged contract without `strict` now selects a different
read policy. Application audit requirements, data policy and fallback remain
the caller's responsibility.

`PromptJsonBinding.jackson2()` and `.jackson3()` use isolated strict mappers,
including Java time support and preserved timestamp offsets. Choose the binding
matching the generated models' Jackson family. Custom `PromptJsonBinding`
implementations must maintain equivalent strictness; failures become a safe
`binding` finding without the original exception or cause.
Custom bindings must also be thread-safe when the runtime is shared between
callers; the built-in bindings configure their mappers once before use.

## Findings and application policy

Each `ResponseViolation` carries a JSON Pointer, schema keyword, action
(`NULLIFIED`, `REMOVED`, `FILLED`, `REJECTED`), JSON type and length. Length is
Unicode code points for strings, member/element count for containers, scalar
text length for other values, and zero for null/missing. Array indices refer to
the original response. Unknown property names can contain personal data, so
their pointer identifies the containing object without echoing the key. Values,
parser messages and model-provided keys never enter findings. Result `toString()`
methods expose counts rather than response contents; `.value()` remains sensitive.

The registry exposes `id()`, `version()`, `versionRef()`, `fingerprint()` and
`sensitivity()` from immutable snapshots. Sensitivity is descriptive metadata.
The application owns mandatory audit, endpoint/model allowlists and data policy;
the library neither authorizes a model nor weakens those checks. Audit should
include contract identity, response schema, raw response and safe findings in
the appropriate protected records. For annotation arguments, use the generated
compile-time `VERSION_REF` constant.

## Complementary pilots

The [small standalone pilot](../examples/runtime/README.md) is used for experiments
with models, prompts and request settings on fictional public announcements.
Explicit local-model runs compare extracted facts with reviewed answers and
record finish reasons, token usage and latency. Its default demo and required
CI use deterministic responses.

The resumed `tichodroma-svc` pilot verifies application integration, dependency
compatibility, encrypted E1 audit, transactions, deadlines, circuit breakers and
fallback. Its dedicated CI uses synthetic responses and loopback HTTP; existing
E1 integration tests cover PostgreSQL boundaries. The service repository's
`doc/PROMPTJIG_RUNTIME_PILOT.md` records the scope and verification commands.

Maintain these separate acceptance criteria as the library develops. After
core/runtime publication, verify both pilots against released dependencies.
The service pilot remains opt-in until a separate reviewed adoption change;
model-quality experiments continue in the standalone example.
