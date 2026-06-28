# Extension Authoring Guide

## Status

**Non-normative implementation guidance**

This guide documents the repository convention for writing Runtime Conditions extension definitions. The core profile draft defines the extension document shape. This guide explains how extension authors should use that shape so extensions compose cleanly.

---

# 1. Ownership

An extension definition owns only the vocabulary it introduces.

An extension MAY define:

- Condition kinds
- Interface types
- Condition fields
- Interface fields
- Allowed field values
- JSON Schema validation rules

An extension MUST NOT redefine vocabulary owned by another extension. If it needs to build on that vocabulary, it must declare a dependency and reference the dependency-owned kind, interface type, or field in scoped definitions.

For first-party tooling support, the resolved extension set must contain exactly one definition for each vocabulary item in its scope. Two definitions conflict even if they are textually identical.

The scope of a vocabulary item is part of its identity:

- A kind is scoped by its `name`.
- An interface type is scoped by `targetKind` and `name`.
- An interface field is scoped by `targetKind`, `targetType`, and `name`.
- A condition field is scoped by `name`, `appliesToKinds`, and `appliesToInterfaceTypes`.
- A field value definition is scoped by `field`, `targetKind`, and `targetType`.

Condition fields with the same `name` can coexist only when their kind/type scopes do not overlap. A kind-wide condition field overlaps every narrower interface-type condition field for that kind. The same field name can be reused for unrelated kinds, or for distinct interface types under the same kind when neither definition is kind-wide.

---

# 2. Base Extensions

A base extension introduces vocabulary directly.

The Common Integrations extension owns common application integration vocabulary:

```yaml
apiVersion: runtimeconditions.io/v1alpha1
kind: RuntimeConditionsExtensionDefinition

metadata:
  id: https://runtimeconditions.io/extensions/common-integrations/v1alpha1/runtimeconditions.extension.yaml

spec:
  kinds:
    - name: api
    - name: datastore
    - name: cache

  interfaceTypes:
    - name: http
      targetKind: api
    - name: relational
      targetKind: datastore
    - name: document
      targetKind: datastore
    - name: key_value
      targetKind: cache
```

Because this extension owns `api`, `datastore`, `cache`, `http`, `relational`, `document`, and `key_value`, other extensions must not redefine them.

---

# 3. Additive Extensions

An additive extension builds on dependency-owned vocabulary without copying it.

The Environment Configuration extension adds the `configuration` field to common integration Conditions. It does not redefine common's kinds or interface types:

```yaml
apiVersion: runtimeconditions.io/v1alpha1
kind: RuntimeConditionsExtensionDefinition

metadata:
  id: https://runtimeconditions.io/extensions/env-configuration/v1alpha1/runtimeconditions.extension.yaml

spec:
  dependencies:
    - https://runtimeconditions.io/extensions/common-integrations/v1alpha1/runtimeconditions.extension.yaml

  conditionFields:
    - name: configuration
      appliesToKinds:
        - api
      appliesToInterfaceTypes:
        - http
    - name: configuration
      appliesToKinds:
        - datastore
      appliesToInterfaceTypes:
        - relational
        - document
    - name: configuration
      appliesToKinds:
        - cache
      appliesToInterfaceTypes:
        - key_value
```

The dependency makes the referenced common vocabulary available. The additive
extension owns `configuration` only in the listed kind and interface-type scopes,
plus the rules for values inside that field.

---

# 4. Field Values

Use `fieldValues` to define allowed values for extension-owned fields in a specific vocabulary scope.

```yaml
fieldValues:
  - field: configuration.env[].property
    targetKind: api
    targetType: http
    values:
      - url
      - baseUrl
      - hostname
      - port
      - scheme
      - username
      - password
      - token
      - tls

  - field: configuration.env[].property
    targetKind: cache
    targetType: key_value
    values:
      - url
      - hostname
      - port
      - scheme
      - username
      - password
      - database
      - token
      - tls
```

The field path is owned by the additive extension. The target kind and interface type may come from the same extension or from a declared dependency.

For first-party tooling support, a `fieldValues.field` path must resolve to a field that is valid in the declared `targetKind` and optional `targetType` scope:

- Paths beginning with `interface.` must target an `interfaceFields` entry for that exact `targetKind` and `targetType`, except `interface.type`, which targets an interface type value.
- Paths beginning with a condition field name, such as `configuration.` or `trust.`, must target a `conditionFields` entry with the same name whose scope includes the declared `targetKind` and `targetType`.
- `targetType` must identify an interface type that is valid for `targetKind` when `targetType` is present.
- Values inside one `fieldValues` entry must be unique.

This allows unusual field paths such as `access.identity.kind`, `validation.nameConstraints.mode`, `interface.revocation.method`, or `interface.flows[].grantType`, as long as the first path segment is owned and scoped by the extension or one of its dependencies.

---

# 5. Schemas

Extension schemas should validate the fields owned by that extension and leave unrelated fields open.

```yaml
schemas:
  - id: configuration-shape
    description: Validates the environment configuration field shape.
    schema:
      $schema: https://json-schema.org/draft/2020-12/schema
      type: object
      properties:
        configuration:
          type: object
          oneOf:
            - required:
                - env
            - required:
                - alternatives
      additionalProperties: true
```

Use `appliesToKind` and `appliesToInterfaceType` when a schema is valid only for one target scope:

```yaml
schemas:
  - id: configuration-properties-cache-key-value
    appliesToKind: cache
    appliesToInterfaceType: key_value
    description: Validates allowed environment configuration properties for key/value cache integrations.
    schema:
      type: object
      properties:
        configuration:
          type: object
      additionalProperties: true
```

Schemas from multiple resolved extensions apply additively. A Condition must satisfy every schema whose scope matches it.

For first-party tooling support, `schemas[].appliesToKind` must resolve to exactly one kind in the resolved extension set. If `schemas[].appliesToInterfaceType` is present, it must resolve to exactly one interface type for `schemas[].appliesToKind`.

---

# 6. Go Declaration Packages

Go declaration packages should mirror extension ownership.

A base extension package exports the declarations and option types for the vocabulary it owns:

```go
package commonintegrations

type APIOption interface {
	CommonIntegrationsAPIOption()
}

type CacheOption interface {
	CommonIntegrationsCacheOption()
}

func API(name string, options ...APIOption) Declaration {
	return Declaration{}
}

func Cache(name string, options ...CacheOption) Declaration {
	return Declaration{}
}
```

An additive extension package exports only its own options. It may import a base package so its options can satisfy the base package's marker interfaces:

```go
package envconfiguration

import common "github.com/colinjlacy/runtime-conditions-profiles/extensions/common-integrations/go"

type ConditionOption interface {
	common.APIOption
	common.DatastoreOption
	common.CacheOption
	envConfigurationConditionOption()
}

type conditionOption struct{}

func (conditionOption) CommonIntegrationsAPIOption()       {}
func (conditionOption) CommonIntegrationsDatastoreOption() {}
func (conditionOption) CommonIntegrationsCacheOption()     {}
func (conditionOption) envConfigurationConditionOption()   {}

func Env(property, name string, options ...EnvOption) ConditionOption {
	return conditionOption{}
}
```

Application code imports both packages when it uses both extensions:

```go
import (
	common "github.com/colinjlacy/runtime-conditions-profiles/extensions/common-integrations/go"
	env "github.com/colinjlacy/runtime-conditions-profiles/extensions/env-configuration/go"
)

var _ = common.Cache("request-cache",
	common.KeyValue(common.Redis),
	env.EnvAlternative(env.Env("url", "REDIS_URL")),
)
```

The generated profile lists both extensions because both packages directly contributed vocabulary:

```yaml
extensions:
  - https://runtimeconditions.io/extensions/common-integrations/v1alpha1/runtimeconditions.extension.yaml
  - https://runtimeconditions.io/extensions/env-configuration/v1alpha1/runtimeconditions.extension.yaml
```

If a workload imports only `common-integrations/go`, the profile lists only `common-integrations`. If it imports `env-configuration/go` but does not apply an env option to a Condition, the profile does not list `env-configuration`.

Adapters and validators still resolve transitive extension dependencies from extension definitions. Direct profile declarations and dependency resolution are separate steps.

---

# 7. Declarative Code Package Bindings

An extension-side declarative code package uses `runtimeconditions.bindings.yaml`.
That binding manifest maps source calls back to extension-owned profile
vocabulary.

For first-party tooling support:

- `runtimeconditions.bindings.yaml` must use `kind: RuntimeConditionsBinding`.
- `metadata.extension` must match the extension definition `metadata.id`.
- The package should include `runtimeconditions.extension.yaml` next to the binding manifest, unless the extension definition is intentionally vendored elsewhere in the same package artifact or supplied by a local development override.
- `metadata.extensionDefinition` is a vendored or local development override; when present, it must resolve to the extension definition file.
- `metadata.language` must identify the language section used by the binding.
- The language-specific package identity fields are required.
- At least one language-specific declaration or option mapping is required.

This Go example shows a base declaration package mapping source calls to Conditions. The same manifest structure is intended for future language sections, with the language-specific mapping stored under that language's section.

```yaml
apiVersion: runtimeconditions.io/v1alpha1
kind: RuntimeConditionsBinding

metadata:
  extension: https://runtimeconditions.io/extensions/common-integrations/v1alpha1/runtimeconditions.extension.yaml
  language: go

go:
  importPath: github.com/colinjlacy/runtime-conditions-profiles/extensions/common-integrations/go
  package: commonintegrations

  declarations:
    - function: Cache
      nameArg: 0
      kind: cache
      options:
        - function: KeyValue
          target: interface.type
          value: key_value
          engineArg: 0
```

This Go example shows an option-only extension mapping source calls to fields that can augment compatible Conditions:

```yaml
apiVersion: runtimeconditions.io/v1alpha1
kind: RuntimeConditionsBinding

metadata:
  extension: https://runtimeconditions.io/extensions/env-configuration/v1alpha1/runtimeconditions.extension.yaml
  language: go

go:
  importPath: github.com/colinjlacy/runtime-conditions-profiles/extensions/env-configuration/go
  package: envconfiguration

  options:
    - function: Env
      target: configuration.env[]
      appliesToKinds:
        - api
        - datastore
        - cache
      stringArgs:
        property: 0
        name: 1
```

Generators use language-specific option mappings only when an option call appears inside a compatible declaration call. Standalone option calls are ignored for profile emission.

A declaration entry must map to extension-resolved vocabulary:

- `kind` must resolve to exactly one kind.
- `interfaceType`, when present, must resolve to exactly one interface type for `kind`.
- `values` must target supported binding targets and defined field values.
- A declaration may name either a package-level `function` or a receiver `method`.

A package-level option entry must map to fields that are valid for the declaration scope or for the option's `appliesToKinds` and `appliesToInterfaceTypes` scope. For configuration-style targets, the target field and the configured property field must both be defined in that same scope.

The declarative code package must match the binding manifest. In the current Go implementation, that means:

- The parsed Go package name must match `go.package`.
- Every manifest constant must exist in Go and have the same string value.
- Every package-level declaration, option, and constructor function named in the manifest must exist.
- Every receiver method named in the manifest must exist on the named receiver type.
- `nameArg` and entries in `stringArgs` must point to existing `string` parameters.
- `valueArg` and `engineArg` must point to existing parameters.
- `typeArg` must point to an existing type parameter.
- Nested options are validated recursively.

Use the static validator before treating an extension as first-party tooling-ready:

```sh
cd go/profiler
go run . validate-extension -root ../../extensions/env-configuration
```

The validator loads the target extension definition, resolves dependency extension definitions from the provided package or development roots, checks the binding manifest against the resolved vocabulary, and checks that the declarative code package contains the functions, methods, constructors, constants, and argument positions named by the binding manifest.

When validating a single extension directory in this repository, sibling extension directories are included as a local development convenience. Published packages should not depend on repository sibling layout; they should package their own extension definition and declare exact dependency identifiers.

---

# 8. Authoring Checklist

- Define only vocabulary your extension owns.
- Declare dependencies for vocabulary you reference but do not own.
- Scope additive fields to the dependency-owned kinds and interface types they augment.
- Avoid condition field scope overlap unless the definitions are intentionally the same owned definition.
- Use `fieldValues` for portable, adapter-visible enums.
- Make every `fieldValues.field` path resolve to a condition or interface field in the declared target scope.
- Use JSON Schema for machine-readable validation.
- Scope schemas only to kinds and interface types that resolve in the extension's dependency graph.
- Keep schemas focused on your extension's fields and allow unrelated properties.
- Export only declaration functions for vocabulary your package owns.
- For additive declarative packages, export helper APIs that are compatible with the base declaration package contracts.
- Describe additive options with binding-level language mappings such as `go.options`.
- Keep `runtimeconditions.bindings.yaml` identity, package names, symbols, constants, and argument indexes synchronized with the language package.
- Run `go run . validate-extension -root <extension-dir>` before publishing first-party tooling support.
- Do not encode secrets, concrete target-environment values, or provider-specific fulfillment choices.
