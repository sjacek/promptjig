---
title: Contract reference
permalink: /contracts/
---

Use one UTF-8 YAML document per `<name>.prompt.yaml`, alongside its named `.st`
templates. [Your first contract](getting-started.md) is a
complete example. The machine-readable definition is the repository's
[format-v1 schema](https://github.com/sjacek/promptjig/blob/main/core/src/main/resources/schema/prompt-contract-v1.schema.json).

## Header and messages

| Field | Meaning |
| --- | --- |
| `promptContract` | Format version; currently `1` |
| `id` | Starts with a lowercase letter; lowercase alphanumeric segments separated by dots or hyphens |
| `version` | Positive integer, maximum 2147483647 |
| `description` | Optional contract metadata; included in the fingerprint |
| `sensitivity` | Optional metadata; included in the fingerprint; applications enforce its policy |
| `messages` | Ordered `system`/`user` messages referencing template basenames |
| `input.schema` | Object schema for named template input |
| `output.format` | `json` |
| `output.strict` | Runtime response policy; see below |
| `output.schema` | Expected JSON response schema |

Duplicate YAML keys, aliases, multiple documents and unknown contract keys fail.
Model IDs, credentials, temperature, token limits and transport options belong
in application configuration, not the contract.

Template names end in `.st`, use ASCII letters/digits/dots/underscores/hyphens,
and start with a letter or digit. They resolve within the contract's own directory
with exact filename case, including on Windows. External template calls and
includes are unsupported.

## Template parameters

ST4 templates use `{` and `}` delimiters. Root input names across all messages
must match `input.schema.properties`; unknown and unused names are errors.
A message can contain no input variables if the other messages use them.

`contract` is reserved and cannot be an input property. The runtime supplies:

| Attribute | Value |
| --- | --- |
| `contract.output.fields` | Top-level output names in lexical order |
| `contract.output.enums.<field>` | Non-null enum values in schema order |
| `contract.output.fieldText` | Output names joined by comma-space |
| `contract.output.enumText.<field>` | Enum values joined by comma-space |

Array enums are taken from `items`; local references are resolved. Conditions,
functions and anonymous subtemplates use supported ST4 syntax. Nested property
spelling inside maps remains ST4's responsibility. Input containing braces is
passed as data and is not evaluated again as template instructions.

## Supported schemas

Both schemas use a restricted JSON Schema Draft 2020-12 vocabulary:

- `type`: string, boolean, integer, number, object, array, or one such type plus
  null, for example `[string, 'null']`.
- `enum`: a nonempty set of primitive values compatible with the type.
- String `format`: `date` or `date-time`; `pattern`: a valid regular expression.
- Objects: `properties`, `required` and `additionalProperties: false`.
- Arrays: one `items` schema.
- `$defs` and local `$ref` references into `$defs`; a `$ref` has no sibling keywords.

External references, arbitrary unions and composition such as `oneOf`/`anyOf`
are unsupported. Alias-only reference cycles fail; concrete recursive objects
and arrays are supported. Schema loading never retrieves resources from the network.

## Strict and repairing responses

With runtime {{ site.releases.runtime }}, omitted `output.strict` and explicit
`true` reject every schema violation before binding to Java. Schema-valid
responses, including explicitly allowed nulls, are accepted without findings.

Set `output.strict: false` explicitly to allow documented repairs and inspect
every finding. Invalid JSON, duplicate keys, trailing text and incompatible root
structures still fail. See the [repair table](spring-ai.md#repairing-mode).

Backend structured-output strictness is a separate model-client option.
The contract's `strict` controls response reading; it does not configure transport.

## What changes the fingerprint

Contract metadata, schemas, message order and referenced instruction bytes affect
the fingerprint. YAML comments and key ordering do not; template line endings
are normalized to LF. Moving a contract without changing its content does not
require inventing a new identity, but its packaged resource path must still match
the generated constants.

Keep the module's append-only JSON lock outside resources. Changing locked
content requires a [new version](versioning.md).
