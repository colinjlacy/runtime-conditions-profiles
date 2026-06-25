# Demo AWS SDK Fork

This module is a local, dependency-free stand-in for an AWS SDK package.

The important Runtime Conditions convention is inside `service/s3`:

- `runtimeconditions.package.yaml` maps SDK symbols to Runtime Conditions.
- `aws-object-store-v1alpha1.yaml` is the extension definition owned by this SDK package.

The manifest also declares the environment variable names the SDK needs for bucket, region, and credential inputs. It does not include those values.

The demo application imports `service/s3` and calls `Client.PutObject` normally. The generator resolves the direct import through `go.mod`, reads the package manifest, loads the embedded extension definition, and emits an `aws.object_store` Condition without any explicit no-op declaration in the application source.
