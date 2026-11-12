# Runtime validator compatibility — service pilot

The stage-3 certificate pilot in `tichodroma-svc` exposed a binary dependency
conflict. Promptjig's standalone core resolves Networknt JSON Schema Validator
2.0.8. Spring AI 2.0.2 ChatClient brings 3.0.1 under the same Maven coordinates.
The service resolves 3.0.1, whose Jackson-3 `JsonNode` overload cannot satisfy
Promptjig's call to `SchemaRegistry.getSchema(com.fasterxml.jackson.databind.JsonNode)`.
The registry failed during static initialization before startup could complete.

## Fix

`SchemaValidation.compile` and the new `SchemaValidation.validate` helper use
Networknt's common `String`/`InputFormat.JSON` API. The rest of the core retains
its existing Jackson-2 tree contract and safe diagnostic translation. The
already-parsed trees are serialized at the validator boundary; no original
parser messages or response values are added to findings.

The additional precision tests exposed rounding in the default text reader:
distinct decimals beyond `double` precision could satisfy the same numeric enum.
`NativeSchemaJson` now supplies a private reader mapper with
`USE_BIG_DECIMAL_FOR_FLOATS` enabled. It discovers the resolved mapper family
from the native reader builder's parameter type and confines reflection to
mapper construction/configuration. Validation itself still uses the public
text API. No global Networknt mapper is reconfigured, and standalone core users
do not gain a required Jackson-3 dependency.

The application can resolve either validator family. There is no service-wide
downgrade, forced production dependency or change to generated response models.
Core/spike call sites and schema tests consistently use the bridge. Two existing
line-ending tests also normalize an already-CRLF checkout before constructing
CRLF fixtures, avoiding accidental `CR CR LF` on Windows.

## Regression checks

`core:testValidator3` runs the complete core suite against Networknt 3.0.9; the
ordinary core task retains 2.0.8. Runtime tests now include the actual Spring AI
ChatClient dependency, reproducing the host's 3.x selection. Catalog aliases
keep both the compatibility version and ChatClient dependency explicit.
`ChatClientCompatibilityTest` also executes the actual
`StructuredOutputValidationAdvisor`: its Jackson-3 node overloads must work in
the same classpath as Promptjig's validation and both JSON bindings. A separate
case checks precise decimal repair under the host's older Networknt 3.0.1.

The bridge tests cover root JSON kinds, Unicode/escapes, exact integers and
decimals, input immutability, compiled-schema snapshots, concurrent reads and
external-reference rejection. The last test exposes a loopback schema server
and verifies that it receives zero requests.

On 2026-11-12, the full `gradlew.bat build --max-workers=2` passed on Windows,
JDK 25.0.1, with Java 17 compilation targets:

| Suite | Executions | Failures/errors |
| --- | ---: | ---: |
| Core / Networknt 2.0.8 | 123 | 0 |
| Core / Networknt 3.0.9 | 123 | 0 |
| Spring AI runtime + ChatClient | 31 | 0 |
| Gradle TestKit | 19 | 0 |
| Release/compatibility workflows | 20 | 0 |
| Total | 316 | 0 |

The focused schema/ChatClient checks were also run on JDK 17.0.21: 16 schema
tests with each validator family and 3 ChatClient compatibility tests, for 35
successful executions. Core Javadoc passed on both JDK 17 and 25. The full
JDK-25 results were retained in `build/comments-tests-jdk25-results.json` before
the filtered JDK-17 runs replaced the standard XML reports.

Javadoc, plugin validation, staging packaging and the Java-21 spike compilation
also passed. This does not repeat the remote Gradle/JDK matrix or publish a new
artifact. The fix belongs to the local `fix/runtime-validator-compatibility`
worktree; it is not included in the submitted `v0.1.0` tag. The service pilot
must explicitly select this checkout until the fix is incorporated and released.

## Low-level API usage

Direct schema callers should use the portable pair:

```java
var compiled = SchemaValidation.compile(schema);
var errors = SchemaValidation.validate(compiled, value);
```

Calling the native `compiled.validate(JsonNode)` overload binds the caller to
one Jackson family. `SchemaValidation.registry` exposes native configuration;
use its text overloads when working with it directly. Native error messages may
contain values. Applications needing safe findings should use
`ContractResponseValidator` or `PromptRuntime.read`, which translate findings
into pointers, rules, actions, types and lengths.
