# Contract format v1

The machine-readable definition is
[`prompt-contract-v1.schema.json`](../core/src/main/resources/schema/prompt-contract-v1.schema.json).
See the [complete example](../examples/gradle/src/main/resources/prompts/patient/disability/certificate-extraction.prompt.yaml).

Use one UTF-8 YAML document per `<name>.prompt.yaml`. Duplicate keys, YAML
aliases, multiple documents and unknown contract keys are rejected. Required
header fields are `promptContract: 1`, `id`, `version`, `messages`, `input` and
`output`. IDs are lowercase alphanumeric segments separated by dots or hyphens;
versions are positive integers up to 2147483647. Model options do not belong in
the contract. `description` and `sensitivity` are metadata included in the fingerprint.

Messages have role `system` or `user` and a basename ending in `.st`. Template
names use ASCII letters, digits, dots, underscores and hyphens, start with a
letter/digit and resolve within the contract's own directory with exact filename
case, including on Windows. Templates must be
valid UTF-8 and ST4 syntax with `{` / `}` delimiters. External template calls
and includes are unsupported. Conditions, functions, comments, property access
and anonymous subtemplates are inspected through the pinned ST4 4.3.4 AST.

Root names used across all messages must match `input.schema.properties`.
Unknown and unused input names are errors; a single message may use no variables.
`contract` is reserved, cannot be an input property, and need not be used. It
provides runtime output fields and enum values. Core validates its root name;
the [Spring AI runtime](spring-ai.md) supplies its contents while rendering.

Both schemas use a restricted JSON Schema Draft 2020-12 vocabulary:

- `type`: string, boolean, integer, number, object, array, or `[T, 'null']`.
- `enum`: a nonempty set of primitive values compatible with the declared type.
- `format`: date/date-time on strings; `pattern`: a valid string regular expression.
- Objects declare `properties` and `additionalProperties: false`; `required`
  names only declared properties.
- Arrays declare one `items` schema.
- `$defs` and local `$ref` references to schema locations within `$defs`.
  A `$ref` schema contains no sibling keywords. Alias-only cycles are rejected;
  concrete recursive object/array definitions remain valid.

External references, arbitrary unions and composition keywords such as
`oneOf`/`anyOf` are unsupported. Schema retrieval never uses the network.
`output.format` is `json`; `output.strict: true` rejects responses with any
schema violation. The default reader applies the documented repairs and reports
each one. Build-time tasks check definitions without making model calls.

## Frozen fingerprint framing

The fingerprint is SHA-256 over length-prefixed UTF-8/binary frames:

1. The literal identifier `promptjig-fingerprint-v1`.
2. Contract JSON with recursively sorted object keys; array order is preserved.
3. For each referenced template name in lexical order: the name frame, then its
   contents after CRLF/CR normalization to LF.

Each frame has an unsigned 8-byte big-endian byte length followed by the bytes.
The lock value is lowercase `sha256:<64 hex digits>`. Comments and YAML key
ordering do not affect the hash. Contract metadata, message order, schema array
order and instruction bytes do. This is canonicalization for format v1, not a
claim that every semantically equivalent JSON Schema has the same fingerprint.

The module-root JSON lock contains `lockVersion: 1` and a `prompts` object mapping
each ID to version-string/fingerprint entries. Keep the lock source-controlled
and outside application resources. See [version and baseline handling](gradle-plugin.md).
