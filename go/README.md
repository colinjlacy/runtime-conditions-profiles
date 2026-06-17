# Go Runtime Conditions AST Profiler

This tree contains the Go implementation of declaration-code Runtime Conditions Profile generation.

## Layout

- `runtimeconditions/`: typed no-op declaration library.
- `runtimeconditions/extractor/`: Go AST extractor.
- `profiler/cmd/runtimeconditions/`: CLI for generating Runtime Conditions Profile YAML.
- `apps/`: sample Go services.
- `docker-compose.yml`: Go sample service stack.

## Generate a Profile

```sh
go run ./profiler/cmd/runtimeconditions -dir ./apps/traffic -name traffic-generator
```

## Run Samples

```sh
docker compose up
```

## Test

```sh
go test ./...
```
