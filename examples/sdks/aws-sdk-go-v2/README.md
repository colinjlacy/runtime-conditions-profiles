# Example AWS SDK Package

This module is a local, dependency-free stand-in for an AWS SDK package.

The important Runtime Conditions convention is inside `service/s3`:

- `runtimeconditions.package.yaml` maps SDK symbols to Runtime Conditions.
- `runtimeconditions.package.yaml` references the canonical AWS Object Store example extension at `examples/extensions/aws-object-store/aws-object-store-v1alpha1.yaml`.

The manifest also declares the environment variable names the SDK needs for bucket, region, and credential inputs. It does not include those values.

An example workload can import `service/s3` and call `Client.PutObject` normally. The generator resolves the direct import through `go.mod`, reads the package manifest, loads the referenced extension definition, and emits an `aws.object_store` Condition without any explicit no-op declaration in the application source.
