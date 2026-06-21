package extractor

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

const (
	// DeclarationImportPath is the Go import path that this extractor recognizes.
	DeclarationImportPath = "github.com/colinjlacy/golang-ast-inspection/go/runtimeconditions"

	commonIntegrationsExtension = "https://runtimeconditions.io/extensions/common-integrations:v1alpha1"
	envConfigurationExtension   = "https://runtimeconditions.io/extensions/env-configuration:v1alpha1"
	messageBusExtension         = "runtimeconditions.io/message-bus/v1alpha1"
)

// Options configures source extraction and the generated profile metadata.
type Options struct {
	Name            string
	WorkloadURI     string
	WorkloadVersion string
	ExtensionRoots  []string
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
	Name          string         `yaml:"name,omitempty"`
	Kind          string         `yaml:"kind"`
	Interface     Interface      `yaml:"interface"`
	Optional      bool           `yaml:"optional,omitempty"`
	Configuration *Configuration `yaml:"configuration,omitempty"`
}

type Interface struct {
	Type        string      `yaml:"type"`
	Engine      string      `yaml:"engine,omitempty"`
	BucketClass string      `yaml:"bucketClass,omitempty"`
	Spec        *APISpec    `yaml:"spec,omitempty"`
	Operations  []Operation `yaml:"operations,omitempty"`
	Subjects    []Subject   `yaml:"subjects,omitempty"`
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

type Configuration struct {
	Env          []EnvInput                 `yaml:"env,omitempty"`
	Alternatives []ConfigurationAlternative `yaml:"alternatives,omitempty"`
}

type ConfigurationAlternative struct {
	Env []EnvInput `yaml:"env"`
}

type EnvInput struct {
	Property  string `yaml:"property"`
	Name      string `yaml:"name"`
	Sensitive bool   `yaml:"sensitive,omitempty"`
	Required  *bool  `yaml:"required,omitempty"`
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

type goBinding struct {
	ExtensionID             string
	ExtensionDefinitionPath string
	ImportPath              string
	PackageName             string
	Constants               map[string]string
	Constructors            []goBindingConstructor
	Declarations            []goBindingDeclaration
}

type goBindingConstructor struct {
	Function string `yaml:"function"`
	Receiver string `yaml:"receiver"`
}

type goBindingDeclaration struct {
	Function      string            `yaml:"function"`
	Receiver      string            `yaml:"receiver"`
	Method        string            `yaml:"method"`
	Name          string            `yaml:"name"`
	Kind          string            `yaml:"kind"`
	InterfaceType string            `yaml:"interfaceType"`
	NameArg       *int              `yaml:"nameArg"`
	Configuration *Configuration    `yaml:"configuration"`
	Values        []goBindingValue  `yaml:"values"`
	Options       []goBindingOption `yaml:"options"`
}

type goBindingValue struct {
	Target string `yaml:"target"`
	Value  string `yaml:"value"`
}

type goBindingOption struct {
	Function string `yaml:"function"`
	Target   string `yaml:"target"`
	ValueArg int    `yaml:"valueArg"`
}

type goBindingImports struct {
	aliases      map[string]*goBinding
	dot          []*goBinding
	receiverVars map[string]goBindingReceiver
}

type goBindingReceiver struct {
	binding  *goBinding
	receiver string
}

type goBindingDocument struct {
	APIVersion string `yaml:"apiVersion"`
	Kind       string `yaml:"kind"`
	Metadata   struct {
		Extension           string `yaml:"extension"`
		ExtensionDefinition string `yaml:"extensionDefinition"`
		Package             string `yaml:"package"`
		Language            string `yaml:"language"`
	} `yaml:"metadata"`
	Extension struct {
		ID         string `yaml:"id"`
		Definition string `yaml:"definition"`
	} `yaml:"extension"`
	Go struct {
		ImportPath   string                 `yaml:"importPath"`
		Package      string                 `yaml:"package"`
		Constants    map[string]string      `yaml:"constants"`
		Constructors []goBindingConstructor `yaml:"constructors"`
		Declarations []goBindingDeclaration `yaml:"declarations"`
	} `yaml:"go"`
}

type goModule struct {
	path     string
	dir      string
	replaces map[string]string
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
	packageBindings, err := discoverGoPackageBindings(absDir, files)
	if err != nil {
		return nil, err
	}
	bindings, err := discoverGoBindings(opts.ExtensionRoots)
	if err != nil {
		return nil, err
	}
	bindings = append(bindings, packageBindings...)

	scope := &packageScope{
		structs:      make(map[string]*ast.StructType),
		stringConsts: make(map[string]string),
	}
	for _, parsed := range files {
		scope.collect(parsed.file)
	}

	e := &extractor{fset: fset, scope: scope}
	extensions := make(map[string]bool)
	var conditions []Condition

	for _, parsed := range files {
		imports := runtimeConditionImports(parsed.file)
		bindingImports := runtimeConditionBindingImports(parsed.file, bindings)
		if len(imports.aliases) == 0 && !imports.dot && len(bindingImports.aliases) == 0 && len(bindingImports.dot) == 0 {
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
			if ok {
				var condition Condition
				var err error
				switch name {
				case "API":
					condition, err = e.parseAPI(call, imports)
					if err == nil {
						extensions[commonIntegrationsExtension] = true
					}
				case "Datastore":
					condition, err = e.parseDatastore(call, imports)
					if err == nil {
						extensions[commonIntegrationsExtension] = true
					}
				case "Cache":
					condition, err = e.parseCache(call, imports)
					if err == nil {
						extensions[commonIntegrationsExtension] = true
					}
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
				if condition.Configuration != nil {
					extensions[envConfigurationExtension] = true
				}
				conditions = append(conditions, condition)
				return false
			}

			binding, declaration, ok := bindingDeclarationForCall(call, bindingImports)
			if !ok {
				return true
			}
			condition, err := e.parseBindingCondition(call, binding, declaration, bindingImports)
			if err != nil {
				walkErr = e.nodeError(call, err)
				return false
			}
			extensions[binding.ExtensionID] = true
			if condition.Configuration != nil {
				extensions[envConfigurationExtension] = true
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

func discoverGoBindings(roots []string) ([]*goBinding, error) {
	var bindings []*goBinding
	for _, root := range roots {
		if strings.TrimSpace(root) == "" {
			continue
		}
		absRoot, err := filepath.Abs(root)
		if err != nil {
			return nil, err
		}
		err = filepath.WalkDir(absRoot, func(path string, d fs.DirEntry, err error) error {
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
			if d.Name() != "runtimeconditions.binding.yaml" {
				return nil
			}
			binding, err := readGoBinding(path)
			if err != nil {
				return err
			}
			bindings = append(bindings, binding)
			return nil
		})
		if err != nil {
			return nil, err
		}
	}
	return bindings, nil
}

func discoverGoPackageBindings(sourceDir string, files []parsedFile) ([]*goBinding, error) {
	module, err := readGoModule(sourceDir)
	if err != nil {
		return nil, err
	}
	if module == nil {
		return nil, nil
	}

	var bindings []*goBinding
	seen := make(map[string]bool)
	for _, importPath := range directImportPaths(files) {
		packageDir := module.resolveImport(importPath)
		if packageDir == "" {
			continue
		}
		manifestPath := filepath.Join(packageDir, "runtimeconditions.package.yaml")
		if seen[manifestPath] {
			continue
		}
		seen[manifestPath] = true
		if _, err := os.Stat(manifestPath); err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return nil, err
		}
		binding, err := readGoBinding(manifestPath)
		if err != nil {
			return nil, err
		}
		bindings = append(bindings, binding)
	}
	return bindings, nil
}

func readGoBinding(path string) (*goBinding, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var document goBindingDocument
	if err := yaml.Unmarshal(data, &document); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	if document.Kind != "RuntimeConditionsGoBinding" && document.Kind != "RuntimeConditionsPackage" {
		return nil, fmt.Errorf("%s: unsupported binding kind %q", path, document.Kind)
	}

	extensionID := document.Metadata.Extension
	extensionDefinition := document.Metadata.ExtensionDefinition
	if document.Kind == "RuntimeConditionsPackage" {
		extensionID = document.Extension.ID
		extensionDefinition = document.Extension.Definition
	}

	if extensionID == "" {
		return nil, fmt.Errorf("%s: metadata.extension is required", path)
	}
	if document.Go.ImportPath == "" {
		return nil, fmt.Errorf("%s: go.importPath is required", path)
	}
	if len(document.Go.Declarations) == 0 {
		return nil, fmt.Errorf("%s: go.declarations must not be empty", path)
	}
	definitionPath := extensionDefinition
	if definitionPath != "" && !filepath.IsAbs(definitionPath) {
		definitionPath = filepath.Join(filepath.Dir(path), definitionPath)
	}
	if definitionPath != "" {
		if _, err := os.Stat(definitionPath); err != nil {
			return nil, fmt.Errorf("%s: extension definition %q: %w", path, definitionPath, err)
		}
	}
	return &goBinding{
		ExtensionID:             extensionID,
		ExtensionDefinitionPath: definitionPath,
		ImportPath:              document.Go.ImportPath,
		PackageName:             document.Go.Package,
		Constants:               document.Go.Constants,
		Constructors:            document.Go.Constructors,
		Declarations:            document.Go.Declarations,
	}, nil
}

func directImportPaths(files []parsedFile) []string {
	seen := make(map[string]bool)
	for _, parsed := range files {
		for _, spec := range parsed.file.Imports {
			pathValue, err := strconv.Unquote(spec.Path.Value)
			if err == nil {
				seen[pathValue] = true
			}
		}
	}
	result := make([]string, 0, len(seen))
	for pathValue := range seen {
		result = append(result, pathValue)
	}
	slices.Sort(result)
	return result
}

func readGoModule(sourceDir string) (*goModule, error) {
	for current := sourceDir; ; current = filepath.Dir(current) {
		modPath := filepath.Join(current, "go.mod")
		module, err := parseGoMod(modPath)
		if err == nil {
			module.dir = current
			return module, nil
		}
		if err != nil && !os.IsNotExist(err) {
			return nil, err
		}
		parent := filepath.Dir(current)
		if parent == current {
			return nil, nil
		}
	}
}

func parseGoMod(path string) (*goModule, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	module := &goModule{replaces: make(map[string]string)}
	inReplaceBlock := false
	for _, rawLine := range strings.Split(string(data), "\n") {
		line := strings.TrimSpace(stripGoModComment(rawLine))
		if line == "" {
			continue
		}
		if inReplaceBlock {
			if line == ")" {
				inReplaceBlock = false
				continue
			}
			parseReplaceLine(line, module.replaces)
			continue
		}
		if strings.HasPrefix(line, "module ") {
			module.path = strings.TrimSpace(strings.TrimPrefix(line, "module "))
			continue
		}
		if line == "replace (" {
			inReplaceBlock = true
			continue
		}
		if strings.HasPrefix(line, "replace ") {
			parseReplaceLine(strings.TrimSpace(strings.TrimPrefix(line, "replace ")), module.replaces)
		}
	}
	if module.path == "" {
		return nil, fmt.Errorf("%s: module path is required", path)
	}
	return module, nil
}

func stripGoModComment(line string) string {
	if index := strings.Index(line, "//"); index >= 0 {
		return line[:index]
	}
	return line
}

func parseReplaceLine(line string, replaces map[string]string) {
	fields := strings.Fields(line)
	for i, field := range fields {
		if field != "=>" || i == 0 || i+1 >= len(fields) {
			continue
		}
		replaces[fields[i-1]] = fields[i+1]
		return
	}
}

func (m *goModule) resolveImport(importPath string) string {
	type candidate struct {
		modulePath string
		dir        string
	}
	candidates := []candidate{{modulePath: m.path, dir: m.dir}}
	for modulePath, replacement := range m.replaces {
		if !isLocalReplacement(replacement) {
			continue
		}
		dir := replacement
		if !filepath.IsAbs(dir) {
			dir = filepath.Join(m.dir, dir)
		}
		candidates = append(candidates, candidate{modulePath: modulePath, dir: filepath.Clean(dir)})
	}

	var best candidate
	for _, item := range candidates {
		if importPath != item.modulePath && !strings.HasPrefix(importPath, item.modulePath+"/") {
			continue
		}
		if len(item.modulePath) > len(best.modulePath) {
			best = item
		}
	}
	if best.modulePath == "" {
		return ""
	}
	suffix := strings.TrimPrefix(importPath, best.modulePath)
	suffix = strings.TrimPrefix(suffix, "/")
	return filepath.Join(best.dir, filepath.FromSlash(suffix))
}

func isLocalReplacement(path string) bool {
	return filepath.IsAbs(path) || strings.HasPrefix(path, ".")
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

func runtimeConditionBindingImports(file *ast.File, bindings []*goBinding) goBindingImports {
	imports := goBindingImports{
		aliases:      make(map[string]*goBinding),
		receiverVars: make(map[string]goBindingReceiver),
	}
	bindingsByImport := make(map[string]*goBinding, len(bindings))
	for _, binding := range bindings {
		bindingsByImport[binding.ImportPath] = binding
	}

	for _, spec := range file.Imports {
		pathValue, err := strconv.Unquote(spec.Path.Value)
		if err != nil {
			continue
		}
		binding := bindingsByImport[pathValue]
		if binding == nil {
			continue
		}
		if spec.Name == nil {
			if binding.PackageName != "" {
				imports.aliases[binding.PackageName] = binding
			}
			continue
		}
		switch spec.Name.Name {
		case ".":
			imports.dot = append(imports.dot, binding)
		case "_":
			continue
		default:
			imports.aliases[spec.Name.Name] = binding
		}
	}
	imports.collectReceiverVars(file)
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

func bindingDeclarationForCall(call *ast.CallExpr, imports goBindingImports) (*goBinding, goBindingDeclaration, bool) {
	name, binding, ok := callNameAndBinding(call, imports)
	if ok {
		for _, declaration := range binding.Declarations {
			if declaration.Function == name {
				return binding, declaration, true
			}
		}
	}

	name, receiver, ok := receiverMethodNameAndBinding(call, imports)
	if ok {
		for _, declaration := range receiver.binding.Declarations {
			if declaration.Method == name && (declaration.Receiver == "" || declaration.Receiver == receiver.receiver) {
				return receiver.binding, declaration, true
			}
		}
	}

	return nil, goBindingDeclaration{}, false
}

func (imports goBindingImports) collectReceiverVars(file *ast.File) {
	ast.Inspect(file, func(node ast.Node) bool {
		switch typed := node.(type) {
		case *ast.AssignStmt:
			for i, rhs := range typed.Rhs {
				if i >= len(typed.Lhs) {
					continue
				}
				name, ok := typed.Lhs[i].(*ast.Ident)
				if !ok {
					continue
				}
				imports.collectReceiverVar(name.Name, rhs)
			}
		case *ast.ValueSpec:
			for i, value := range typed.Values {
				if i >= len(typed.Names) {
					continue
				}
				imports.collectReceiverVar(typed.Names[i].Name, value)
			}
		}
		return true
	})
}

func (imports goBindingImports) collectReceiverVar(name string, expr ast.Expr) {
	call, ok := unparen(expr).(*ast.CallExpr)
	if !ok {
		return
	}
	binding, receiver, ok := bindingConstructorForCall(call, imports)
	if ok {
		imports.receiverVars[name] = goBindingReceiver{binding: binding, receiver: receiver}
	}
}

func bindingConstructorForCall(call *ast.CallExpr, imports goBindingImports) (*goBinding, string, bool) {
	name, binding, ok := callNameAndBinding(call, imports)
	if !ok {
		return nil, "", false
	}
	for _, constructor := range binding.Constructors {
		if constructor.Function == name {
			return binding, constructor.Receiver, true
		}
	}
	return nil, "", false
}

func bindingOptionForCall(call *ast.CallExpr, imports goBindingImports, binding *goBinding, declaration goBindingDeclaration) (goBindingOption, bool) {
	name, optionBinding, ok := callNameAndBinding(call, imports)
	if !ok || optionBinding != binding {
		return goBindingOption{}, false
	}
	for _, option := range declaration.Options {
		if option.Function == name {
			return option, true
		}
	}
	return goBindingOption{}, false
}

func callNameAndBinding(call *ast.CallExpr, imports goBindingImports) (string, *goBinding, bool) {
	fun := unparen(call.Fun)
	for {
		switch typed := fun.(type) {
		case *ast.IndexExpr:
			fun = unparen(typed.X)
		case *ast.IndexListExpr:
			fun = unparen(typed.X)
		default:
			goto resolved
		}
	}

resolved:
	switch expr := fun.(type) {
	case *ast.SelectorExpr:
		ident, ok := unparen(expr.X).(*ast.Ident)
		if !ok {
			return "", nil, false
		}
		binding := imports.aliases[ident.Name]
		if binding == nil {
			return "", nil, false
		}
		return expr.Sel.Name, binding, true
	case *ast.Ident:
		for _, binding := range imports.dot {
			if binding.hasDeclaration(expr.Name) || binding.hasOption(expr.Name) || binding.hasConstant(expr.Name) {
				return expr.Name, binding, true
			}
		}
	}
	return "", nil, false
}

func receiverMethodNameAndBinding(call *ast.CallExpr, imports goBindingImports) (string, goBindingReceiver, bool) {
	fun := unparen(call.Fun)
	for {
		switch typed := fun.(type) {
		case *ast.IndexExpr:
			fun = unparen(typed.X)
		case *ast.IndexListExpr:
			fun = unparen(typed.X)
		default:
			goto resolved
		}
	}

resolved:
	expr, ok := fun.(*ast.SelectorExpr)
	if !ok {
		return "", goBindingReceiver{}, false
	}
	ident, ok := unparen(expr.X).(*ast.Ident)
	if !ok {
		return "", goBindingReceiver{}, false
	}
	receiver, ok := imports.receiverVars[ident.Name]
	if !ok || receiver.binding == nil {
		return "", goBindingReceiver{}, false
	}
	return expr.Sel.Name, receiver, true
}

func (b *goBinding) hasDeclaration(name string) bool {
	for _, declaration := range b.Declarations {
		if declaration.Function == name {
			return true
		}
	}
	return false
}

func (b *goBinding) hasOption(name string) bool {
	for _, declaration := range b.Declarations {
		for _, option := range declaration.Options {
			if option.Function == name {
				return true
			}
		}
	}
	return false
}

func (b *goBinding) hasConstant(name string) bool {
	_, ok := b.Constants[name]
	return ok
}

func (e *extractor) parseBindingCondition(call *ast.CallExpr, binding *goBinding, declaration goBindingDeclaration, imports goBindingImports) (Condition, error) {
	name := declaration.Name
	if declaration.NameArg != nil {
		if *declaration.NameArg >= len(call.Args) {
			return Condition{}, fmt.Errorf("%s requires a name argument", declaration.displayName())
		}
		value, ok := e.stringValue(call.Args[*declaration.NameArg])
		if !ok {
			return Condition{}, fmt.Errorf("%s name must be a string literal or string const", declaration.displayName())
		}
		name = value
	}

	condition := Condition{
		Name: name,
		Kind: declaration.Kind,
		Interface: Interface{
			Type: declaration.InterfaceType,
		},
		Configuration: cloneConfiguration(declaration.Configuration),
	}

	for _, value := range declaration.Values {
		if err := applyBindingValue(&condition, value.Target, value.Value); err != nil {
			return Condition{}, err
		}
	}

	for i, arg := range call.Args {
		if declaration.NameArg != nil && i == *declaration.NameArg {
			continue
		}
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			continue
		}
		option, ok := bindingOptionForCall(subcall, imports, binding, declaration)
		if !ok {
			continue
		}
		if option.ValueArg >= len(subcall.Args) {
			return Condition{}, fmt.Errorf("%s requires a value argument", option.Function)
		}
		value, ok := e.bindingValue(subcall.Args[option.ValueArg], imports, binding)
		if !ok {
			return Condition{}, fmt.Errorf("%s value must be a string literal, string const, or binding constant", option.Function)
		}
		if err := applyBindingValue(&condition, option.Target, value); err != nil {
			return Condition{}, err
		}
	}

	return condition, nil
}

func cloneConfiguration(config *Configuration) *Configuration {
	if config == nil {
		return nil
	}
	clone := &Configuration{}
	if len(config.Env) > 0 {
		clone.Env = append([]EnvInput(nil), config.Env...)
	}
	if len(config.Alternatives) > 0 {
		clone.Alternatives = make([]ConfigurationAlternative, 0, len(config.Alternatives))
		for _, alternative := range config.Alternatives {
			clone.Alternatives = append(clone.Alternatives, ConfigurationAlternative{
				Env: append([]EnvInput(nil), alternative.Env...),
			})
		}
	}
	return clone
}

func (d goBindingDeclaration) displayName() string {
	if d.Function != "" {
		return d.Function
	}
	if d.Method != "" {
		return d.Method
	}
	return "declaration"
}

func (e *extractor) applyConfigurationOption(condition *Condition, optionName string, call *ast.CallExpr, imports importSet) (bool, error) {
	switch optionName {
	case "Env":
		env, err := e.parseEnvInput(call, imports)
		if err != nil {
			return true, err
		}
		if condition.Configuration != nil && len(condition.Configuration.Alternatives) > 0 {
			return true, fmt.Errorf("Env cannot be combined with EnvAlternative")
		}
		if condition.Configuration == nil {
			condition.Configuration = &Configuration{}
		}
		condition.Configuration.Env = append(condition.Configuration.Env, env)
		return true, nil
	case "EnvAlternative":
		alternative, err := e.parseEnvAlternative(call, imports)
		if err != nil {
			return true, err
		}
		if condition.Configuration != nil && len(condition.Configuration.Env) > 0 {
			return true, fmt.Errorf("EnvAlternative cannot be combined with Env")
		}
		if condition.Configuration == nil {
			condition.Configuration = &Configuration{}
		}
		condition.Configuration.Alternatives = append(condition.Configuration.Alternatives, alternative)
		return true, nil
	default:
		return false, nil
	}
}

func (e *extractor) parseEnvAlternative(call *ast.CallExpr, imports importSet) (ConfigurationAlternative, error) {
	if len(call.Args) == 0 {
		return ConfigurationAlternative{}, fmt.Errorf("EnvAlternative requires at least one Env")
	}
	alternative := ConfigurationAlternative{}
	for _, arg := range call.Args {
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			return ConfigurationAlternative{}, fmt.Errorf("EnvAlternative arguments must be Env calls")
		}
		optionName, _, ok := callNameAndTypeArgs(subcall, imports)
		if !ok || optionName != "Env" {
			return ConfigurationAlternative{}, fmt.Errorf("EnvAlternative arguments must be Env calls")
		}
		env, err := e.parseEnvInput(subcall, imports)
		if err != nil {
			return ConfigurationAlternative{}, err
		}
		alternative.Env = append(alternative.Env, env)
	}
	return alternative, nil
}

func (e *extractor) parseEnvInput(call *ast.CallExpr, imports importSet) (EnvInput, error) {
	if len(call.Args) < 2 {
		return EnvInput{}, fmt.Errorf("Env requires property and name")
	}
	property, ok := e.stringValue(call.Args[0])
	if !ok {
		return EnvInput{}, fmt.Errorf("Env property must be a string literal or string const")
	}
	name, ok := e.stringValue(call.Args[1])
	if !ok {
		return EnvInput{}, fmt.Errorf("Env name must be a string literal or string const")
	}
	env := EnvInput{Property: property, Name: name}
	for _, arg := range call.Args[2:] {
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			continue
		}
		optionName, _, ok := callNameAndTypeArgs(subcall, imports)
		if !ok {
			continue
		}
		switch optionName {
		case "Sensitive":
			env.Sensitive = true
		case "Optional":
			required := false
			env.Required = &required
		}
	}
	return env, nil
}

func (e *extractor) bindingValue(expr ast.Expr, imports goBindingImports, expected *goBinding) (string, bool) {
	if value, ok := e.stringValue(expr); ok {
		return value, true
	}
	switch typed := unparen(expr).(type) {
	case *ast.SelectorExpr:
		ident, ok := unparen(typed.X).(*ast.Ident)
		if !ok {
			return "", false
		}
		binding := imports.aliases[ident.Name]
		if binding != expected {
			return "", false
		}
		value, ok := binding.Constants[typed.Sel.Name]
		return value, ok
	case *ast.Ident:
		for _, binding := range imports.dot {
			if binding != expected {
				continue
			}
			value, ok := binding.Constants[typed.Name]
			if ok {
				return value, true
			}
		}
	}
	return "", false
}

func applyBindingValue(condition *Condition, target string, value string) error {
	switch target {
	case "interface.bucketClass":
		condition.Interface.BucketClass = value
	default:
		return fmt.Errorf("unsupported binding target %q", target)
	}
	return nil
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

		if handled, err := e.applyConfigurationOption(&condition, optionName, subcall, imports); handled {
			if err != nil {
				return Condition{}, err
			}
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
		if handled, err := e.applyConfigurationOption(&condition, optionName, subcall, imports); handled {
			if err != nil {
				return Condition{}, err
			}
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
		if !ok {
			continue
		}
		if handled, err := e.applyConfigurationOption(&condition, optionName, subcall, imports); handled {
			if err != nil {
				return Condition{}, err
			}
			continue
		}
		if optionName == "KeyValue" {
			condition.Interface.Type = "key_value"
			if len(subcall.Args) > 0 {
				condition.Interface.Engine = e.engineValue(subcall.Args[0], imports)
			}
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
		if handled, err := e.applyConfigurationOption(&condition, optionName, subcall, imports); handled {
			if err != nil {
				return Condition{}, err
			}
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
