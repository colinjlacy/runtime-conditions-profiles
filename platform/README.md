# Runtime Conditions Kratix Demo Harness

This tree contains scripts and assets for the first end-to-end Kratix implementation described in `docs/core/kratix-runtime-conditions-implementation-proposal.md`.

The scripts assume:

- you already have a Kubernetes cluster
- the current `kubectl` context points at that cluster
- the cluster can pull images from the internet
- Cilium is already installed as the CNI
- Kratix, Flux, MinIO, and cert-manager are not installed yet

The Kratix installer used here is the OSS quick-start installer. It is suitable for this executable proof, not for a hardened long-lived production platform.

## Quick Run

From the repository root:

```sh
platform/scripts/00-check-prereqs.sh
platform/scripts/01-install-kratix.sh
platform/scripts/02-use-ghcr-images.sh
platform/scripts/03-install-promises.sh
platform/scripts/04-deploy-catalog-and-provider.sh
platform/scripts/05-rc-deploy.sh
platform/scripts/06-smoke-test.sh
```

The GHCR script writes image references to `platform/.env.generated`. Later scripts source that file automatically.

The default image tag is `latest`, matching the tag produced by the GitHub Actions workflow on the default branch:

```sh
IMAGE_TAG=latest platform/scripts/02-use-ghcr-images.sh
```

For a branch or commit-specific deployment, use the branch tag or `sha-<commit>` tag emitted by the workflow:

```sh
IMAGE_TAG=sha-0123456789abcdef platform/scripts/02-use-ghcr-images.sh
```

The script infers `GHCR_OWNER` and `GHCR_REPOSITORY` from `remote.origin.url`. You can set them explicitly when needed:

```sh
GHCR_OWNER=example GHCR_REPOSITORY=runtimeconditions-demo platform/scripts/02-use-ghcr-images.sh
```

## Publishing Images

The workflow at `.github/workflows/publish-ghcr-images.yml` builds and pushes public GHCR images for:

- `redis-pipeline`
- `runtime-workload-pipeline`
- `todos-api`
- `request-logger`

It publishes them as:

```text
ghcr.io/<owner>/<repo>-redis-pipeline:<tag>
ghcr.io/<owner>/<repo>-runtime-workload-pipeline:<tag>
ghcr.io/<owner>/<repo>-todos-api:<tag>
ghcr.io/<owner>/<repo>-request-logger:<tag>
```

GHCR packages may need to be marked public in the GitHub UI after their first publish. The Kubernetes cluster does not need image pull secrets for public packages.

For local ad hoc builds, the original Docker build script remains available. By default, it pushes temporary public `ttl.sh` tags. For a durable registry:

```sh
IMAGE_REGISTRY=registry.example.com/runtimeconditions IMAGE_TAG=dev platform/scripts/02-build-and-push-images.sh
```

## Breaking Contract Demo

```sh
platform/scripts/07-demo-breaking-change.sh
```

That script swaps the catalog to an incompatible OpenAPI document and submits a separate `RuntimeWorkload` request named `request-logger-breaking`. The resolver should fail before writing a Deployment for that request.

## Useful Environment Variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `DEMO_NAMESPACE` | `demo` | Namespace for provider and consumer workloads |
| `CATALOG_NAMESPACE` | `runtimeconditions-system` | Namespace for the API catalog ConfigMap |
| `IMAGE_REGISTRY` | empty | Registry prefix for durable images |
| `IMAGE_TAG` | `latest` for GHCR, `dev` or `24h` for local build script | Image tag |
| `GHCR_OWNER` | inferred from git remote | GHCR namespace owner |
| `GHCR_REPOSITORY` | inferred from git remote | Repository name used in GHCR package names |
| `TARGET_PLATFORM` | `linux/amd64` | Docker build platform |
| `APP_NAME` | `request-logger` | RuntimeWorkload and Deployment name |
| `APP_SOURCE_DIR` | `go/apps/request-logger-http` | Source scanned by the AST profiler |
| `APP_IMAGE` | generated request logger image | Consumer image deployed by `RuntimeWorkload` |

## What Gets Installed

- Kratix quick-start stack in `kratix-platform-system`
- `runtimeconditions.io/v1alpha1, Kind=Redis` Promise
- `runtimeconditions.io/v1alpha1, Kind=RuntimeWorkload` Promise
- Backstage-compatible API catalog ConfigMap for `todos-api`
- In-cluster `todos-api` provider Deployment and Service
- A `RuntimeWorkload` request generated from source-derived Runtime Conditions
