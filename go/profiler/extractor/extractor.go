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

type goBinding struct {
	ExtensionID             string
	ExtensionDefinitionPath string
	ImportPath              string
	PackageName             string
	Constants               map[string]string
	Constructors            []goBindingConstructor
	Declarations            []goBindingDeclaration
	Options                 []goBindingOption
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
	Function                string            `yaml:"function"`
	Target                  string            `yaml:"target"`
	Value                   string            `yaml:"value"`
	ValueArg                *int              `yaml:"valueArg"`
	TypeArg                 *int              `yaml:"typeArg"`
	EngineArg               *int              `yaml:"engineArg"`
	Method                  string            `yaml:"method"`
	AppliesToKinds          []string          `yaml:"appliesToKinds"`
	AppliesToInterfaceTypes []string          `yaml:"appliesToInterfaceTypes"`
	StringArgs              map[string]int    `yaml:"stringArgs"`
	Options                 []goBindingOption `yaml:"options"`
}

type goBindingOptionMatch struct {
	binding  *goBinding
	option   goBindingOption
	typeArgs []ast.Expr
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
		Options      []goBindingOption      `yaml:"options"`
	} `yaml:"go"`
}

type goModule struct {
	path     string
	dir      string
	replaces map[string]string
}

const (
	goBindingsManifest       = "runtimeconditions.bindings.yaml"
	legacyGoBindingManifest  = "runtimeconditions.binding.yaml"
	goPackageBindingManifest = "runtimeconditions.package.yaml"
)

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
		bindingImports := runtimeConditionBindingImports(parsed.file, bindings)
		if len(bindingImports.aliases) == 0 && len(bindingImports.dot) == 0 {
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
			binding, declaration, ok := bindingDeclarationForCall(call, bindingImports)
			if !ok {
				return true
			}
			condition, optionBindings, err := e.parseBindingCondition(call, binding, declaration, bindingImports)
			if err != nil {
				walkErr = e.nodeError(call, err)
				return false
			}
			extensions[binding.ExtensionID] = true
			for _, optionBinding := range optionBindings {
				extensions[optionBinding.ExtensionID] = true
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
			if !isExtensionBindingManifest(d.Name()) {
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
		manifestPath, ok, err := findPackageBindingManifest(packageDir)
		if err != nil {
			return nil, err
		}
		if !ok || seen[manifestPath] {
			continue
		}
		seen[manifestPath] = true
		binding, err := readGoBinding(manifestPath)
		if err != nil {
			return nil, err
		}
		bindings = append(bindings, binding)
	}
	return bindings, nil
}

func isExtensionBindingManifest(name string) bool {
	return name == goBindingsManifest || name == legacyGoBindingManifest
}

func findPackageBindingManifest(dir string) (string, bool, error) {
	for _, name := range []string{goBindingsManifest, goPackageBindingManifest} {
		path := filepath.Join(dir, name)
		if _, err := os.Stat(path); err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return "", false, err
		}
		return path, true, nil
	}
	return "", false, nil
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
	if len(document.Go.Declarations) == 0 && len(document.Go.Options) == 0 {
		return nil, fmt.Errorf("%s: go.declarations or go.options must not be empty", path)
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
		Options:                 document.Go.Options,
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

func bindingOptionForCall(call *ast.CallExpr, imports goBindingImports, binding *goBinding, options []goBindingOption) (goBindingOptionMatch, bool) {
	name, typeArgs, optionBinding, ok := callNameTypeArgsAndBinding(call, imports)
	if !ok || optionBinding != binding {
		return goBindingOptionMatch{}, false
	}
	for _, option := range options {
		if option.Function == name {
			return goBindingOptionMatch{binding: optionBinding, option: option, typeArgs: typeArgs}, true
		}
	}
	return goBindingOptionMatch{}, false
}

func bindingConditionOptionForCall(call *ast.CallExpr, imports goBindingImports, declarationBinding *goBinding, declaration goBindingDeclaration, condition Condition) (goBindingOptionMatch, bool) {
	name, typeArgs, optionBinding, ok := callNameTypeArgsAndBinding(call, imports)
	if !ok {
		return goBindingOptionMatch{}, false
	}
	if optionBinding == declarationBinding {
		for _, option := range declaration.Options {
			if option.Function == name {
				return goBindingOptionMatch{binding: optionBinding, option: option, typeArgs: typeArgs}, true
			}
		}
	}
	for _, option := range optionBinding.Options {
		if option.Function == name && optionAppliesToCondition(option, condition) {
			return goBindingOptionMatch{binding: optionBinding, option: option, typeArgs: typeArgs}, true
		}
	}
	return goBindingOptionMatch{}, false
}

func optionAppliesToCondition(option goBindingOption, condition Condition) bool {
	if len(option.AppliesToKinds) > 0 && !slices.Contains(option.AppliesToKinds, condition.Kind) {
		return false
	}
	if len(option.AppliesToInterfaceTypes) > 0 && condition.Interface.Type != "" && !slices.Contains(option.AppliesToInterfaceTypes, condition.Interface.Type) {
		return false
	}
	return true
}

func callNameAndBinding(call *ast.CallExpr, imports goBindingImports) (string, *goBinding, bool) {
	name, _, binding, ok := callNameTypeArgsAndBinding(call, imports)
	return name, binding, ok
}

func callNameTypeArgsAndBinding(call *ast.CallExpr, imports goBindingImports) (string, []ast.Expr, *goBinding, bool) {
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
		if !ok {
			return "", nil, nil, false
		}
		binding := imports.aliases[ident.Name]
		if binding == nil {
			return "", nil, nil, false
		}
		return expr.Sel.Name, typeArgs, binding, true
	case *ast.Ident:
		for _, binding := range imports.dot {
			if binding.hasDeclaration(expr.Name) || binding.hasOption(expr.Name) || binding.hasConstant(expr.Name) {
				return expr.Name, typeArgs, binding, true
			}
		}
	}
	return "", nil, nil, false
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
	if hasBindingOption(b.Options, name) {
		return true
	}
	for _, declaration := range b.Declarations {
		if hasBindingOption(declaration.Options, name) {
			return true
		}
	}
	return false
}

func hasBindingOption(options []goBindingOption, name string) bool {
	for _, option := range options {
		if option.Function == name || hasBindingOption(option.Options, name) {
			return true
		}
	}
	return false
}

func (b *goBinding) hasConstant(name string) bool {
	_, ok := b.Constants[name]
	return ok
}

func (e *extractor) parseBindingCondition(call *ast.CallExpr, binding *goBinding, declaration goBindingDeclaration, imports goBindingImports) (Condition, []*goBinding, error) {
	name := declaration.Name
	if declaration.NameArg != nil {
		if *declaration.NameArg >= len(call.Args) {
			return Condition{}, nil, fmt.Errorf("%s requires a name argument", declaration.displayName())
		}
		value, ok := e.stringValue(call.Args[*declaration.NameArg])
		if !ok {
			return Condition{}, nil, fmt.Errorf("%s name must be a string literal or string const", declaration.displayName())
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
			return Condition{}, nil, err
		}
	}

	usedOptionBindings := make(map[*goBinding]bool)
	for i, arg := range call.Args {
		if declaration.NameArg != nil && i == *declaration.NameArg {
			continue
		}
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			continue
		}
		match, ok := bindingConditionOptionForCall(subcall, imports, binding, declaration, condition)
		if !ok {
			continue
		}
		if err := e.applyBindingOption(&condition, match.option, subcall, match.typeArgs, imports, match.binding); err != nil {
			return Condition{}, nil, err
		}
		if match.binding != binding {
			usedOptionBindings[match.binding] = true
		}
	}

	optionBindings := make([]*goBinding, 0, len(usedOptionBindings))
	for optionBinding := range usedOptionBindings {
		optionBindings = append(optionBindings, optionBinding)
	}
	return condition, optionBindings, nil
}

func (e *extractor) applyBindingOption(
	condition *Condition,
	option goBindingOption,
	call *ast.CallExpr,
	typeArgs []ast.Expr,
	imports goBindingImports,
	binding *goBinding,
) error {
	_ = typeArgs
	switch option.Target {
	case "interface.spec":
		spec, err := e.parseBindingSpec(option, call)
		if err != nil {
			return err
		}
		condition.Interface.Spec = &spec
		return nil
	case "interface.operations[]":
		operation, err := e.parseBindingOperation(option, call, imports, binding)
		if err != nil {
			return err
		}
		condition.Interface.Operations = append(condition.Interface.Operations, operation)
		return nil
	case "interface.type":
		condition.Interface.Type = option.Value
		if option.EngineArg != nil {
			if *option.EngineArg >= len(call.Args) {
				return fmt.Errorf("%s requires an engine argument", option.Function)
			}
			engine, ok := e.bindingValue(call.Args[*option.EngineArg], imports, binding)
			if !ok {
				return fmt.Errorf("%s engine must be a string literal, string const, or binding constant", option.Function)
			}
			condition.Interface.Engine = engine
		}
		return nil
	case "configuration.env[]":
		env, err := e.parseBindingEnvInput(option, call, imports, binding)
		if err != nil {
			return err
		}
		if condition.Configuration != nil && len(condition.Configuration.Alternatives) > 0 {
			return fmt.Errorf("%s cannot be combined with configuration alternatives", option.Function)
		}
		if condition.Configuration == nil {
			condition.Configuration = &Configuration{}
		}
		condition.Configuration.Env = append(condition.Configuration.Env, env)
		return nil
	case "configuration.alternatives[]":
		alternative, err := e.parseBindingEnvAlternative(option, call, imports, binding)
		if err != nil {
			return err
		}
		if condition.Configuration != nil && len(condition.Configuration.Env) > 0 {
			return fmt.Errorf("%s cannot be combined with configuration env", option.Function)
		}
		if condition.Configuration == nil {
			condition.Configuration = &Configuration{}
		}
		condition.Configuration.Alternatives = append(condition.Configuration.Alternatives, alternative)
		return nil
	case "requestBodySchema", "responseSchema":
		return fmt.Errorf("%s is only valid as an operation option", option.Function)
	default:
		value, err := e.bindingOptionValue(option, call, imports, binding)
		if err != nil {
			return err
		}
		return applyBindingValue(condition, option.Target, value)
	}
}

func (e *extractor) parseBindingSpec(option goBindingOption, call *ast.CallExpr) (APISpec, error) {
	format, err := e.bindingStringArg(call, option, "format", true)
	if err != nil {
		return APISpec{}, err
	}
	uri, err := e.bindingStringArg(call, option, "uri", true)
	if err != nil {
		return APISpec{}, err
	}
	version, err := e.bindingStringArg(call, option, "version", false)
	if err != nil {
		return APISpec{}, err
	}
	return APISpec{Format: format, URI: uri, Version: version}, nil
}

func (e *extractor) parseBindingOperation(option goBindingOption, call *ast.CallExpr, imports goBindingImports, binding *goBinding) (Operation, error) {
	path, err := e.bindingStringArg(call, option, "path", true)
	if err != nil {
		return Operation{}, err
	}
	operation := Operation{Method: option.Method, Path: path}
	for _, arg := range call.Args[1:] {
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			continue
		}
		match, ok := bindingOptionForCall(subcall, imports, binding, option.Options)
		if !ok {
			continue
		}
		if err := e.applyBindingOperationOption(&operation, match.option, match.typeArgs); err != nil {
			return Operation{}, err
		}
	}
	return operation, nil
}

func (e *extractor) applyBindingOperationOption(operation *Operation, option goBindingOption, typeArgs []ast.Expr) error {
	if option.TypeArg == nil {
		return fmt.Errorf("%s requires typeArg in binding manifest", option.Function)
	}
	if *option.TypeArg >= len(typeArgs) {
		return fmt.Errorf("%s requires a type argument", option.Function)
	}
	schema, err := e.schemaForType(typeArgs[*option.TypeArg])
	if err != nil {
		return err
	}
	switch option.Target {
	case "requestBodySchema":
		operation.RequestBodySchema = schema
	case "responseSchema":
		operation.ResponseSchema = schema
	default:
		return fmt.Errorf("unsupported operation option target %q", option.Target)
	}
	return nil
}

func (e *extractor) parseBindingEnvAlternative(option goBindingOption, call *ast.CallExpr, imports goBindingImports, binding *goBinding) (ConfigurationAlternative, error) {
	if len(call.Args) == 0 {
		return ConfigurationAlternative{}, fmt.Errorf("%s requires at least one env input", option.Function)
	}
	alternative := ConfigurationAlternative{}
	for _, arg := range call.Args {
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			return ConfigurationAlternative{}, fmt.Errorf("%s arguments must match nested option calls declared by the package manifest", option.Function)
		}
		match, ok := bindingOptionForCall(subcall, imports, binding, option.Options)
		if !ok {
			return ConfigurationAlternative{}, fmt.Errorf("%s arguments must match nested option calls declared by the package manifest", option.Function)
		}
		env, err := e.parseBindingEnvInput(match.option, subcall, imports, match.binding)
		if err != nil {
			return ConfigurationAlternative{}, err
		}
		alternative.Env = append(alternative.Env, env)
	}
	return alternative, nil
}

func (e *extractor) parseBindingEnvInput(option goBindingOption, call *ast.CallExpr, imports goBindingImports, binding *goBinding) (EnvInput, error) {
	property, err := e.bindingStringArg(call, option, "property", true)
	if err != nil {
		return EnvInput{}, err
	}
	name, err := e.bindingStringArg(call, option, "name", true)
	if err != nil {
		return EnvInput{}, err
	}
	env := EnvInput{Property: property, Name: name}
	for _, arg := range call.Args[2:] {
		subcall, ok := unparen(arg).(*ast.CallExpr)
		if !ok {
			continue
		}
		match, ok := bindingOptionForCall(subcall, imports, binding, option.Options)
		if !ok {
			continue
		}
		if err := applyBindingEnvInputOption(&env, match.option); err != nil {
			return EnvInput{}, err
		}
	}
	return env, nil
}

func applyBindingEnvInputOption(env *EnvInput, option goBindingOption) error {
	switch option.Target {
	case "env.sensitive":
		value, err := strconv.ParseBool(option.Value)
		if err != nil {
			return fmt.Errorf("%s has invalid boolean value %q", option.Function, option.Value)
		}
		env.Sensitive = value
	case "env.required":
		value, err := strconv.ParseBool(option.Value)
		if err != nil {
			return fmt.Errorf("%s has invalid boolean value %q", option.Function, option.Value)
		}
		env.Required = &value
	default:
		return fmt.Errorf("unsupported env input option target %q", option.Target)
	}
	return nil
}

func (e *extractor) bindingStringArg(call *ast.CallExpr, option goBindingOption, name string, required bool) (string, error) {
	index, ok := option.StringArgs[name]
	if !ok {
		if required {
			return "", fmt.Errorf("%s binding is missing stringArgs.%s", option.Function, name)
		}
		return "", nil
	}
	if index >= len(call.Args) {
		if required {
			return "", fmt.Errorf("%s requires %s argument", option.Function, name)
		}
		return "", nil
	}
	value, ok := e.stringValue(call.Args[index])
	if !ok {
		return "", fmt.Errorf("%s %s must be a string literal or string const", option.Function, name)
	}
	return value, nil
}

func (e *extractor) bindingOptionValue(option goBindingOption, call *ast.CallExpr, imports goBindingImports, binding *goBinding) (string, error) {
	if option.Value != "" {
		return option.Value, nil
	}
	if option.ValueArg == nil {
		return "", fmt.Errorf("%s binding must declare value or valueArg", option.Function)
	}
	if *option.ValueArg >= len(call.Args) {
		return "", fmt.Errorf("%s requires a value argument", option.Function)
	}
	value, ok := e.bindingValue(call.Args[*option.ValueArg], imports, binding)
	if !ok {
		return "", fmt.Errorf("%s value must be a string literal, string const, or binding constant", option.Function)
	}
	return value, nil
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
