# This Repository Is No Longer Active

Runtime Conditions has moved to the
[`runtimeconditions`](https://github.com/runtimeconditions) GitHub
organization. This root-level repository is kept only as historical source
while the project is split into smaller repos for hands-on usability, feedback,
and possible adoption by a parent project.

Start here: https://runtimeconditions.github.io/

Active repositories now live under: https://github.com/runtimeconditions

---

# Runtime Conditions Profilers

Declare your service's runtime dependencies in code and generate a Runtime Conditions Profile that downstream tools can consume. The Go AST profiler reads first-party extension declaration packages and emits profile YAML for adapters and validators.

## The Problem

Deploying a service can feel like navigating a minefield. The “just deploy it” mindset often leads to failures because critical dependencies like databases, caches, or properly configured tooling are overlooked. Even when teams recognize the need for these components, they may lack awareness of the specific deployment requirements.

Downstream tooling is often a patchwork of one-off configurations and fragmented processes. Configuring database permissions, adjusting network policies, and other tasks require breadcrumbing conversations across teams. This can lead to misalignment, time-consuming coordination, and details slipping through the cracks.

## The Proposal

This repository is organized around a [three-step workflow](https://colinjlacy.github.io/runtime-conditions-profiles/):

- The source code uses integrations. For an example, the demo app calls an HTTP API and Redis.
- A generator emits a profile that includes requirements and environment variable names, not target values.
- An adapter fulfills the profile. The Kratix demo maps Conditions to API catalog validation, Redis provisioning, environment variables, and Kubernetes Deployment/Service resources.

## Who Benefits

**App Developers:**

- Write code, not YAML.
- Catch breaking changes early with type-checking (e.g., API mismatches).
- Integrates seamlessly into your CI/CD pipeline so you can generate specs alongside your builds, tests, and scans.

**Platform Engineers:**

- Get a cross-cutting view of all integrations across your platform, with one spec file per service.
- Automate downstream configurations such as Kubernetes workloads, catalog checks, and provider resource requests from the spec.

**Cloud Providers:**

- Provide extensions to auto-declare your services (e.g., S3, Lambda functions) in the spec, making it easier for users to adopt and integrate your offerings.
- Embed spec awareness in your SDKs so they automatically declare required integrations, reduce friction for users, and drive adoption of your platform.
- Help standardize how services declare dependencies, creating a sticky, provider-agnostic layer that benefits your customers and the broader cloud-native community.

## Contents

This repository contains:

- `ebpf-profiler/` - the original Linux eBPF runtime observation profiler.
- `docs/` - the Runtime Conditions Profile specification draft and authoring guides.
- `extensions/` - first-party extension definitions and declaration packages.
- `go/profiler/` - the maintained Go AST profile generator.
- `java/profiler/` - Java-native profiler work, currently focused on Maven/Gradle-aware Runtime Conditions artifact discovery.
- `demos/apps/` - demo workloads used to exercise declaration packages and downstream adapters.
- `demos/kratix/` - Kratix adapter and Promise demo assets.
- `examples/sdks/` - SDK packaging examples for package-owned manifests.

The Runtime Conditions Profile specification draft lives in `docs/sixth-draft.md`.

The GitHub Pages reader site lives in `site/`. It is a static site that presents
the spec, extension model, implementation guides, and end-to-end Kratix
demo as a cohesive reader flow. The workflow in `.github/workflows/pages.yml`
publishes that directory to GitHub Pages.

## eBPF Profiler

```sh
cd ebpf-profiler
go generate ./pkg/profiler
go test ./...
go build ./cmd/profiler
```

The generated eBPF bindings are produced by `bpf2go` from `ebpf-profiler/pkg/profiler/bpf.go`.

## Go AST Profiler

```sh
cd go/profiler
go test ./...
go run . \
  -dir ../../demos/apps/request-logger-http \
  -name request-logger-http \
  -workload-version dev
```

## Java Profiler

```sh
cd java/profiler
mvn -q package dependency:build-classpath \
  -Dmdep.outputFile=/tmp/runtimeconditions-java-profiler.classpath

CP="target/classes:$(cat /tmp/runtimeconditions-java-profiler.classpath)"

java -cp "$CP" \
  io.runtimeconditions.profiler.ProfilerCli generate \
  --project src/testdata/declarative-app \
  --classpath ../../extensions/common-integrations/java:../../extensions/env-configuration/java \
  --name java-declarative-app \
  --workload-uri example/java-declarative-app \
  --workload-version test
```

The Java profiler currently generates profiles from declarative Java binding packages. SDK/runtime package extraction is intentionally not implemented yet.

## Try It Out

We're actively seeking feedback on this approach. Does this solve your pain points? What’s missing? What's useful? [File an issue](https://github.com/colinjlacy/runtime-conditions-profiles/issues) and let us know. Thanks in advance!

## Contribute

If you'd like to help us build out the repository, file an issue describing the feature or change you'd like to implement, and why.
