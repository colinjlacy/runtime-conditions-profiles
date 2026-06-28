# AWS Object Store Extension Specification (Draft)

## Status

**Draft - third-party extension example**

This document defines a minimal Runtime Conditions extension for AWS S3-compatible object storage requirements.

The machine-readable extension definition is [aws-object-store-v1alpha1.yaml](aws-object-store-v1alpha1.yaml).

The example SDK package that embeds this extension shape is in
[../../examples/sdks/aws-sdk-go-v2/service/s3](../../examples/sdks/aws-sdk-go-v2/service/s3).

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
- S3-specific environment configuration properties for `bucket`, `region`, `accessKeyId`, `secretAccessKey`, and `sessionToken`

This extension has no extension dependencies. It defines its own `configuration`
field scope for `aws.object_store` Conditions with the `aws.s3` interface type,
plus S3-specific allowed property values inside that field.

---

# 2. Example

```yaml
conditions:
  - name: audit-log-bucket
    kind: aws.object_store
    interface:
      type: aws.s3
      bucketClass: archive
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

The `configuration` block names workload-facing environment variables only. It does not include bucket names, credential values, regions selected for a target environment, or other concrete fulfillment values.

---

# 3. SDK Package Manifest Example

The example SDK package includes a `runtimeconditions.package.yaml` file next to
its S3 client code. That package manifest maps SDK method calls to this
extension vocabulary:

```yaml
apiVersion: runtimeconditions.io/v1alpha1
kind: RuntimeConditionsPackage

extension:
  id: https://aws.example.com/runtimeconditions/object-store:v1alpha1
  definition: aws-object-store-v1alpha1.yaml

go:
  importPath: github.com/colinjlacy/runtime-conditions-profiles/examples/sdks/aws-sdk-go-v2/service/s3
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

The application imports and calls the SDK normally. Runtime Conditions tooling
reads the package manifest for direct imports and emits the profile condition
without requiring application code to import a separate declaration package.

A downstream adapter can map this profile shape to provider-specific provisioning. For example, an adapter could create an object-storage request, publish non-sensitive connection properties through a ConfigMap, publish sensitive workload credentials through a Secret, and use the profile's `configuration.env[].name` values to wire those outputs into the workload Deployment.
