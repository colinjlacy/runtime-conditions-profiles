# Runtime Conditions Profilers

Declare your service’s dependencies in code (Go or Python) and automatically generate the configurations needed for deployment. No YAML or manual steps required: just run a command in your CI pipeline to produce a spec that downstream tools (Kubernetes, Cilium, databases, etc.) can consume.

## The Problem

Deploying a service can feel like navigating a minefield. The “just deploy it” mindset often leads to failures because critical dependencies like databases, caches, or properly configured tooling are overlooked. Even when teams recognize the need for these components, they may lack awareness of the specific deployment requirements.

Downstream tooling is often a patchwork of one-off configurations and fragmented processes. Configuring database permissions, adjusting network policies, and other tasks require breadcrumbing conversations across teams. This can lead to misalignment, time-consuming coordination, and details slipping through the cracks.

## The Proposal

This tool replaces chaos and confusion with a [three-step workflow](colinjlacy.github.io/runtime-conditions-profiles/):

- The source code uses integrations. For an example, the demo app below calls an HTTP API, Redis, and S3.
- A generator emits a profile that includes requirements and environment variable names, not target values.
- An adapter fulfills the profile. The Kratix demo maps Conditions to Redis, S3, Secrets, ConfigMaps, and Cilium policies.

## Who Benefits

**App Developers:**

- Write code, not YAML.
- Catch breaking changes early with type-checking (e.g., API mismatches).
- Integrates seamlessly into your CI/CD pipeline so you can generate specs alongside your builds, tests, and scans.

**Platform Engineers:**

- Get a cross-cutting view of all integrations across your platform, with one spec file per service.
- Automate downstream configurations (Kubernetes, Cilium, etc.) from the spec.

**Cloud Providers:**

- Provide extensions to auto-declare your services (e.g., S3, Lambda functions) in the spec, making it easier for users to adopt and integrate your offerings.
- Embed spec awareness in your SDKs so they automatically declare required integrations, reduce friction for users, and drive adoption of your platform.
- Help standardize how services declare dependencies, creating a sticky, provider-agnostic layer that benefits your customers and the broader cloud-native community.

## Contents

This repository contains three separate implementation areas:

- `ebpf-profiler/` - the original Linux eBPF runtime observation profiler.
- `go/` - Go declaration library, Go AST profiler, and Go sample services.
- `python/` - Python declaration library, Python AST profiler, and Python sample services.

The Runtime Conditions Profile specification draft lives in `docs/`. Start with
`docs/intro.md` for the core spec, extension drafts, and SDK integration guides.

The GitHub Pages reader site lives in `site/`. It is a static site that presents
the current spec, extension model, implementation guides, and end-to-end Kratix
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
cd go
go test ./...
go run ./profiler/cmd/runtimeconditions -dir ./apps/traffic -name traffic-generator
docker compose up
```

## Python AST Profiler

```sh
cd python
python3 -m unittest discover -s tests
python3 -m runtimeconditions.profiler -d apps/traffic -n traffic-generator
docker compose up
```

## Try It Out

We're actively seeking feedback on this approach. Does this solve your pain points? What’s missing? What's useful? [File an issue](https://github.com/colinjlacy/runtime-conditions-profiles/issues) and let us know. Thanks in advance!

## Contribute

If you'd like to help us build out the repository, file an issue describing the feature or change you'd like to implement, and why.
