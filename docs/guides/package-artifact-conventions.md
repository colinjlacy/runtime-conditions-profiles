# Package Artifact Conventions

## Status

**Non-normative implementation guidance**

This document defines the packaging convention used by declarative extension packages, SDKs, and production libraries that want Runtime Conditions generators to discover extension metadata from normal application imports.

The convention is intentionally outside the Runtime Conditions Profile document shape. A generated profile never embeds `runtimeconditions.bindings.yaml`, `runtimeconditions.package.yaml`, or package-local extension files; these artifacts exist only to help generators map source code to profile Conditions.

---

# 1. Package Artifact Types

First-party tooling recognizes three package-adjacent files:

| File | Artifact | Purpose |
| ---- | -------- | ------- |
| `runtimeconditions.extension.yaml` | Extension definition | Contains a `RuntimeConditionsExtensionDefinition` document. Its allowed fields are defined by the core draft: `apiVersion`, `kind`, `metadata.id`, and `spec`. |
| `runtimeconditions.bindings.yaml` | Binding manifest | Maps declarative helper APIs to extension-owned Condition vocabulary. This is a first-party tooling convention, not profile vocabulary. |
| `runtimeconditions.package.yaml` | Package manifest | Maps SDK or production library APIs to extension-owned Condition vocabulary. This is a first-party tooling convention, not profile vocabulary. |

The core draft defines the extension definition document shape. This guide only names where a package should place that document for first-party generator discovery.

An imported declarative extension package SHOULD include a binding manifest in the package directory that exposes its declaration APIs.

An imported SDK or production library package SHOULD include a package manifest in the package directory that exposes the mapped API surface.

Both manifest types MUST identify a Runtime Conditions extension definition. That extension definition is a standalone artifact: it can be used without the code package, and these manifests only map source symbols to that extension vocabulary.

Published packages that follow first-party tooling conventions SHOULD ship the extension definition next to whichever manifest they provide:

```text
runtimeconditions.extension.yaml
```

Declarative extension package example:

```text
declarations/
  declarations.go
  runtimeconditions.bindings.yaml
  runtimeconditions.extension.yaml
```

SDK or production library package example:

```text
service/s3/
  client.go
  runtimeconditions.package.yaml
  runtimeconditions.extension.yaml
```

The manifest identifies language-specific source symbols. The extension definition identifies the Condition vocabulary those symbols require.

## 1.1 Optional Files

Package authors MAY also include:

```text
README.md
fixtures/
testdata/
runtimeconditions.schema.json
```

Fixtures and tests are strongly recommended for packages with non-trivial mappings.

---

# 2. Package Manifest Shape

The package manifest uses this envelope:

```yaml
apiVersion: runtimeconditions.io/v1alpha1
kind: RuntimeConditionsPackage

metadata:
  package: <language package identifier>
  language: <language id>

extension:
  id: <extension id URI>
  definition: <optional vendored or local override path>

<language-specific section>: {}
```

Required fields:

| Field | Required | Description |
| ----- | -------- | ----------- |
| `apiVersion` | YES | Runtime Conditions API version |
| `kind` | YES | Must be `RuntimeConditionsPackage` |
| `metadata.package` | YES | Language package identity |
| `metadata.language` | YES | Language id such as `go`, `python`, `javascript`, or `typescript` |
| `extension.id` | YES | Exact extension identifier used by generated profiles |
| `extension.definition` | NO | Package-manifest override path for vendored or local development layouts |

The package MAY include one or more language-specific sections. A generator ignores sections for languages it does not support.

When `extension.definition` is omitted, first-party tooling loads `runtimeconditions.extension.yaml` from the same package artifact as the manifest. When it is present, the path is resolved relative to `runtimeconditions.package.yaml` and must point to an extension definition whose `metadata.id` exactly matches `extension.id`.

---

# 3. Binding Manifest Shape

A declarative extension package binding manifest uses this envelope:

```yaml
apiVersion: runtimeconditions.io/v1alpha1
kind: RuntimeConditionsBinding

metadata:
  extension: <extension id URI>
  extensionDefinition: <optional vendored or local override path>
  language: <language id>

<language-specific section>: {}
```

Required fields:

| Field | Required | Description |
| ----- | -------- | ----------- |
| `apiVersion` | YES | Runtime Conditions API version |
| `kind` | YES | Must be `RuntimeConditionsBinding` |
| `metadata.extension` | YES | Exact extension identifier used by generated profiles |
| `metadata.extensionDefinition` | NO | Binding-manifest override path for vendored or local development layouts |
| `metadata.language` | YES | Language id; must match the generator language |

The binding manifest maps declarative helper functions to extension-owned vocabulary. It does not define vocabulary itself.

When `metadata.extensionDefinition` is omitted, first-party tooling loads `runtimeconditions.extension.yaml` from the same package artifact as the manifest. When it is present, the path is resolved relative to `runtimeconditions.bindings.yaml` and must point to an extension definition whose `metadata.id` exactly matches `metadata.extension`.

Override paths are manifest-specific: `RuntimeConditionsPackage` uses `extension.definition`, and `RuntimeConditionsBinding` uses `metadata.extensionDefinition`. Published packages should prefer package-local `runtimeconditions.extension.yaml`; override paths are mainly for vendored layouts, local development, and fixtures.

---

# 4. Go Section

The current example implements a Go section.

```yaml
go:
  importPath: github.com/colinjlacy/runtime-conditions-profiles/examples/sdks/aws-sdk-go-v2/service/s3
  package: s3

  constructors:
    - function: NewFromConfig
      receiver: Client

  declarations:
    - receiver: Client
      method: PutObject
      name: s3-object-store
      kind: aws.object_store
      interfaceType: aws.s3
      values:
        - target: interface.bucketClass
          value: standard
      configuration:
        env:
          - property: bucket
            name: AUDIT_LOG_BUCKET
          - property: region
            name: AWS_REGION
          - property: accessKeyId
            name: AWS_ACCESS_KEY_ID
            sensitive: true
          - property: secretAccessKey
            name: AWS_SECRET_ACCESS_KEY
            sensitive: true
```

Fields:

| Field | Required | Description |
| ----- | -------- | ----------- |
| `go.importPath` | YES | Go import path that identifies the package |
| `go.package` | NO | Default package name used when the import has no local alias |
| `go.constructors` | NO | Functions that construct typed SDK clients |
| `go.declarations` | NO | Source call mappings that emit Conditions |
| `go.options` | NO | Package-level option mappings that augment compatible declarations |

At least one of `go.declarations` or `go.options` must be present.

Constructor fields:

| Field | Required | Description |
| ----- | -------- | ----------- |
| `function` | YES | Function name called from the imported package |
| `receiver` | YES | Client or receiver type produced by the constructor |

Declaration fields:

| Field | Required | Description |
| ----- | -------- | ----------- |
| `receiver` | NO | Receiver type for method calls |
| `method` | NO | Receiver method name |
| `function` | NO | Package-level function name |
| `name` | NO | Static Condition name |
| `nameArg` | NO | Zero-based argument index containing a static Condition name |
| `kind` | YES | Condition `kind` value |
| `interfaceType` | YES | `interface.type` value |
| `values` | NO | Static field values to include in the generated Condition |
| `options` | NO | Nested function calls that provide field values |
| `configuration` | NO | Static workload-facing configuration mappings to include in the generated Condition |

At least one of `method` or `function` must be present. A declaration should provide either `name` or `nameArg`.

Static field values:

```yaml
values:
  - target: interface.bucketClass
    value: standard
```

Nested options:

```yaml
options:
  - function: BucketClass
    target: interface.bucketClass
    valueArg: 0
```

Nested options are useful for no-op declaration packages. Operation manifests for SDKs and production libraries usually prefer static values or future language-specific extraction rules.

Configuration mappings let package authors surface hidden package inputs without emitting concrete values. For example, an SDK can state that generated S3 Conditions require a `bucket`, `region`, `accessKeyId`, and `secretAccessKey`, and can name the environment variables that the workload expects for those inputs. The generated profile carries the names; adapters map those properties to platform-provided ConfigMaps, Secrets, service URLs, or other delivery mechanisms.

Package-level options let additive extension packages contribute fields to declarations owned by another package. For example, the Environment Configuration Go package exports only env option functions:

```yaml
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
      appliesToInterfaceTypes:
        - http
        - relational
        - document
        - key_value
      stringArgs:
        property: 0
        name: 1
      options:
        - function: Sensitive
          target: env.sensitive
          value: "true"
        - function: Optional
          target: env.required
          value: "false"

    - function: EnvAlternative
      target: configuration.alternatives[]
      appliesToKinds:
        - api
        - datastore
        - cache
      appliesToInterfaceTypes:
        - http
        - relational
        - document
        - key_value
      options:
        - function: Env
          target: configuration.env[]
          stringArgs:
            property: 0
            name: 1
          options:
            - function: Sensitive
              target: env.sensitive
              value: "true"
            - function: Optional
              target: env.required
              value: "false"
```

A generator applies a package-level option only when the option call appears inside a compatible declaration call. If a package is imported but none of its options are applied to generated Conditions, its extension is not emitted in the profile.

---

# 5. Language Placement Conventions

Generators SHOULD use language-native package resolution and then check conventional manifest locations. They SHOULD NOT recursively scan dependency trees looking for arbitrary manifest files.

The current Go generator supports these conventions. The Python, JavaScript, and TypeScript paths below describe the intended package-resolution convention for future language support.

## 5.1 Go

For a Go import:

```go
import "github.com/colinjlacy/runtime-conditions-profiles/examples/sdks/aws-sdk-go-v2/service/s3"
```

The generator resolves the import path to a package directory and checks:

```text
<resolved package directory>/runtimeconditions.bindings.yaml
<resolved package directory>/runtimeconditions.package.yaml
```

In local development, `go.mod` `replace` directives can resolve a module to a local directory.

## 5.2 Python

For Python imports:

```python
import boto3
from vendor_sdk.s3 import Client
```

A Python generator should resolve the imported distribution or package directory and check:

```text
<package directory>/runtimeconditions.bindings.yaml
<package directory>/runtimeconditions.package.yaml
```

For wheels, the manifest should be included as package data in the imported package or distribution metadata.

## 5.3 JavaScript and TypeScript

For JavaScript or TypeScript imports:

```ts
import { S3Client, PutObjectCommand } from "@aws-sdk/client-s3";
```

A generator should resolve the npm package root using normal Node package resolution and check:

```text
<package root>/runtimeconditions.bindings.yaml
<package root>/runtimeconditions.package.yaml
```

For subpath exports, a generator MAY also check:

```text
<resolved subpath directory>/runtimeconditions.bindings.yaml
<resolved subpath directory>/runtimeconditions.package.yaml
```

This convention does not require a `package.json` property.

---

# 6. Extension Definition Relationship

The manifest's extension identifier must match the extension definition it resolves:

- `RuntimeConditionsPackage` compares `extension.id` with the extension definition `metadata.id`.
- `RuntimeConditionsBinding` compares `metadata.extension` with the extension definition `metadata.id`.

Given:

```yaml
extension:
  id: https://aws.example.com/runtimeconditions/object-store/v1alpha1/runtimeconditions.extension.yaml
```

The resolved `runtimeconditions.extension.yaml`, or the file reached through an allowed override path, should contain:

```yaml
metadata:
  id: https://aws.example.com/runtimeconditions/object-store/v1alpha1/runtimeconditions.extension.yaml
```

The extension definition owns vocabulary and dependencies:

```yaml
spec:
  kinds:
    - name: aws.object_store

  interfaceTypes:
    - name: aws.s3
      targetKind: aws.object_store

  conditionFields:
    - name: configuration
      appliesToKinds:
        - aws.object_store
      appliesToInterfaceTypes:
        - aws.s3
```

The package or binding manifest maps source symbols to that vocabulary. It does not define vocabulary itself.

If a manifest emits `configuration`, the resolved extension definition should define that configuration field in the relevant scope, or depend on another extension that defines the configuration shape it uses.

---

# 7. Safety Rules

Generators MUST treat binding and package manifests as static metadata. They MUST NOT execute package code to discover Conditions.

Binding and package manifests SHOULD NOT instruct generators to read:

- Secret values
- Environment variable values
- Runtime network responses
- Cloud account state
- Customer data
- Target environment configuration

Generators MAY read literal source values, constants, type names, method names, and import paths when a manifest explicitly maps those source constructs to non-secret profile fields.

---

# 8. Compatibility

Manifest compatibility is governed by `apiVersion`.

Generators SHOULD ignore unknown manifest fields when they can still interpret the required fields safely. Generators MUST fail with diagnostics when:

- `kind` is unsupported
- `extension.id` is missing
- `metadata.extension` is missing
- The resolved extension definition cannot be loaded
- The language section required for the current generator is missing
- A declaration cannot be interpreted safely

Future manifest versions may add richer language-specific extraction rules without changing the Runtime Conditions Profile document shape.
