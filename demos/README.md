# Runtime Conditions Demos

This tree contains runnable examples and downstream adapter assets.

## Layout

- `apps/request-logger-http/` - Go workload that imports first-party declaration packages and demonstrates explicit profile declarations.
- `apps/request-logger-http-java/` - Java workload with matching explicit declarations for the same Conditions as the Go request logger.
- `apps/todos-api/` - simple provider API used by the request logger demo.
- `catalog/apis/` - OpenAPI and catalog files used by the adapter demo.
- `kratix/` - Kratix Promise and adapter assets for downstream fulfillment demos.
- `kratix/manifests/` - static Kubernetes and Kratix manifests applied by the demo scripts.

## Generate the Request Logger Profile

From the repository root:

```sh
cd go/profiler
go run . \
  -dir ../../demos/apps/request-logger-http \
  -name request-logger-http \
  -workload-version dev
```

The request logger is its own Go module:

```sh
cd demos/apps/request-logger-http
go test ./...
```

## Generate the Java Request Logger Profile

From the repository root:

```sh
cd java/profiler
mvn -q package

java -jar target/runtimeconditions-java-profiler-0.1.0-SNAPSHOT.jar generate \
  --project ../../demos/apps/request-logger-http-java \
  --classpath ../../extensions/common-integrations/java:../../extensions/env-configuration/java \
  --name request-logger-http \
  --workload-uri github.com/colinjlacy/runtime-conditions-profiles/demos/apps/request-logger-http-java \
  --workload-version dev
```

The Java demo is a Maven project:

```sh
cd demos/apps/request-logger-http-java
mvn -q package
```

## Published Demo Images

The workflow at `.github/workflows/publish-ghcr-images.yml` builds and pushes images for:

- `redis-pipeline`
- `application-release-pipeline`
- `todos-api`
- `request-logger`

## Run the Kratix Demo

From the repository root:

```sh
demos/kratix/scripts/00-check-prereqs.sh
demos/kratix/scripts/01-install-kratix.sh
demos/kratix/scripts/02-install-promises.sh
demos/kratix/scripts/03-deploy-catalog-and-provider.sh
demos/kratix/scripts/04-deploy-application-release.sh
demos/kratix/scripts/05-smoke-test.sh
```

To run the contract failure path:

```sh
demos/kratix/scripts/06-demo-breaking-change.sh
```
