# Golang HTTP Profiler

Minimal eBPF-backed HTTP syscall and environment variable profiler written in Golang, plus a tiny test service and traffic generator. 

It's a PoC in the service of [this initiative](https://github.com/cncf/toc/issues/1797).

## What’s here
- `cmd/profiler`: eBPF-powered profiler that attaches to socket syscalls and writes request/response metadata to a local file.
- `pkg/profiler`: profiler implementation.
- `bpf/profiler.bpf.c`: BPF program compiled via `bpf2go` during build.
- `output`: default local output directory.

The sample applications that can be profiled live outside this tree:

- `../go/apps`: Go sample services and Go AST declarations.
- `../python/apps`: Python sample services and Python AST declarations.

## What it does:

- Sets up an eBPF profiler to listen for HTTP events and logs the origin PID, to IP and port, from IP and port, method, data, response code, etc.
- Classifies non-HTTP connections (databases, caches, message buses) using port-based heuristics and protocol fingerprinting
- For each PID found, pulls the environment variables assigned to the process
- Writes the output of each to an output file

Please feel free to make suggestions, either here or in the initiative's [Slack discussion](https://cloud-native.slack.com/archives/C09S7A5T3GF).

## System compatibility 

**This project currently only runs on Linux.** If you want to run it on a Mac, you'll need a VM. I could not get it working in a Linux container, although that could have something to do with the corporate security profile installed on my machine. 

Since it leverages eBPF, I have strong doubts about it working on Windows.

## Setup (Ubuntu 20/22/23/25)

There are probably better/smarter/faster/cooler ways to run this, but the way I pulled it off was to run a [Lima VM](https://lima-vm.io/) on my Mac. Note that I'm running on an ARM64 Mac, and I have not tested this on an x86 machine of any sort. Which means I also haven't tested it on a real Linux box.

That said, if you'd like to run this:
- If you're using a VM, SSH into that and clone this repo
  - Lima will mount your host machine's home directory as read-only
  - But! you need to generate the Go/C bindings for the eBPF functionality.
  - So! don't rely on the mounted home directory if you've cloned this to your host machine
- Install the Go toolchain 1.25+ 
- Make sure you've got an OCI container runtime installed
  - Most people would say "make sure you've got Docker installed"
  - I used [nerdctl](https://github.com/containerd/nerdctl)
  - [Podman](https://podman.io/) would also work.
- Install C libraries (I had to sudo on a lima VM):
```sh
sudo apt-get update
# have not tested on x86, 
# but I'd imagine you'll have less problems than I did
sudo apt-get install -y --no-install-recommends \
    clang llvm make pkg-config libelf-dev zlib1g-dev linux-libc-dev libbpf-dev
sudo rm -rf /var/lib/apt/lists/*
```
- set up necessary symlink:
```sh
# me and Claude trying to be arch-agnostic
arch="$(uname -m)" && \
case "${arch}" in \
    x86_64) multiarch="x86_64-linux-gnu" ;; \
    aarch64|arm64) multiarch="aarch64-linux-gnu" ;; \
    *) echo "Unsupported architecture: ${arch}" >&2; exit 1 ;; \
esac && \
ln -sf /usr/include/${multiarch}/asm /usr/include/asm
```
- Set environment variables:
```sh
export GOOS=linux
export GOARCH=arm64 # or, ya know, whatever
export CGO_ENABLED=1
```
- Build the profiler:
```sh
# Linux only; requires clang/llvm and kernel headers
go mod download
go generate ./pkg/profiler       # builds the BPF object via bpf2go (emits profiler_bpfel.go and profiler_bpfeb.go)
go build ./cmd/profiler          # profiler binary (uses generated bindings)
```

## Environment Variables

### Profiler Configuration

These environment variables configure the profiler itself:

- `OUTPUT_PATH=/var/log/ebpf_http_profiler.log` - File path for HTTP event logs (JSON format)
- `ENV_OUTPUT_PATH=/var/log/ebpf_http_env.yaml` - File path for environment variable logs (YAML format)
- `SERVICE_MAP_PATH=""` - File path for service integration map (YAML format). If not set, service map is disabled.
- `ENV_PREFIX_LIST=""` - Comma-separated list of environment variable key prefixes to include (case-sensitive). If not set, all environment variables are collected.
- `ADI_PROFILE_ALLOWED=""` - Comma-separated list of ADI_PROFILE values to profile (see Opt-In Profiling below). If not set, all processes with `ADI_PROFILE` set (any value) will be profiled.
- `CONTAINERD_SOCKET=""` - Path to containerd or Docker socket. If not set, container metadata enrichment is disabled.
  - For Docker: `/var/run/docker.sock` (probably?)
  - For rootless nerdctl: `/run/user/$UID/containerd/containerd.sock`
  - For rootful nerdctl/containerd: `/run/containerd/containerd.sock`
  - For rootless podman: `/run/user/$UID/podman/podman.sock`
  - For rootful podman: `/run/podman/podman.sock`
- `CONTAINERD_NAMESPACE=default` - Containerd namespace to use (nerdctl typically uses `default`)

### Opt-In Profiling

The profiler uses an opt-in system to control which processes are profiled. Target processes (the ones being profiled) must set specific environment variables:

**Target Process Environment Variables:**
- `ADI_PROFILE=<environment>` - **Required for profiling.** Indicates the process opts into profiling. The value should indicate the environment (e.g., `local`, `dev`, `staging`, `prod`).
- `ADI_PROFILE_NAME=<name>` - **Optional.** A human-readable name for the service or process instance. This value will be included in the YAML output as `adi_profile_name` for easier identification.
- `ADI_PROFILE_DISABLED=1` - **Override to disable profiling.** If set, the process will not be profiled even if `ADI_PROFILE` is present.

**Profiling Logic:**

A process is profiled only if:
1. `ADI_PROFILE_DISABLED` is **not** set to `1`
2. `ADI_PROFILE` **is** set
3. If `ADI_PROFILE_ALLOWED` is set on the profiler, the `ADI_PROFILE` value must be in the allowed list

**Default Behavior:**
- If `ADI_PROFILE_ALLOWED` is **not set**: All processes with `ADI_PROFILE` set (any value) are profiled
- If `ADI_PROFILE_ALLOWED` **is set**: Only processes whose `ADI_PROFILE` value matches one in the list are profiled
- Processes without `ADI_PROFILE` are **never** profiled

**Example Scenarios:**

| Target Process Has | Profiler Config | Result |
|-------------------|-----------------|---------|
| `ADI_PROFILE=local` | `ADI_PROFILE_ALLOWED=""` (not set) | ✅ Profiled |
| `ADI_PROFILE=local` | `ADI_PROFILE_ALLOWED="local,dev"` | ✅ Profiled |
| `ADI_PROFILE=prod` | `ADI_PROFILE_ALLOWED="local,dev"` | ❌ Not profiled |
| `ADI_PROFILE=local`<br>`ADI_PROFILE_DISABLED=1` | `ADI_PROFILE_ALLOWED="local"` | ❌ Not profiled (override) |
| (no ADI_PROFILE) | `ADI_PROFILE_ALLOWED="local"` | ❌ Not profiled |

## Run 'dis mofo

### Basic Usage

**Step 1: Start the profiler**

Profile all processes with `ADI_PROFILE` set (any value), get the full environment variable map for each process:
```sh
sudo OUTPUT_PATH="/some/path/ebpf_http_profiler.log" \
     ENV_OUTPUT_PATH="/some/path/ebpf_env_profiler.yaml" \
     ./profiler
```

Profile only specific processes that have `ADI_PROFILE=local` or `ADI_PROFILE=dev` set, still get the full environment variable map for each process:
```sh
sudo OUTPUT_PATH="/some/path/ebpf_http_profiler.log" \
     ENV_OUTPUT_PATH="/some/path/ebpf_env_profiler.yaml" \
     ADI_PROFILE_ALLOWED="local,dev" \
     ./profiler
```

Profile with environment variable filtering:
```sh
sudo OUTPUT_PATH="/some/path/ebpf_http_profiler.log" \
     ENV_OUTPUT_PATH="/some/path/ebpf_env_profiler.yaml" \
     ENV_PREFIX_LIST="REVIEWS_,RATINGS_,MONGO_" \
     ADI_PROFILE_ALLOWED="local,dev,staging" \
     ./profiler
```

Profile with container metadata enrichment (service-to-service mapping):
```sh
# using nerdctl rootless as an example for the container enrichment flags
sudo OUTPUT_PATH="/some/path/ebpf_http_profiler.log" \
     ENV_OUTPUT_PATH="/some/path/ebpf_env_profiler.yaml" \
     SERVICE_MAP_PATH="/some/path/ebpf_service_map.yaml" \
     CONTAINERD_SOCKET="$XDG_RUNTIME_DIR/containerd/containerd.sock" \
     CONTAINERD_NAMESPACE="default" \
     ADI_PROFILE_ALLOWED="local,dev" \
     ./profiler
```

**Step 2: Start services with opt-in flag**

The Go and Python implementations each include a `docker-compose.yml` for a demo microservices architecture. From `../go` or `../python`, the compose file starts the following profiled services (all have `ADI_PROFILE=local`):

- **http-service**: HTTP server that publishes request info to NATS
  - Labels: `app.role=web-server`, `app.tier=backend`, `app.component=api`
- **request-logger**: Subscribes to NATS and stores requests in Redis
  - Labels: `app.role=logger`, `app.tier=backend`, `app.component=event-consumer`
- **traffic-generator**: Generates HTTP requests and database/cache operations
  - Labels: `app.role=test-client`, `app.tier=testing`, `app.component=load-generator`

And supporting infrastructure (also profiled with `ADI_PROFILE=local`):
- **postgres**: PostgreSQL database
  - Labels: `app.role=database`, `app.tier=data`, `app.component=relational-db`
- **redis**: Redis cache
  - Labels: `app.role=cache`, `app.tier=data`, `app.component=key-value-store`
- **nats-server**: NATS message broker
  - Labels: `app.role=message-broker`, `app.tier=infrastructure`, `app.component=message-bus`

All services are tagged with Docker Compose labels that describe their role, tier, and component type. These labels are automatically captured by the profiler and included in the service map output when container metadata enrichment is enabled.

Run them all with:
```sh
cd ../go      # or ../python
docker compose up -d
# podman compose up -d
# nerdctl compose up -d
```

**What Gets Profiled:**

The profiler captures all HTTP traffic and non-HTTP connections from processes that meet the opt-in criteria:

- **HTTP endpoints** (in service map `endpoints` array):
  - `traffic-generator` → `http-service` (GET /, GET /healthz, POST /echo, GET /slow)

- **Database/Cache/Message Bus connections** (in service map `connections` array):
  - `traffic-generator` → PostgreSQL (port 5432, category: database)
  - `traffic-generator` → Redis (port 6379, category: cache)
  - `http-service` → NATS (port 4222, category: message_bus)
  - `request-logger` → NATS (port 4222, category: message_bus)
  - `request-logger` → Redis (port 6379, category: cache)

The profiler also collects environment variables from each process making network calls. As soon as a new PID is observed, the profiler reads `/proc/<pid>/environ` and writes the results to a separate YAML file.

You can run the profiler first, and it'll hang out waiting for any network traffic to arrive via syscall. Or, if you start it while processes are sending traffic, it will profile for as long as it's running.

## Go Big(-ish)

I've got [another repo](https://github.com/colinjlacy/bookinfo-docker-compose) that puts the [Istio Bookinfo](https://github.com/istio/istio/tree/master/samples/bookinfo) demo into a docker-compose file. 

To profile the Bookinfo services:

**Step 1: Start the profiler with environment-specific filtering and container metadata**

```sh
sudo OUTPUT_PATH="/home/lima.linux/http-profiler/output/ebpf_http_profiler.log" \
     ENV_OUTPUT_PATH="/home/lima.linux/http-profiler/output/ebpf_env_profiler.yaml" \
     SERVICE_MAP_PATH="/home/lima.linux/http-profiler/output/ebpf_service_map.yaml" \
     ENV_PREFIX_LIST="REVIEWS_,RATINGS_,MONGO_,DETAILS_" \
     ADI_PROFILE_ALLOWED="local,dev" \
     CONTAINERD_SOCKET="$XDG_RUNTIME_DIR/containerd/containerd.sock" \
     CONTAINERD_NAMESPACE="default" \
     ./profiler
```

**Step 2: Start the services and run traffic**

From the other repo's project root:
```sh
docker compose up -d
# podman compose up -d
# nerdctl compose up -d
./scripts/run-traffic-gen.sh
```

The profiler will capture HTTP traffic and environment variables from all services that have `ADI_PROFILE=local` or `ADI_PROFILE=dev` set. Because you specified `ENV_PREFIX_LIST`, you'll only see the filtered environment variables (not the full OCI runtime environment). Try it without `ENV_PREFIX_LIST` to see the full environment variable firehose.

Note that the NodeJS traffic generator app was not profiled at all, due to the fact that it does not have `ADI_PROFILE` set.

## Output format

### HTTP Events (JSON)
JSON lines with syscall-derived metadata and parsed HTTP fields:
```json
{
  "timestamp": "2024-04-08T18:24:10.123456789Z",
  "pid": 1234,
  "comm": "traffic-generat",
  "cmdline": "/bin/traffic-generator",
  "direction": "send",
  "source_ip": "127.0.0.1",
  "source_port": 54321,
  "dest_ip": "127.0.0.1",
  "dest_port": 8080,
  "bytes": 89,
  "method": "GET",
  "url": "/echo",
  "body": "{\"message\":\"hello\"}",
  "headers": {
    "Host": "127.0.0.1:8080",
    "User-Agent": "Go-http-client/1.1",
    "Content-Type": "application/json"
  },
  "raw_payload": "GET /echo HTTP/1.1\r\nHost: ...",
  "source_container": {
    "service": "productpage",
    "image": "myorg/productpage:1.0.0",
    "container_id": "abc123def456...",
    "container_name": "productpage-1"
  },
  "destination_container": {
    "service": "reviews",
    "image": "myorg/reviews:2.1.0",
    "container_id": "789xyz012...",
    "container_name": "reviews-1"
  },
  "destination_type": "container"
}
```

Fields include parsed HTTP method, URL, status code (for responses), headers, request/response bodies, plus the complete raw payload from the syscalls.

In addition to HTTP traffic, the profiler also classifies and logs non-HTTP connections to databases, caches, and message buses:

```json
{
  "timestamp": "2024-04-08T18:24:10.123456789Z",
  "event_type": "connection",
  "pid": 1234,
  "comm": "traffic-generat",
  "cmdline": "/bin/traffic-generator",
  "direction": "send",
  "source_ip": "10.4.2.60",
  "source_port": 38338,
  "dest_ip": "10.4.2.59",
  "dest_port": 5432,
  "protocol": "postgres",
  "category": "database",
  "confidence": 90,
  "detection_reason": "port 5432, valid Postgres startup/SSLRequest header"
}
```

Connection events include:
- `event_type`: Always `"connection"` for non-HTTP traffic
- `protocol`: Detected protocol (e.g., `postgres`, `mysql`, `redis`, `kafka`)
- `category`: High-level classification (`database`, `cache`, or `message_bus`)
- `confidence`: Detection confidence score (0-100)
- `detection_reason`: Explanation of how the protocol was identified

**Supported Protocols and Ports:**

| Category | Protocol | Default Ports |
|----------|----------|---------------|
| database | PostgreSQL | 5432 |
| database | MySQL/MariaDB | 3306 |
| database | MongoDB | 27017 |
| database | MSSQL (TDS) | 1433 |
| cache | Redis | 6379, 26379 |
| cache | Memcached | 11211 |
| message_bus | Kafka | 9092, 19092, 29092, 9093 |
| message_bus | AMQP/RabbitMQ | 5672, 5671 |
| message_bus | NATS | 4222, 6222 |

Classification uses a combination of port-based heuristics and protocol fingerprinting on the first bytes of payload. When payload inspection confirms the protocol, confidence is high (90). When only port matching is available (e.g., TLS connections), confidence is medium (60).

### Container metadata fields

**When `CONTAINERD_SOCKET` is configured**, HTTP events and connection events are enriched with container metadata:

- `source_container`: Metadata about the container that **sent** the request (the requester)
  - `service`: Docker Compose service name (from `com.docker.compose.service` label)
  - `image`: Container image with tag
  - `container_id`: Full container ID
  - `container_name`: Human-readable container name
- `destination_container`: Metadata about the container that **received** the request (the responder)
  - Same fields as `source_container`
  - Will be `null` for external destinations
- `destination_type`: Either `"container"` (for container-to-container calls) or `"external"` (for calls outside the container runtime)

**Container labels** are also captured from the container runtime and included in the service map output. This includes:
- Custom application labels (e.g., `app.role`, `app.tier`, `app.component`)
- Docker Compose metadata (e.g., `com.docker.compose.service`, `com.docker.compose.project`)
- Container runtime labels (e.g., `nerdctl/*`, `io.containerd.*`)

Labels can be used to classify and organize services in downstream tooling.

**How it works:**
- For `"send"` events: Source resolved from PID (sender), destination from IP (receiver)
- For `"recv"` events: Source resolved from IP (sender), destination from PID (receiver)
- This ensures `source_container` always means "who sent it" and `destination_container` always means "who received it"
- All container labels are extracted during container resolution and stored with each workload

**Note:** Some `recv` events may have `source_ip: "invalid IP"` when socket peer information isn't available. These events will only have `destination_container` populated. Full service-to-service mapping is captured in the corresponding `send` events.

### Service Map (YAML)

**When `SERVICE_MAP_PATH` is configured**, a service map is maintained in the [ObservedBehaviors format](https://github.com/cncf/toc/issues/1797), which tracks workload identities and their observed network behaviors (HTTP endpoints and non-HTTP connections):

```yaml
apiVersion: adi.dev/v1alpha1
kind: ObservedBehaviors
metadata:
  name: http-profiler-local-2024-04-08t183000z
spec:
  generatedAt: "2024-04-08T18:30:00.000000000Z"
  observationEngines:
    - ref: observationengine/golang-http-profiler
  environment:
    observed: local
  workloads:
    - id: workload:container/productpage
      displayName: productpage
      software:
        image: docker.io/istio/examples-bookinfo-productpage-v1:1.20.1
      labels:
        app.role: web-server
        app.tier: frontend
        app.component: ui
        com.docker.compose.project: bookinfo
        com.docker.compose.service: productpage
      evidence:
        firstSeen: "2024-04-08T18:24:10.000000000Z"
        lastSeen: "2024-04-08T18:30:00.000000000Z"
        sources:
          - engineRef: observationengine/golang-http-profiler
    - id: workload:container/reviews
      displayName: reviews
      software:
        image: docker.io/istio/examples-bookinfo-reviews-v1:1.20.1
      labels:
        app.role: service
        app.tier: backend
        app.component: api
        com.docker.compose.project: bookinfo
        com.docker.compose.service: reviews
      evidence:
        firstSeen: "2024-04-08T18:25:00.000000000Z"
        lastSeen: "2024-04-08T18:29:00.000000000Z"
        sources:
          - engineRef: observationengine/golang-http-profiler
  behaviors:
    - id: behavior:productpage:http:reviews:GET:/reviews/0
      sourceRef: workload:container/productpage
      destination:
        workloadRef: workload:container/reviews
      facets:
        protocol:
          name: http
        interface:
          http:
            method: GET
            path: /reviews/0
            requestSchema: null
            responseSchema:
              clustername: string
              id: string
              podname: string
              reviews:
                - reviewer: string
                  text: string
      evidence:
        firstSeen: "2024-04-08T18:24:10.000000000Z"
        lastSeen: "2024-04-08T18:30:00.000000000Z"
        count: 150
        observerConfidence: 1.0
        sources:
          - engineRef: observationengine/golang-http-profiler
    - id: behavior:productpage:tcp:postgres:5432:postgres
      sourceRef: workload:container/productpage
      destination:
        workloadRef: workload:container/postgres
      facets:
        network:
          transport: tcp
          port: 5432
        protocol:
          name: postgres
          category: database
          classificationConfidence: 0.9
          classificationReason: "port 5432, valid Postgres startup/SSLRequest header"
      evidence:
        firstSeen: "2024-04-08T18:24:10.000000000Z"
        lastSeen: "2024-04-08T18:30:00.000000000Z"
        count: 1
        observerConfidence: 1.0
        sources:
          - engineRef: observationengine/golang-http-profiler
    - id: behavior:productpage:tcp:redis:6379:redis
      sourceRef: workload:container/productpage
      destination:
        workloadRef: workload:container/redis
      facets:
        network:
          transport: tcp
          port: 6379
        protocol:
          name: redis
          category: cache
          classificationConfidence: 0.9
          classificationReason: "redis RESP array"
      evidence:
        firstSeen: "2024-04-08T18:24:10.000000000Z"
        lastSeen: "2024-04-08T18:30:00.000000000Z"
        count: 1
        observerConfidence: 1.0
        sources:
          - engineRef: observationengine/golang-http-profiler
```

**Service Map Structure:**

The map follows the ObservedBehaviors schema with two main sections:

**Workloads** - A catalog of observed services/containers:
- `id`: Stable workload identifier (e.g., `workload:container/productpage`)
- `displayName`: Human-readable service name
- `software.image`: Container image with tag (when available)
- `labels`: All container labels, including:
  - Custom application labels (e.g., `app.role`, `app.tier`, `app.component`)
  - Docker Compose metadata (e.g., `com.docker.compose.service`, `com.docker.compose.project`)
  - Container runtime labels (e.g., `nerdctl/*`, `io.containerd.*`)
- `evidence`: Observation metadata (timestamps, sources)

**Behaviors** - Individual interactions between workloads:
- `id`: Stable behavior identifier encoding the interaction
- `sourceRef`: Reference to the source workload (who initiated the connection)
- `destination.workloadRef`: Reference to the destination workload (who received the connection)
- `facets`: Protocol and interface details
  - For HTTP: `protocol.name: http`, plus `interface.http` with method, path, request/response schemas
  - For non-HTTP: `protocol.name` (e.g., postgres, redis), `protocol.category` (database, cache, message_bus), `network.transport` and `network.port`
- `evidence`: Observation metadata including timestamps, count, confidence, and detection reasoning

**How it works:**
- Container labels are captured automatically from the container runtime when `CONTAINERD_SOCKET` is configured
- All labels (application, Docker Compose, and runtime metadata) are included in workload definitions
- Each unique interaction (source + destination + protocol + endpoint) is tracked as a separate behavior
- Request/response correlation uses source port matching to pair HTTP requests with their responses
- JSON schemas are extracted showing structure (keys and types) without values
- Schema variants are preserved: if an endpoint returns different response shapes, each is tracked separately
- Non-JSON bodies are marked as `"non-json"`, empty bodies as `null`
- Non-HTTP connections are classified on first payload using port and protocol fingerprinting
- Inferred workloads (like databases and message brokers not directly profiled) are added to the catalog automatically
- File is written with a 2-second debounce to coalesce rapid updates
- On SIGINT/SIGTERM, the map is flushed to disk before exit
- Output follows the ObservedBehaviors schema for compatibility with ADI tooling

### Environment Variables (YAML)
Multi-document YAML with one document per PID:
```yaml
---
adi_profile_pid: 12345
adi_profile_match: "local"
adi_profile_name: "reviews"
adi_profile_cmdline: "/usr/bin/python3 /app/server.py --port 9080"
adi_profile_env:
  PATH: "/usr/local/bin:/usr/bin:/bin"
  HOME: "/home/user"
  REVIEWS_SERVICE_URL: "http://reviews:9080"
  RATINGS_HOSTNAME: "ratings"
---
adi_profile_pid: 67890
adi_profile_match: "staging"
adi_profile_cmdline: "./ratings-service"
adi_profile_env:
  MONGO_HOST: "mongodb://db:27017"
  MONGO_DATABASE: "bookinfo"
---
adi_profile_pid: 99999
adi_profile_match: "local"
adi_profile_cmdline: "/app/service --config /etc/config.yaml"
error: "open /proc/99999/environ: no such file or directory"
```

Each document includes:
- `adi_profile_pid`: The process ID
- `adi_profile_match`: The value of the `ADI_PROFILE` environment variable that qualified this process for profiling
- `adi_profile_name`: **(Optional)** The value of `ADI_PROFILE_NAME` if set on the target process. Useful for identifying specific service instances.
- `adi_profile_cmdline`: The command line used to start the process (from `/proc/<pid>/cmdline`)
- `adi_profile_env`: Key-value pairs of environment variables (filtered by `ENV_PREFIX_LIST` if specified)
- `error`: Error message if the process exits before the profiler can read its environment

**Notes:**
- When `ENV_PREFIX_LIST` is used, only matching environment variables are included in `adi_profile_env` (PIDs may have empty `adi_profile_env: {}` if no variables match)
- `adi_profile_name` only appears if the target process has `ADI_PROFILE_NAME` set
- All documents are separated by `---` for proper YAML multi-document format
