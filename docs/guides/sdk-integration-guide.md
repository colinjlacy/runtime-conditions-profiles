# SDK Integration Guide

## Status

**Non-normative implementation guidance**

This guide describes how SDK authors can package Runtime Conditions metadata so workloads that import those SDKs can generate accurate Runtime Conditions Profiles without adding application-specific configuration files.

The core Runtime Conditions Profile specification defines the profile document and extension artifacts. This guide defines a packaging convention for SDKs and production libraries that want their internal runtime integrations to surface into generated workload profiles.

---

# 1. Goal

Many workloads access external runtime integrations through SDKs rather than direct application code.

Examples:

- A Go service calls `s3.Client.PutObject`.
- A Python service calls `boto3.client("s3").put_object`.
- A TypeScript service calls `new S3Client(...).send(new PutObjectCommand(...))`.

Without SDK-provided metadata, a generator can see the imported SDK or production library and the method call, but it cannot reliably know which Runtime Conditions extension owns that integration vocabulary or how the call maps to a Condition.

The SDK package convention solves this by allowing SDK authors to ship:

- A Runtime Conditions package manifest
- A reference to the extension definition owned by that SDK, vendor, or ecosystem
- Language-specific symbol mappings from SDK calls to Conditions

---

# 2. SDK Author Responsibilities

An SDK or production library author SHOULD:

- Identify SDK operations that imply external runtime integration requirements.
- Define or reference the Runtime Conditions extension vocabulary for those requirements.
- Ship a `runtimeconditions.package.yaml` manifest in the imported package.
- Package the extension definition as `runtimeconditions.extension.yaml` next to that manifest.
- Publish any SDK-owned extension definition as a standalone artifact that can be used without the SDK.
- Declare extension dependencies in the extension definition.
- Provide source fixtures that prove representative SDK calls generate the expected Conditions.

An SDK or production library author MUST NOT use package metadata to extract or emit:

- Secret values
- Credentials
- Customer data
- Protected data
- Concrete target-environment values
- Account-specific resource identifiers unless the profile extension explicitly permits them as non-secret requirements

The package metadata should describe workload requirements, not discovered deployment state.

---

# 3. Modeling Internal Conditions

SDK authors should model stable integration requirements, not every low-level method call.

For example, this SDK call:

```go
client := s3.NewFromConfig(s3.Config{})
_, err := client.PutObject(ctx, &s3.PutObjectInput{
	Bucket: &bucketName,
	Key:    &objectKey,
	Body:   body,
})
```

should normally produce one Condition:

```yaml
conditions:
  - name: s3-object-store
    kind: aws.object_store
    interface:
      type: aws.s3
      bucketClass: standard
```

It should not emit the runtime bucket name from the application variable. The bucket name is a concrete fulfillment choice unless the extension explicitly defines it as a requirement field.

## 3.1 Condition Granularity

SDK authors SHOULD prefer one Condition per required external integration surface.

Good examples:

- `aws.object_store` for S3 object storage usage
- `aws.rds` for RDS database usage
- `stripe.payments_api` for Stripe payment API usage
- `twilio.messaging_api` for Twilio messaging API usage

SDK authors SHOULD NOT create a separate Condition for every SDK method unless each method has materially different runtime requirements.

## 3.2 Stable Names

Package manifests MAY assign stable default Condition names.

Example:

```yaml
declarations:
  - receiver: Client
    method: PutObject
    name: s3-object-store
    kind: aws.object_store
    interfaceType: aws.s3
```

If an SDK supports multiple configured clients with different runtime requirements, the SDK author SHOULD provide a visible source-level naming mechanism that the generator can read statically.

---

# 4. Extension Ownership

An SDK author that introduces vendor-specific vocabulary should define an extension.

Example AWS object store extension:

```yaml
apiVersion: runtimeconditions.io/v1alpha1
kind: RuntimeConditionsExtensionDefinition

metadata:
  id: https://aws.example.com/runtimeconditions/object-store/v1alpha1/runtimeconditions.extension.yaml

spec:
  kinds:
    - name: aws.object_store

  interfaceTypes:
    - name: aws.s3
      targetKind: aws.object_store
```

An extension can be used directly by profiles and adapters without any SDK package. An SDK package that wants generation support must leverage an extension by referencing it from its package manifest; the package manifest does not define vocabulary by itself.

The SDK-owned extension definition should declare any first-party or third-party dependencies it relies on. For example, an AWS RDS extension that reuses common datastore vocabulary and environment configuration should declare dependencies on:

```yaml
spec:
  dependencies:
    - https://runtimeconditions.io/extensions/common-integrations/v1alpha1/runtimeconditions.extension.yaml
    - https://runtimeconditions.io/extensions/env-configuration/v1alpha1/runtimeconditions.extension.yaml
```

The SDK package does not need to vendor every dependency extension file. The SDK package is the source of the direct extension definition used for SDK extraction; that definition's dependency identifiers are then resolved by validators, generators, or adapters from their configured package, cache, registry, or development sources.

---

# 5. Package Manifest Role

The package manifest connects SDK source symbols to extension vocabulary.

Example:

```yaml
apiVersion: runtimeconditions.io/v1alpha1
kind: RuntimeConditionsPackage

metadata:
  package: github.com/colinjlacy/runtime-conditions-profiles/examples/sdks/aws-sdk-go-v2/service/s3
  language: go

extension:
  id: https://aws.example.com/runtimeconditions/object-store/v1alpha1/runtimeconditions.extension.yaml

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

This manifest says:

- The Go import path owns a package manifest.
- The manifest uses the AWS object store extension.
- `NewFromConfig` creates a `Client`.
- Calls to `Client.PutObject` imply an `aws.object_store` Condition with interface type `aws.s3`.
- The generated Condition should include `interface.bucketClass: standard`.
- The generated Condition should declare the environment variable names the workload expects for S3 connection and credential properties.

The manifest does not provide values for `AUDIT_LOG_BUCKET`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, or `AWS_SECRET_ACCESS_KEY`. It only maps workload-facing environment variable names to extension-defined properties. A platform adapter is responsible for satisfying those properties from provider outputs such as ConfigMaps, Secrets, service bindings, cloud identity mechanisms, or generated credentials.

---

# 6. Source Fixtures

SDK authors SHOULD include source fixtures that demonstrate the expected mapping.

Example fixture:

```go
package fixture

import (
	"context"

	"github.com/colinjlacy/runtime-conditions-profiles/examples/sdks/aws-sdk-go-v2/service/s3"
)

func write(ctx context.Context) error {
	client := s3.NewFromConfig(s3.Config{})
	_, err := client.PutObject(ctx, &s3.PutObjectInput{})
	return err
}
```

Expected generated profile fragment:

```yaml
extensions:
  - https://aws.example.com/runtimeconditions/object-store/v1alpha1/runtimeconditions.extension.yaml

conditions:
  - name: s3-object-store
    kind: aws.object_store
    interface:
      type: aws.s3
      bucketClass: standard
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

These fixtures are important because package manifests are executable only through generator behavior. A manifest that validates structurally but never maps real SDK source code is not useful.

---

# 7. SDK Author Checklist

Before publishing Runtime Conditions metadata, SDK authors SHOULD verify:

- The package includes `runtimeconditions.package.yaml`.
- The package includes `runtimeconditions.extension.yaml` next to the manifest.
- The extension identifier in the manifest matches `metadata.id` in the extension file.
- The extension declares all vocabulary dependencies.
- Any manifest `configuration` shape is defined by a declared extension dependency.
- The manifest maps real SDK symbols, not internal implementation details that users never call.
- Generated Conditions do not contain secrets or concrete target-environment values.
- Repeated calls deduplicate or aggregate into stable Conditions according to the generator's documented behavior.
- Source fixtures generate the expected profile fragments.
- Unsupported SDK features fail silently only when documented, or produce actionable diagnostics when a manifest is malformed.

---

# 8. Current Demo

This repository contains a minimal example SDK package at:

```text
examples/sdks/aws-sdk-go-v2/service/s3/
```

It includes:

```text
client.go
runtimeconditions.package.yaml
```

The manifest references the canonical example extension at:

```text
examples/extensions/aws-object-store/aws-object-store-v1alpha1.yaml
```

An example workload can import the SDK normally and call `Client.PutObject`. The Go generator discovers the package manifest from that import, loads the referenced extension definition, and emits an `aws.object_store` Condition into the generated Runtime Conditions Profile.
