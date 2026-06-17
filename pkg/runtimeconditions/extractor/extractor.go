package extractor

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"path/filepath"
	"reflect"
	"slices"
	"strconv"
	"strings"
)

const (
	// DeclarationImportPath is the Go import path that this extractor recognizes.
	DeclarationImportPath = "github.com/colinjlacy/golang-ast-inspection/pkg/runtimeconditions"

	coreExtension       = "core"
	messageBusExtension = "runtimeconditions.io/message-bus/v1alpha1"
)

// Options configures source extraction and the generated profile metadata.
type Options struct {
	Name            string
	WorkloadURI     string
	WorkloadVersion string
}

// RuntimeConditionsProfile is the YAML shape emitted by the declarative source
// profiler.
type RuntimeConditionsProfile struct {
	APIVersion string      `yaml:"apiVersion"`
	Kind       string      `yaml:"kind"`
	Metadata   Metadata    `yaml:"metadata"`
	Workload   Workload    `yaml:"workload"`
	Extensions []string    `yaml:"extensions,omitempty"`
	Conditions []Condition `yaml:"conditions"`
}

type Metadata struct {
	Name string `yaml:"name"`
}

type Workload struct {
	URI     string `yaml:"uri,omitempty"`
	Version string `yaml:"version,omitempty"`
}

type Condition struct {
	Name      string    `yaml:"name,omitempty"`
	Kind      string    `yaml:"kind"`
	Interface Interface `yaml:"interface"`
	Optional  bool      `yaml:"optional,omitempty"`
}

type Interface struct {
	Type       string      `yaml:"type"`
	Engine     string      `yaml:"engine,omitempty"`
	Spec       *APISpec    `yaml:"spec,omitempty"`
	Operations []Operation `yaml:"operations,omitempty"`
	Subjects   []Subject   `yaml:"subjects,omitempty"`
}

type APISpec struct {
	Format  string `yaml:"format"`
	URI     string `yaml:"uri"`
	Version string `yaml:"version,omitempty"`
}

type Operation struct {
	Method            string `yaml:"method"`
	Path              string `yaml:"path"`
	RequestBodySchema any    `yaml:"requestBodySchema,omitempty"`
	ResponseSchema    any    `yaml:"responseSchema,omitempty"`
}

type Subject struct {
	Name          string `yaml:"name"`
	Direction     string `yaml:"direction"`
	PayloadSchema any    `yaml:"payloadSchema,omitempty"`
}

type parsedFile struct {
	path string
	file *ast.File
}

type packageScope struct {
	structs      map[string]*ast.StructType
	stringConsts map[string]string
}

type importSet struct {
	aliases map[string]bool
	dot     bool
}

type extractor struct {
	fset  *token.FileSet
	scope *packageScope
}

// ExtractDir reads Go source declarations from dir and converts them to a
// Runtime Conditions Profile. It parses source only; it does not build, import,
// or execute the target package.
func ExtractDir(dir string, opts Options) (*RuntimeConditionsProfile, error) {
	absDir, err := filepath.Abs(dir)
	if err != nil {
		return nil, err
	}

	fset := token.NewFileSet()
	files, err := parseGoFiles(fset, absDir)
	if err != nil {
		return nil, err
	}

	scope := &packageScope{
		structs:      make(map[string]*ast.StructType),
		stringConsts: make(map[string]string),
	}
	for _, parsed := range files {
		scope.collect(parsed.file)
	}

	e := &extractor{fset: fset, scope: scope}
	extensions := map[string]bool{coreExtension: true}
	var conditions []Condition

	for _, parsed := range files {
		imports := runtimeConditionImports(parsed.file)
		if len(imports.aliases) == 0 && !imports.dot {
			continue
		}

		var walkErr error
		ast.Inspect(parsed.file, func(node ast.Node) bool {
			if walkErr != nil {
				return false
			}
			call, ok := node.(*ast.CallExpr)
			if !ok {
				return true
			}
			name, _, ok := callNameAndTypeArgs(call, imports)
			if !ok {
				return true
			}

			var condition Condition
			var err error
			switch name {
			case "API":
				condition, err = e.parseAPI(call, imports)
			case "Datastore":
				condition, err = e.parseDatastore(call, imports)
			case "Cache":
				condition, err = e.parseCache(call, imports)
			case "MessageBus":
				condition, err = e.parseMessageBus(call, imports)
				if err == nil {
					extensions[messageBusExtension] = true
				}
			default:
				return true
			}
			if err != nil {
				walkErr = e.nodeError(call, err)
				return false
			}
			conditions = append(conditions, condition)
			return false
		})
		if walkErr != nil {
			return nil, walkErr
		}
	}

	return &RuntimeConditionsProfile{
		APIVersion: "runtimeconditions.io/v1alpha1",
		Kind:       "RuntimeConditionsProfile",
		Metadata: Metadata{
			Name: opts.Name,
		},
		Workload: Workload{
			URI:     opts.WorkloadURI,
			Version: opts.WorkloadVersion,
		},
		Extensions: sortedExtensions(extensions),
		Conditions: conditions,
	}, nil
}

func parseGoFiles(fset *token.FileSet, root string) ([]parsedFile, error) {
	var files []parsedFile
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			switch d.Name() {
			case ".git", "vendor", "node_modules":
				return filepath.SkipDir
			}
			return nil
		}
		if !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		file, err := parser.ParseFile(fset, path, nil, parser.ParseComments)
		if err != nil {
			return err
		}
		files = append(files, parsedFile{path: path, file: file})
		return nil
	})
	return files, err
}

func (s *packageScope) collect(file *ast.File) {
	for _, decl := range file.Decls {
		gen, ok := decl.(*ast.GenDecl)
		if !ok {
			continue
		}
		switch gen.Tok {
		case token.TYPE:
			for _, spec := range gen.Specs {
				typeSpec, ok := spec.(*ast.TypeSpec)
				if !ok {
					continue
				}
				structType, ok := typeSpec.Type.(*ast.StructType)
				if ok {
					s.structs[typeSpec.Name.Name] = structType
				}
			}
		case token.CONST:
			for _, spec := range gen.Specs {
				valueSpec, ok := spec.(*ast.ValueSpec)
				if !ok {
					continue
				}
				for i, name := range valueSpec.Names {
					if i >= len(valueSpec.Values) {
						continue
					}
					if value, ok := stringLiteral(valueSpec.Values[i]); ok {
						s.stringConsts[name.Name] = value
					}
				}
			}
		}
	}
}

func runtimeConditionImports(file *ast.File) importSet {
	imports := importSet{aliases: make(map[string]bool)}
	for _, spec := range file.Imports {
		pathValue, err := strconv.Unquote(spec.Path.Value)
		if err != nil || pathValue != DeclarationImportPath {
			continue
		}
		if spec.Name == nil {
			imports.aliases["runtimeconditions"] = true
			continue
		}
		switch spec.Name.Name {
		case ".":
			imports.dot = true
		case "_":
			continue
		default:
			imports.aliases[spec.Name.Name] = true
		}
	}
	return imports
}

func callNameAndTypeArgs(call *ast.CallExpr, imports importSet) (string, []ast.Expr, bool) {
	fun := unparen(call.Fun)
	var typeArgs []ast.Expr

	for {
		switch typed := fun.(type) {
		case *ast.IndexExpr:
			typeArgs = append(typeArgs, typed.Index)
			fun = unparen(typed.X)
		case *ast.IndexListExpr:
			typeArgs = append(typeArgs, typed.Indices...)
			fun = unparen(typed.X)
		default:
			goto resolved
		}
	}

resolved:
	switch expr := fun.(type) {
	case *ast.SelectorExpr:
		ident, ok := unparen(expr.X).(*ast.Ident)
		if ok && imports.aliases[ident.Name] {
			return expr.Sel.Name, typeArgs, true
		}
	case *ast.Ident:
		if imports.dot {
			return expr.Name, typeArgs, true
		}
	}
	return "", nil, false
}

func (e *extractor) parseAPI(call *ast.CallExpr, imports importSet) (Condition, error) {
	if len(call.Args) == 0 {
		return Condition{}, fmt.Errorf("API requires a name")
	}
	name, ok := e.stringValue(call.Args[0])
	if !ok {
		return Condition{}, fmt.Errorf("API name must be a string literal or string const")
	}

	condition := Condition{
		Name: name,
		Kind: "api",
		Interface: Interface{
			Type: "http",
		},
	}

	for _, arg := range call.Args[1:] {
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			continue
		}
		optionName, typeArgs, ok := callNameAndTypeArgs(subcall, imports)
		if !ok {
			continue
		}

		switch {
		case isHTTPMethod(optionName):
			operation, err := e.parseOperation(optionName, subcall, imports)
			if err != nil {
				return Condition{}, err
			}
			condition.Interface.Operations = append(condition.Interface.Operations, operation)
		case optionName == "Spec":
			spec, err := e.parseSpec(subcall)
			if err != nil {
				return Condition{}, err
			}
			condition.Interface.Spec = &spec
		case optionName == "Request" || optionName == "Response":
			if len(condition.Interface.Operations) == 0 {
				return Condition{}, fmt.Errorf("%s must follow an HTTP operation", optionName)
			}
			if len(typeArgs) != 1 {
				return Condition{}, fmt.Errorf("%s requires exactly one type argument", optionName)
			}
			schema, err := e.schemaForType(typeArgs[0])
			if err != nil {
				return Condition{}, err
			}
			last := &condition.Interface.Operations[len(condition.Interface.Operations)-1]
			if optionName == "Request" {
				last.RequestBodySchema = schema
			} else {
				last.ResponseSchema = schema
			}
		}
	}

	if condition.Interface.Spec == nil && len(condition.Interface.Operations) == 0 {
		return Condition{}, fmt.Errorf("API requires at least one Spec or HTTP operation")
	}
	return condition, nil
}

func (e *extractor) parseOperation(method string, call *ast.CallExpr, imports importSet) (Operation, error) {
	if len(call.Args) == 0 {
		return Operation{}, fmt.Errorf("%s requires a path", method)
	}
	path, ok := e.stringValue(call.Args[0])
	if !ok {
		return Operation{}, fmt.Errorf("%s path must be a string literal or string const", method)
	}

	operation := Operation{
		Method: method,
		Path:   path,
	}

	for _, arg := range call.Args[1:] {
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			continue
		}
		optionName, typeArgs, ok := callNameAndTypeArgs(subcall, imports)
		if !ok || (optionName != "Request" && optionName != "Response") {
			continue
		}
		if len(typeArgs) != 1 {
			return Operation{}, fmt.Errorf("%s requires exactly one type argument", optionName)
		}
		schema, err := e.schemaForType(typeArgs[0])
		if err != nil {
			return Operation{}, err
		}
		if optionName == "Request" {
			operation.RequestBodySchema = schema
		} else {
			operation.ResponseSchema = schema
		}
	}

	return operation, nil
}

func (e *extractor) parseSpec(call *ast.CallExpr) (APISpec, error) {
	if len(call.Args) < 2 {
		return APISpec{}, fmt.Errorf("Spec requires format and URI")
	}
	format, ok := e.stringValue(call.Args[0])
	if !ok {
		return APISpec{}, fmt.Errorf("Spec format must be a string literal or string const")
	}
	uri, ok := e.stringValue(call.Args[1])
	if !ok {
		return APISpec{}, fmt.Errorf("Spec URI must be a string literal or string const")
	}
	spec := APISpec{Format: format, URI: uri}
	if len(call.Args) > 2 {
		version, ok := e.stringValue(call.Args[2])
		if !ok {
			return APISpec{}, fmt.Errorf("Spec version must be a string literal or string const")
		}
		spec.Version = version
	}
	return spec, nil
}

func (e *extractor) parseDatastore(call *ast.CallExpr, imports importSet) (Condition, error) {
	if len(call.Args) == 0 {
		return Condition{}, fmt.Errorf("Datastore requires a name")
	}
	name, ok := e.stringValue(call.Args[0])
	if !ok {
		return Condition{}, fmt.Errorf("Datastore name must be a string literal or string const")
	}
	condition := Condition{Name: name, Kind: "datastore"}

	for _, arg := range call.Args[1:] {
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			continue
		}
		optionName, _, ok := callNameAndTypeArgs(subcall, imports)
		if !ok {
			continue
		}
		switch optionName {
		case "Relational":
			condition.Interface.Type = "relational"
			if len(subcall.Args) > 0 {
				condition.Interface.Engine = e.engineValue(subcall.Args[0], imports)
			}
		case "Document":
			condition.Interface.Type = "document"
			if len(subcall.Args) > 0 {
				condition.Interface.Engine = e.engineValue(subcall.Args[0], imports)
			}
		}
	}

	if condition.Interface.Type == "" {
		return Condition{}, fmt.Errorf("Datastore requires Relational or Document")
	}
	return condition, nil
}

func (e *extractor) parseCache(call *ast.CallExpr, imports importSet) (Condition, error) {
	if len(call.Args) == 0 {
		return Condition{}, fmt.Errorf("Cache requires a name")
	}
	name, ok := e.stringValue(call.Args[0])
	if !ok {
		return Condition{}, fmt.Errorf("Cache name must be a string literal or string const")
	}
	condition := Condition{Name: name, Kind: "cache"}

	for _, arg := range call.Args[1:] {
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			continue
		}
		optionName, _, ok := callNameAndTypeArgs(subcall, imports)
		if !ok || optionName != "KeyValue" {
			continue
		}
		condition.Interface.Type = "key_value"
		if len(subcall.Args) > 0 {
			condition.Interface.Engine = e.engineValue(subcall.Args[0], imports)
		}
	}

	if condition.Interface.Type == "" {
		return Condition{}, fmt.Errorf("Cache requires KeyValue")
	}
	return condition, nil
}

func (e *extractor) parseMessageBus(call *ast.CallExpr, imports importSet) (Condition, error) {
	if len(call.Args) == 0 {
		return Condition{}, fmt.Errorf("MessageBus requires a name")
	}
	name, ok := e.stringValue(call.Args[0])
	if !ok {
		return Condition{}, fmt.Errorf("MessageBus name must be a string literal or string const")
	}
	condition := Condition{
		Name: name,
		Kind: "runtimeconditions.message_bus",
		Interface: Interface{
			Type: "runtimeconditions.pubsub",
		},
	}

	for _, arg := range call.Args[1:] {
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			continue
		}
		optionName, _, ok := callNameAndTypeArgs(subcall, imports)
		if !ok {
			continue
		}
		switch optionName {
		case "PubSub":
			if len(subcall.Args) > 0 {
				condition.Interface.Engine = e.engineValue(subcall.Args[0], imports)
			}
		case "Publishes", "Subscribes":
			subject, err := e.parseSubject(optionName, subcall, imports)
			if err != nil {
				return Condition{}, err
			}
			condition.Interface.Subjects = append(condition.Interface.Subjects, subject)
		}
	}
	return condition, nil
}

func (e *extractor) parseSubject(optionName string, call *ast.CallExpr, imports importSet) (Subject, error) {
	if len(call.Args) == 0 {
		return Subject{}, fmt.Errorf("%s requires a subject name", optionName)
	}
	name, ok := e.stringValue(call.Args[0])
	if !ok {
		return Subject{}, fmt.Errorf("%s subject must be a string literal or string const", optionName)
	}

	subject := Subject{Name: name}
	if optionName == "Publishes" {
		subject.Direction = "publish"
	} else {
		subject.Direction = "subscribe"
	}

	for _, arg := range call.Args[1:] {
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			continue
		}
		suboptionName, typeArgs, ok := callNameAndTypeArgs(subcall, imports)
		if !ok || suboptionName != "Payload" {
			continue
		}
		if len(typeArgs) != 1 {
			return Subject{}, fmt.Errorf("Payload requires exactly one type argument")
		}
		schema, err := e.schemaForType(typeArgs[0])
		if err != nil {
			return Subject{}, err
		}
		subject.PayloadSchema = schema
	}
	return subject, nil
}

func (e *extractor) schemaForType(expr ast.Expr) (any, error) {
	switch typed := unparen(expr).(type) {
	case *ast.Ident:
		if schemaType, ok := builtinSchemaType(typed.Name); ok {
			return schemaType, nil
		}
		structType, ok := e.scope.structs[typed.Name]
		if !ok {
			return nil, fmt.Errorf("unsupported schema type %q", typed.Name)
		}
		return e.schemaForStruct(structType)
	case *ast.StarExpr:
		return e.schemaForType(typed.X)
	case *ast.ArrayType:
		element, err := e.schemaForType(typed.Elt)
		if err != nil {
			return nil, err
		}
		return []any{element}, nil
	case *ast.SelectorExpr:
		if ident, ok := unparen(typed.X).(*ast.Ident); ok && ident.Name == "time" && typed.Sel.Name == "Time" {
			return "string", nil
		}
		return nil, fmt.Errorf("unsupported external schema type %s", exprString(typed))
	default:
		return nil, fmt.Errorf("unsupported schema expression %s", exprString(typed))
	}
}

func (e *extractor) schemaForStruct(structType *ast.StructType) (map[string]any, error) {
	schema := make(map[string]any)
	for _, field := range structType.Fields.List {
		if len(field.Names) == 0 {
			nested, err := e.schemaForType(field.Type)
			if err != nil {
				return nil, err
			}
			nestedMap, ok := nested.(map[string]any)
			if !ok {
				continue
			}
			for key, value := range nestedMap {
				schema[key] = value
			}
			continue
		}

		for _, name := range field.Names {
			if !name.IsExported() {
				continue
			}
			jsonName, skip := jsonFieldName(name.Name, field.Tag)
			if skip {
				continue
			}
			fieldSchema, err := e.schemaForType(field.Type)
			if err != nil {
				return nil, err
			}
			schema[jsonName] = fieldSchema
		}
	}
	return schema, nil
}

func jsonFieldName(defaultName string, tag *ast.BasicLit) (string, bool) {
	if tag == nil {
		return defaultName, false
	}
	tagValue, err := strconv.Unquote(tag.Value)
	if err != nil {
		return defaultName, false
	}
	jsonTag := reflect.StructTag(tagValue).Get("json")
	if jsonTag == "" {
		return defaultName, false
	}
	name := strings.Split(jsonTag, ",")[0]
	switch name {
	case "-":
		return "", true
	case "":
		return defaultName, false
	default:
		return name, false
	}
}

func builtinSchemaType(name string) (string, bool) {
	switch name {
	case "string":
		return "string", true
	case "bool":
		return "boolean", true
	case "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr":
		return "integer", true
	case "float32", "float64":
		return "number", true
	default:
		return "", false
	}
}

func (e *extractor) stringValue(expr ast.Expr) (string, bool) {
	if value, ok := stringLiteral(expr); ok {
		return value, true
	}
	ident, ok := unparen(expr).(*ast.Ident)
	if !ok {
		return "", false
	}
	value, ok := e.scope.stringConsts[ident.Name]
	return value, ok
}

func stringLiteral(expr ast.Expr) (string, bool) {
	lit, ok := unparen(expr).(*ast.BasicLit)
	if !ok || lit.Kind != token.STRING {
		return "", false
	}
	value, err := strconv.Unquote(lit.Value)
	return value, err == nil
}

func (e *extractor) engineValue(expr ast.Expr, imports importSet) string {
	if value, ok := e.stringValue(expr); ok {
		return value
	}
	switch typed := unparen(expr).(type) {
	case *ast.SelectorExpr:
		if ident, ok := unparen(typed.X).(*ast.Ident); ok && imports.aliases[ident.Name] {
			return engineName(typed.Sel.Name)
		}
	case *ast.Ident:
		if imports.dot {
			return engineName(typed.Name)
		}
	}
	return ""
}

func engineName(name string) string {
	switch name {
	case "Postgres":
		return "postgres"
	case "MySQL":
		return "mysql"
	case "MariaDB":
		return "mariadb"
	case "SQLServer":
		return "sqlserver"
	case "Oracle":
		return "oracle"
	case "SQLite":
		return "sqlite"
	case "MongoDB":
		return "mongodb"
	case "Couchbase":
		return "couchbase"
	case "Redis":
		return "redis"
	case "Memcached":
		return "memcached"
	case "NATS":
		return "nats"
	default:
		return strings.ToLower(name)
	}
}

func isHTTPMethod(name string) bool {
	switch name {
	case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE":
		return true
	default:
		return false
	}
}

func sortedExtensions(extensions map[string]bool) []string {
	result := make([]string, 0, len(extensions))
	for extension := range extensions {
		result = append(result, extension)
	}
	slices.Sort(result)
	return result
}

func (e *extractor) nodeError(node ast.Node, err error) error {
	position := e.fset.Position(node.Pos())
	return fmt.Errorf("%s: %w", position, err)
}

func unparen(expr ast.Expr) ast.Expr {
	for {
		paren, ok := expr.(*ast.ParenExpr)
		if !ok {
			return expr
		}
		expr = paren.X
	}
}

func exprString(expr ast.Expr) string {
	switch typed := expr.(type) {
	case *ast.Ident:
		return typed.Name
	case *ast.SelectorExpr:
		return exprString(typed.X) + "." + typed.Sel.Name
	case *ast.StarExpr:
		return "*" + exprString(typed.X)
	case *ast.ArrayType:
		return "[]" + exprString(typed.Elt)
	default:
		return fmt.Sprintf("%T", expr)
	}
}
