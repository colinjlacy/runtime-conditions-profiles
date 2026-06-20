# AWS Object Store Extension Specification (Draft)

## Status

**Draft - third-party extension example**

This document defines a minimal Runtime Conditions extension for AWS S3-compatible object storage requirements.

The machine-readable extension definition is [aws-object-store-v1alpha1.yaml](aws-object-store-v1alpha1.yaml).

The demo SDK package that embeds this extension shape is in
[../../demo/aws-sdk-go-v2/service/s3](../../demo/aws-sdk-go-v2/service/s3).

This extension is treated as a third-party extension. It is not first-party Runtime Conditions vocabulary.

---

# 1. Extension Identity

```yaml
extensions:
  - https://aws.example.com/runtimeconditions/object-store:v1alpha1
```

This extension defines:

- The `aws.object_store` Condition kind
- The `aws.s3` interface type
- The `interface.bucketClass` field
- The `standard` and `archive` bucket class values

---

# 2. Example

```yaml
conditions:
  - name: audit-log-bucket
    kind: aws.object_store
    interface:
      type: aws.s3
      bucketClass: archive
```

---

# 3. SDK Package Manifest Example

The demo SDK package includes a `runtimeconditions.package.yaml` file next to
its S3 client code. That package manifest maps SDK method calls to this
extension vocabulary:

```yaml
apiVersion: runtimeconditions.io/v1alpha1
kind: RuntimeConditionsPackage

extension:
  id: https://aws.example.com/runtimeconditions/object-store:v1alpha1
  definition: aws-object-store-v1alpha1.yaml

go:
  importPath: github.com/colinjlacy/golang-http-profiler/demo/aws-sdk-go-v2/service/s3
  constructors:
    - function: NewFromConfig
      receiver: Client
  declarations:
    - receiver: Client
      method: PutObject
      name: s3-object-store
      kind: aws.object_store
      interfaceType: aws.s3
```

The application imports and calls the SDK normally. Runtime Conditions tooling
reads the package manifest for direct imports and emits the profile condition
without requiring application code to import a separate declaration package.
