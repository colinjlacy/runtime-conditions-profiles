package extractor

import (
	"os"
	"path/filepath"
	"slices"
	"testing"

	"gopkg.in/yaml.v3"
)

func TestExtractDirWithEnvConfigurationFacade(t *testing.T) {
	dir := t.TempDir()
	envPath := extensionModulePath(t, "env-configuration")
	writeModule(t, dir, map[string]string{
		"github.com/colinjlacy/golang-http-profiler/extensions/env-configuration/go": envPath,
	})

	source := `package main

import rc "github.com/colinjlacy/golang-http-profiler/extensions/env-configuration/go"

const (
	todoPath = "/todos"
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

var _ = rc.API("todos-api",
	rc.POST(todoPath, rc.Request[CreateTodoRequest](), rc.Response[Todo]()),
	rc.Env("baseUrl", "TODOS_API_URL"),
)

var _ = rc.Datastore("primary-db", rc.Relational(rc.MySQL))
var _ = rc.Cache("todo-cache",
	rc.KeyValue(rc.Redis),
	rc.EnvAlternative(rc.Env("url", "REDIS_URL")),
	rc.EnvAlternative(rc.Env("hostname", "REDIS_HOST"), rc.Env("port", "REDIS_PORT")),
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

	if !slices.Equal(profile.Extensions, []string{"https://runtimeconditions.io/extensions/env-configuration:v1alpha1"}) {
		t.Fatalf("unexpected extensions: %#v", profile.Extensions)
	}
	resolvedExtensions := resolveExtensionsForTest(t, profile.Extensions, map[string]string{
		"https://runtimeconditions.io/extensions/env-configuration:v1alpha1":   filepath.Join(envPath, "..", "env-configuration-v1alpha1.yaml"),
		"https://runtimeconditions.io/extensions/common-integrations:v1alpha1": filepath.Join(envPath, "..", "..", "common-integrations", "common-integrations-v1alpha1.yaml"),
	})
	if !slices.Contains(resolvedExtensions, "https://runtimeconditions.io/extensions/common-integrations:v1alpha1") {
		t.Fatalf("resolved extensions do not include common integrations: %#v", resolvedExtensions)
	}
	if len(profile.Conditions) != 3 {
		t.Fatalf("expected 3 conditions, got %d", len(profile.Conditions))
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
	if api.Configuration == nil || len(api.Configuration.Env) != 1 || api.Configuration.Env[0].Name != "TODOS_API_URL" {
		t.Fatalf("unexpected api configuration: %#v", api.Configuration)
	}

	datastore := profile.Conditions[1]
	if datastore.Kind != "datastore" || datastore.Interface.Type != "relational" || datastore.Interface.Engine != "mysql" {
		t.Fatalf("unexpected datastore condition: %#v", datastore)
	}

	cache := profile.Conditions[2]
	if cache.Kind != "cache" || cache.Interface.Type != "key_value" || cache.Interface.Engine != "redis" {
		t.Fatalf("unexpected cache condition: %#v", cache)
	}
	if cache.Configuration == nil || len(cache.Configuration.Alternatives) != 2 {
		t.Fatalf("unexpected cache configuration: %#v", cache.Configuration)
	}

}

func TestExtractDirWithCommonIntegrationsOnly(t *testing.T) {
	dir := t.TempDir()
	commonPath := extensionModulePath(t, "common-integrations")
	writeModule(t, dir, map[string]string{
		"github.com/colinjlacy/golang-http-profiler/extensions/common-integrations/go": commonPath,
	})

	source := `package main

import common "github.com/colinjlacy/golang-http-profiler/extensions/common-integrations/go"

var _ = common.Cache("todo-cache", common.KeyValue(common.Redis))
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

	if !slices.Equal(profile.Extensions, []string{"https://runtimeconditions.io/extensions/common-integrations:v1alpha1"}) {
		t.Fatalf("unexpected extensions: %#v", profile.Extensions)
	}
	if len(profile.Conditions) != 1 {
		t.Fatalf("expected 1 condition, got %d", len(profile.Conditions))
	}
	condition := profile.Conditions[0]
	if condition.Kind != "cache" || condition.Interface.Type != "key_value" || condition.Interface.Engine != "redis" {
		t.Fatalf("unexpected condition: %#v", condition)
	}
	if condition.Configuration != nil {
		t.Fatalf("common-only condition should not include env configuration: %#v", condition.Configuration)
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
	if condition.Configuration == nil || len(condition.Configuration.Env) != 4 {
		t.Fatalf("unexpected configuration: %#v", condition.Configuration)
	}
	if condition.Configuration.Env[2].Name != "AWS_ACCESS_KEY_ID" || !condition.Configuration.Env[2].Sensitive {
		t.Fatalf("unexpected credential configuration: %#v", condition.Configuration.Env[2])
	}
}

func extensionModulePath(t *testing.T, name string) string {
	t.Helper()
	path, err := filepath.Abs(filepath.Join("..", "..", "..", "extensions", name, "go"))
	if err != nil {
		t.Fatal(err)
	}
	return path
}

func writeModule(t *testing.T, dir string, replacements map[string]string) {
	t.Helper()
	content := "module github.com/example/testapp\n\ngo 1.25.0\n\nrequire (\n"
	for module := range replacements {
		content += "\t" + module + " v0.0.0\n"
	}
	content += ")\n\n"
	for module, replacement := range replacements {
		content += "replace " + module + " => " + replacement + "\n"
	}
	if err := os.WriteFile(filepath.Join(dir, "go.mod"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func resolveExtensionsForTest(t *testing.T, roots []string, definitions map[string]string) []string {
	t.Helper()
	seen := make(map[string]bool)
	var visit func(string)
	visit = func(id string) {
		if seen[id] {
			return
		}
		seen[id] = true
		for _, dependency := range extensionDependencies(t, definitions[id]) {
			visit(dependency)
		}
	}
	for _, root := range roots {
		visit(root)
	}
	resolved := make([]string, 0, len(seen))
	for id := range seen {
		resolved = append(resolved, id)
	}
	slices.Sort(resolved)
	return resolved
}

func extensionDependencies(t *testing.T, path string) []string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var document struct {
		Spec struct {
			Dependencies []string `yaml:"dependencies"`
		} `yaml:"spec"`
	}
	if err := yaml.Unmarshal(data, &document); err != nil {
		t.Fatal(err)
	}
	return document.Spec.Dependencies
}
