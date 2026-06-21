# Generator Discovery and End-User Workflow

## Status

**Non-normative implementation guidance**

This guide documents how a first-party generator discovers Runtime Conditions package manifests from imported libraries and how an end user benefits from those manifests when generating workload profiles.

---

# 1. Discovery Model

Generators should discover Runtime Conditions metadata from direct imports, not by scanning entire dependency trees.

The intended flow is:

```mermaid
flowchart LR
  Source["Workload source code"] --> Imports["Collect direct imports"]
  Imports --> Resolve["Resolve imported packages"]
  Resolve --> Manifest["Check conventional manifest path"]
  Manifest --> Extension["Load package-owned extension definition"]
  Extension --> Dependencies["Resolve extension dependencies"]
  Source --> AST["Read source AST"]
  AST --> Map["Apply manifest symbol mappings"]
  Map --> Profile["Emit Runtime Conditions Profile"]
```

This avoids expensive scans of directories such as `node_modules`, Maven caches, Python virtual environments, or transitive Go module caches.

---

# 2. Current Go Generator Workflow

The current Go demo generator performs these steps:

1. Parse workload Go source files.
2. Collect direct import paths from those files.
3. Find the nearest `go.mod` for the workload.
4. Resolve direct imports using the module path and local `replace` directives.
5. Check each resolved direct import directory for `runtimeconditions.package.yaml`.
6. Load the package manifest.
7. Load the extension definition referenced by the package manifest.
8. Parse the workload AST.
9. Match constructor calls, receiver method calls, and explicit Runtime Conditions declarations.
10. Emit a Runtime Conditions Profile.

The generator does not need `-extensions-root` for package manifests shipped by imported SDK packages.

The `-extensions-root` flag remains useful for local extension catalogs and legacy binding manifests, but SDK package manifests are discovered from imports.

---

# 3. Demo Walkthrough

The demo workload imports a local SDK package:

```go
import "github.com/colinjlacy/golang-http-profiler/demo/aws-sdk-go-v2/service/s3"
```

The workload calls the SDK normally:

```go
func writeAuditLog(ctx context.Context, event string) error {
	bucket := os.Getenv("AUDIT_LOG_BUCKET")
	if bucket == "" {
		return errors.New("AUDIT_LOG_BUCKET is not set")
	}
	client := s3.NewFromConfig(s3.Config{})
	_, err := client.PutObject(ctx, &s3.PutObjectInput{
		Bucket: stringPtr(bucket),
		Key:    stringPtr("request-logger/demo.json"),
		Body:   strings.NewReader(event),
	})
	return err
}
```

The SDK package includes:

```text
demo/aws-sdk-go-v2/service/s3/
  client.go
  runtimeconditions.package.yaml
  aws-object-store-v1alpha1.yaml
```

The package manifest maps `Client.PutObject` to:

```yaml
kind: aws.object_store
interfaceType: aws.s3
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

Running the generator:

```sh
cd go
go run ./profiler/cmd/runtimeconditions \
  -dir ./apps/request-logger-http \
  -name request-logger-http \
  -workload-uri github.com/example/request-logger-http \
  -workload-version v0.1.0
```

produces a profile that includes both explicit declarations and SDK-discovered Conditions:

```yaml
extensions:
  - https://aws.example.com/runtimeconditions/object-store:v1alpha1
  - https://runtimeconditions.io/extensions/common-integrations:v1alpha1
  - https://runtimeconditions.io/extensions/env-configuration:v1alpha1

conditions:
  - name: todos-api
    kind: api
    interface:
      type: http
    configuration:
      env:
        - property: baseUrl
          name: TODOS_API_URL
  - name: request-cache
    kind: cache
    interface:
      type: key_value
      engine: redis
    configuration:
      alternatives:
        - env:
            - property: url
              name: REDIS_URL
        - env:
            - property: hostname
              name: REDIS_HOST
            - property: port
              name: REDIS_PORT
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

The `todos-api` and `request-cache` Conditions come from explicit `rc` declarations in the workload. The `s3-object-store` Condition comes from normal SDK usage plus the SDK package manifest.

The profile records the environment variable names expected by the workload. It does not contain the values for those variables. In the Kratix demo, the runtime-workload adapter maps these requested properties to provider-owned outputs:

| Condition property | Kubernetes source |
| ---- | ---- |
| `baseUrl` | Literal service URL from the API catalog |
| `url`, `hostname`, `port` | Redis Promise connection ConfigMap |
| `bucket`, `region` | S3Bucket Promise connection ConfigMap |
| `accessKeyId`, `secretAccessKey` | S3Bucket Promise credentials Secret |

The same adapter emits Cilium policy requests for the resolved workload:

- A `CiliumNamespaceLockdown` request creates namespace default-deny pod networking.
- A `CiliumAPIAccess` request is emitted for API Conditions that declare HTTP operations. That request contains the workload selector, resolved service or FQDN destination, port, and only the `method` and `path` pairs declared by the Condition.
- Redis and S3Bucket requests include the workload selector so their Promises can render dependency-specific Cilium policies.

The generator still emits only the Runtime Conditions Profile. Network-policy requests are adapter output.

---

# 4. Extension Dependency Resolution

Package manifests identify the extension used by generated Conditions:

```yaml
extension:
  id: https://aws.example.com/runtimeconditions/object-store:v1alpha1
  definition: aws-object-store-v1alpha1.yaml
```

The extension definition declares its dependencies:

```yaml
spec:
  dependencies:
    - https://runtimeconditions.io/extensions/common-integrations:v1alpha1
    - https://runtimeconditions.io/extensions/env-configuration:v1alpha1
```

Generators and validators should resolve dependencies by extension identifier from configured sources such as:

- Bundled first-party extension definitions
- Local extension roots
- Local cache
- Organization registry
- Public registry

SDK packages should ship the extension definition they own. They do not need to physically include every transitive dependency extension file.

---

# 5. End-User Workflow

An application developer using third-party SDK support should be able to follow this workflow:

1. Add or update the SDK dependency as usual.
2. Write normal application code against the SDK.
3. Add explicit Runtime Conditions declarations only for unsupported SDKs, raw HTTP calls, or app-specific integrations.
4. Run the language generator.
5. Review the generated Runtime Conditions Profile.
6. Validate the profile against the core spec and resolved extensions.
7. Pass the validated profile to an adapter or platform workflow.

The end user should not need to add a separate application config file just to enable SDK Condition discovery.

---

# 6. Diagnostics

Generators SHOULD produce actionable diagnostics for malformed package metadata.

Examples:

| Case | Diagnostic Category |
| ---- | ------------------- |
| Imported package has malformed `runtimeconditions.package.yaml` | `package-manifest` |
| Manifest references a missing extension file | `package-extension` |
| Manifest references an unsupported language section | `package-language` |
| Manifest maps a method that cannot be matched statically | `package-mapping` |
| Extension dependency cannot be resolved | `extension-dependency` |
| Generated vocabulary is not defined by resolved extensions | `unresolved-vocabulary` |

Generators SHOULD NOT fail merely because an imported package does not include a Runtime Conditions manifest. Most libraries will not participate in this convention.

Generators SHOULD fail before emitting a profile when a discovered manifest is malformed or would emit unresolved vocabulary.

---

# 7. Dedupe and Aggregation

A generator may see the same SDK method called many times.

SDK package manifests SHOULD choose stable Condition names so generators can deduplicate repeated calls.

Example:

```yaml
declarations:
  - receiver: Client
    method: PutObject
    name: s3-object-store
    kind: aws.object_store
    interfaceType: aws.s3
```

If a workload calls `PutObject` in five places, the generated profile should normally contain one `s3-object-store` Condition unless the manifest defines a static and safe way to distinguish multiple integration requirements.

---

# 8. Unsupported Integrations

Package manifest discovery is additive. It does not replace explicit declarations.

For raw HTTP calls or SDKs without package manifests, developers can still write explicit declarations:

```go
rc.API("todos-api",
	rc.Spec("openapi", "catalog://api/default/todos-api", "1.0.0"),
	rc.GET("/todos/{id}", rc.Response[Todo]()),
)
```

This preserves a practical escape hatch while allowing richer SDKs to surface their internal Conditions automatically.
