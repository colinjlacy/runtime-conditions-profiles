ARG GO_VERSION=1.25
FROM golang:${GO_VERSION}-bookworm AS build

RUN apt-get update && apt-get install -y --no-install-recommends \
    clang \
    llvm \
    make \
    pkg-config \
    libelf-dev \
    zlib1g-dev \
    linux-libc-dev \
    libbpf-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY . .
RUN go generate ./pkg/profiler
RUN CGO_ENABLED=0 GOOS=linux go build -o /bin/ebpf-profiler ./cmd/profiler

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=build /bin/ebpf-profiler /usr/local/bin/ebpf-profiler
ENTRYPOINT ["ebpf-profiler"]
