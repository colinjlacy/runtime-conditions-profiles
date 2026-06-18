FROM golang:1.25-alpine AS build

WORKDIR /src
COPY go.mod ./
COPY main.go ./
RUN go build -o /out/todos-api .

FROM gcr.io/distroless/static-debian12

COPY --from=build /out/todos-api /todos-api

ENTRYPOINT ["/todos-api"]

