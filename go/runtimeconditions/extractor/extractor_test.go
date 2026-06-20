package extractor

import (
	"os"
	"path/filepath"
	"slices"
	"testing"
)

func TestExtractDirWithAliasedImport(t *testing.T) {
	dir := t.TempDir()
	source := `package main

import runcon "github.com/colinjlacy/golang-ast-inspection/go/runtimeconditions"

const (
	todoPath = "/todos"
	eventsSubject = "todos.changed"
)

type CreateTodoRequest struct {
	UserID int ` + "`json:\"userId\"`" + `
	Title string ` + "`json:\"title\"`" + `
}

type Todo struct {
	ID int ` + "`json:\"id\"`" + `
	UserID int ` + "`json:\"userId\"`" + `
	Title string ` + "`json:\"title\"`" + `
	Completed bool ` + "`json:\"completed\"`" + `
}

type TodoEvent struct {
	Todo Todo ` + "`json:\"todo\"`" + `
}

var _ = runcon.API("todos-api",
	runcon.POST(todoPath, runcon.Request[CreateTodoRequest](), runcon.Response[Todo]()),
)

var _ = runcon.Datastore("primary-db", runcon.Relational(runcon.MySQL))
var _ = runcon.Cache("todo-cache", runcon.KeyValue(runcon.Redis))
var _ = runcon.MessageBus("todo-events",
	runcon.PubSub(runcon.NATS),
	runcon.Publishes(eventsSubject, runcon.Payload[TodoEvent]()),
)
`

	if err := os.WriteFile(filepath.Join(dir, "main.go"), []byte(source), 0o644); err != nil {
		t.Fatal(err)
	}

	profile, err := ExtractDir(dir, Options{
		Name:            "todos",
		WorkloadURI:     "github.com/example/todos",
		WorkloadVersion: "v0.1.0",
	})
	if err != nil {
		t.Fatal(err)
	}

	if !slices.Equal(profile.Extensions, []string{"https://runtimeconditions.io/extensions/common-integrations:v1alpha1", "runtimeconditions.io/message-bus/v1alpha1"}) {
		t.Fatalf("unexpected extensions: %#v", profile.Extensions)
	}
	if len(profile.Conditions) != 4 {
		t.Fatalf("expected 4 conditions, got %d", len(profile.Conditions))
	}

	api := profile.Conditions[0]
	if api.Name != "todos-api" || api.Kind != "api" || api.Interface.Type != "http" {
		t.Fatalf("unexpected api condition: %#v", api)
	}
	if got := api.Interface.Operations[0].RequestBodySchema.(map[string]any)["userId"]; got != "integer" {
		t.Fatalf("unexpected request schema userId: %#v", got)
	}
	if got := api.Interface.Operations[0].ResponseSchema.(map[string]any)["completed"]; got != "boolean" {
		t.Fatalf("unexpected response schema completed: %#v", got)
	}

	datastore := profile.Conditions[1]
	if datastore.Kind != "datastore" || datastore.Interface.Type != "relational" || datastore.Interface.Engine != "mysql" {
		t.Fatalf("unexpected datastore condition: %#v", datastore)
	}

	cache := profile.Conditions[2]
	if cache.Kind != "cache" || cache.Interface.Type != "key_value" || cache.Interface.Engine != "redis" {
		t.Fatalf("unexpected cache condition: %#v", cache)
	}

	messageBus := profile.Conditions[3]
	if messageBus.Kind != "runtimeconditions.message_bus" || messageBus.Interface.Engine != "nats" {
		t.Fatalf("unexpected message bus condition: %#v", messageBus)
	}
	if got := messageBus.Interface.Subjects[0].PayloadSchema.(map[string]any)["todo"].(map[string]any)["title"]; got != "string" {
		t.Fatalf("unexpected payload schema title: %#v", got)
	}
}

func TestExtractDirWithThirdPartyPackageManifest(t *testing.T) {
	dir := t.TempDir()
	sdkPath, err := filepath.Abs(filepath.Join("..", "..", "..", "demo", "aws-sdk-go-v2"))
	if err != nil {
		t.Fatal(err)
	}
	mod := `module github.com/example/audit-logger

go 1.25.0

require github.com/colinjlacy/golang-http-profiler/demo/aws-sdk-go-v2 v0.0.0

replace github.com/colinjlacy/golang-http-profiler/demo/aws-sdk-go-v2 => ` + sdkPath + `
`
	if err := os.WriteFile(filepath.Join(dir, "go.mod"), []byte(mod), 0o644); err != nil {
		t.Fatal(err)
	}

	source := `package main

import (
	"context"

	"github.com/colinjlacy/golang-http-profiler/demo/aws-sdk-go-v2/service/s3"
)

func writeAuditLog(ctx context.Context) error {
	client := s3.NewFromConfig(s3.Config{})
	_, err := client.PutObject(ctx, &s3.PutObjectInput{})
	return err
}
`

	if err := os.WriteFile(filepath.Join(dir, "main.go"), []byte(source), 0o644); err != nil {
		t.Fatal(err)
	}

	profile, err := ExtractDir(dir, Options{
		Name:            "audit-logger",
		WorkloadURI:     "github.com/example/audit-logger",
		WorkloadVersion: "v0.1.0",
	})
	if err != nil {
		t.Fatal(err)
	}

	if !slices.Equal(profile.Extensions, []string{"https://aws.example.com/runtimeconditions/object-store:v1alpha1"}) {
		t.Fatalf("unexpected extensions: %#v", profile.Extensions)
	}
	if len(profile.Conditions) != 1 {
		t.Fatalf("expected 1 condition, got %d", len(profile.Conditions))
	}

	condition := profile.Conditions[0]
	if condition.Name != "s3-object-store" || condition.Kind != "aws.object_store" {
		t.Fatalf("unexpected condition identity: %#v", condition)
	}
	if condition.Interface.Type != "aws.s3" || condition.Interface.BucketClass != "standard" {
		t.Fatalf("unexpected interface: %#v", condition.Interface)
	}
}
