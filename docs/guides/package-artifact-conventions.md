# Package Artifact Conventions

## Status

**Non-normative implementation guidance**

This document defines the packaging convention used by SDKs and libraries that want Runtime Conditions generators to discover extension metadata from normal application imports.

The convention is intentionally outside the Runtime Conditions Profile document shape. A generated profile never embeds `runtimeconditions.package.yaml`; the manifest exists only to help generators map source code to profile Conditions.

---

# 1. Required Files

An imported library that supports Runtime Conditions discovery SHOULD include a package manifest in the runtime-relevant package directory:

```text
runtimeconditions.package.yaml
```

The package manifest MUST reference a Runtime Conditions extension definition. That extension definition is a standalone artifact: it can be used without the SDK, and the SDK manifest only maps SDK symbols to that extension vocabulary.

An SDK or library can reference an extension definition shipped inside the package, elsewhere in the same module or repository, or in a configured extension catalog. For local files, `extension.definition` is resolved relative to the package manifest.

Example:

```text
examples/extensions/aws-object-store/
  aws-object-store-v1alpha1.yaml

service/s3/
  client.go
  runtimeconditions.package.yaml
```

The package manifest identifies language-specific source symbols. The extension definition identifies the Condition vocabulary those symbols require.

## 1.1 Optional Files

SDK authors MAY also include:

```text
README.md
fixtures/
testdata/
runtimeconditions.schema.json
```

Fixtures and tests are strongly recommended for SDKs with non-trivial mappings.

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
  id: <extension-uri>:<extension-version>
  definition: <relative path to extension definition>

<language-specific section>: {}
```

Required fields:

| Field | Required | Description |
| ----- | -------- | ----------- |
| `apiVersion` | YES | Runtime Conditions API version |
| `kind` | YES | Must be `RuntimeConditionsPackage` |
| `metadata.package` | YES | Language package identity |
| `metadata.language` | YES | Language id such as `go`, `python`, `java`, `javascript`, or `typescript` |
| `extension.id` | YES | Exact extension identifier used by generated profiles |
| `extension.definition` | YES | Relative path to a resolvable extension definition |

The package MAY include one or more language-specific sections. A generator ignores sections for languages it does not support.

---

# 3. Go Section

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

Nested options are useful for no-op declaration packages. SDK operation manifests usually prefer static values or future language-specific extraction rules.

Configuration mappings let SDK authors surface hidden SDK inputs without emitting concrete values. For example, an SDK can state that generated S3 Conditions require a `bucket`, `region`, `accessKeyId`, and `secretAccessKey`, and can name the environment variables that the workload expects for those inputs. The generated profile carries the names; adapters map those properties to platform-provided ConfigMaps, Secrets, service URLs, or other delivery mechanisms.

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

# 4. Language Placement Conventions

Generators SHOULD use language-native package resolution and then check conventional manifest locations. They SHOULD NOT recursively scan dependency trees looking for arbitrary manifest files.

## 4.1 Go

For a Go import:

```go
import "github.com/colinjlacy/runtime-conditions-profiles/examples/sdks/aws-sdk-go-v2/service/s3"
```

The generator resolves the import path to a package directory and checks:

```text
<resolved package directory>/runtimeconditions.package.yaml
```

In local development, `go.mod` `replace` directives can resolve a module to a local directory.

## 4.2 Python

For Python imports:

```python
import boto3
from vendor_sdk.s3 import Client
```

A Python generator should resolve the imported distribution or package directory and check:

```text
<package directory>/runtimeconditions.package.yaml
```

For wheels, the manifest should be included as package data in the runtime package or distribution metadata.

## 4.3 JavaScript and TypeScript

For JavaScript or TypeScript imports:

```ts
import { S3Client, PutObjectCommand } from "@aws-sdk/client-s3";
```

A generator should resolve the npm package root using normal Node package resolution and check:

```text
<package root>/runtimeconditions.package.yaml
```

For subpath exports, a generator MAY also check:

```text
<resolved subpath directory>/runtimeconditions.package.yaml
```

This convention does not require a `package.json` property.

## 4.4 Java

For Java dependencies, the manifest should be packaged as a classpath resource:

```text
META-INF/runtimeconditions/runtimeconditions.package.yaml
META-INF/runtimeconditions/<extension-name>-<version>.yaml
```

A Java generator should resolve imported or referenced artifacts through the build system classpath and inspect only the matching artifacts.

---

# 5. Extension Definition Relationship

The manifest's `extension.id` must match the referenced extension definition.

Given:

```yaml
extension:
  id: https://aws.example.com/runtimeconditions/object-store:v1alpha1
  definition: ../../../../extensions/aws-object-store/aws-object-store-v1alpha1.yaml
```

The referenced extension definition should contain:

```yaml
metadata:
  uri: https://aws.example.com/runtimeconditions/object-store
  version: v1alpha1
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

The package manifest maps source symbols to that vocabulary. It does not define vocabulary itself.

If a package manifest emits `configuration`, the referenced extension definition should define that configuration field in the relevant scope, or depend on another extension that defines the configuration shape it uses.

---

# 6. Safety Rules

Generators MUST treat package manifests as static metadata. They MUST NOT execute package code to discover Conditions.

Package manifests SHOULD NOT instruct generators to read:

- Secret values
- Environment variable values
- Runtime network responses
- Cloud account state
- Customer data
- Target environment configuration

Generators MAY read literal source values, constants, type names, method names, and import paths when a manifest explicitly maps those source constructs to non-secret profile fields.

---

# 7. Compatibility

Manifest compatibility is governed by `apiVersion`.

Generators SHOULD ignore unknown manifest fields when they can still interpret the required fields safely. Generators MUST fail with diagnostics when:

- `kind` is unsupported
- `extension.id` is missing
- `extension.definition` is missing or cannot be read
- The language section required for the current generator is missing
- A declaration cannot be interpreted safely

Future manifest versions may add richer language-specific extraction rules without changing the Runtime Conditions Profile document shape.
