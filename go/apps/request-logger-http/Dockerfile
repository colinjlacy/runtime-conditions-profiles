FROM golang:1.25-alpine AS build

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY runtimeconditions ./runtimeconditions
COPY apps/request-logger-http ./apps/request-logger-http
RUN go build -o /out/request-logger ./apps/request-logger-http

FROM gcr.io/distroless/static-debian12

COPY --from=build /out/request-logger /request-logger

ENTRYPOINT ["/request-logger"]

