package io.runtimeconditions.profiler;

import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

final class JavaProfileExtractor {
    Map<String, Object> extract(Path projectRoot, JavaProfileOptions options) throws IOException {
        DiscoveryResult discovery = new JavaProjectDiscovery().discover(projectRoot, options.discoveryOptions());
        if (discovery.hasErrors()) {
            throw new IOException("Runtime Conditions artifact validation failed: " + diagnostics(discovery));
        }

        List<JavaBindingArtifact> bindings = declarativeBindings(discovery);
        if (bindings.isEmpty()) {
            throw new IOException("no RuntimeConditionsBinding artifacts were discovered");
        }

        List<Path> sourceFiles = javaSourceFiles(discovery.projectRoot(), discovery.modules());
        if (sourceFiles.isEmpty()) {
            throw new IOException("no Java source files found under " + discovery.projectRoot());
        }

        ParsedJavaProgram program = parse(sourceFiles, discovery.classpathEntries());
        ExtractionScanner scanner = new ExtractionScanner(bindings, options, discovery, program.semantic());
        for (CompilationUnitTree unit : program.units()) {
            scanner.collect(unit);
        }
        for (CompilationUnitTree unit : program.units()) {
            scanner.extract(unit);
        }
        Map<String, Object> profile = scanner.profile();
        List<RuntimeConditionsDiagnostic> profileDiagnostics = new JavaProfileValidator().validate(profile, discovery);
        if (!profileDiagnostics.isEmpty()) {
            throw new IOException("generated Runtime Conditions Profile validation failed: " + diagnostics(profileDiagnostics));
        }
        return profile;
    }

    private List<JavaBindingArtifact> declarativeBindings(DiscoveryResult discovery) {
        List<JavaBindingArtifact> bindings = new ArrayList<>();
        for (ValidatedRuntimeConditionsArtifact artifact : discovery.validatedArtifacts()) {
            if (artifact.artifact().kind() != RuntimeConditionsArtifact.Kind.BINDING || artifact.javaManifest() == null) {
                continue;
            }
            bindings.add(new JavaBindingArtifact(artifact));
        }
        return List.copyOf(bindings);
    }

    private ParsedJavaProgram parse(List<Path> sourceFiles, List<Path> classpathEntries) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("JDK compiler is required; run with a JDK rather than a JRE");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sourceFiles);
            List<String> compilerOptions = new ArrayList<>();
            compilerOptions.add("-proc:none");
            if (!classpathEntries.isEmpty()) {
                compilerOptions.add("-classpath");
                compilerOptions.add(joinClasspath(classpathEntries));
                compilerOptions.add("-sourcepath");
                compilerOptions.add(joinClasspath(sourcePathEntries(classpathEntries)));
            }
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, compilerOptions, null, units);
            List<CompilationUnitTree> parsed = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                parsed.add(unit);
            }
            task.analyze();
            return new ParsedJavaProgram(List.copyOf(parsed), SemanticModel.index(task, parsed));
        }
    }

    private List<Path> sourcePathEntries(List<Path> classpathEntries) {
        List<Path> entries = new ArrayList<>();
        for (Path entry : classpathEntries) {
            if (!Files.isDirectory(entry)) {
                continue;
            }
            Path sourceRoot = entry.resolve("src/main/java");
            if (Files.isDirectory(sourceRoot)) {
                entries.add(sourceRoot);
            } else {
                entries.add(entry);
            }
        }
        return entries;
    }

    private List<Path> javaSourceFiles(Path root, List<Path> modules) throws IOException {
        List<Path> roots = new ArrayList<>();
        addSourceRoot(roots, root);
        for (Path module : modules) {
            addSourceRoot(roots, module);
        }
        List<Path> files = new ArrayList<>();
        for (Path sourceRoot : roots) {
            try (var stream = Files.walk(sourceRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(path -> !path.toString().contains("/target/"))
                        .filter(path -> !path.toString().contains("/build/"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        return List.copyOf(files);
    }

    private void addSourceRoot(List<Path> roots, Path projectRoot) {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        if (Files.isDirectory(sourceRoot)) {
            roots.add(sourceRoot);
        } else if (Files.isDirectory(projectRoot)) {
            roots.add(projectRoot);
        }
    }

    private String joinClasspath(List<Path> entries) {
        StringBuilder out = new StringBuilder();
        for (Path entry : entries) {
            if (!out.isEmpty()) {
                out.append(java.io.File.pathSeparator);
            }
            out.append(entry);
        }
        return out.toString();
    }

    private String diagnostics(DiscoveryResult discovery) {
        return diagnostics(discovery.diagnostics());
    }

    private String diagnostics(List<RuntimeConditionsDiagnostic> diagnostics) {
        StringBuilder out = new StringBuilder();
        for (RuntimeConditionsDiagnostic diagnostic : diagnostics) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(diagnostic.message());
        }
        return out.toString();
    }

    private record ParsedJavaProgram(List<CompilationUnitTree> units, SemanticModel semantic) {
    }

    private static final class ExtractionScanner extends TreePathScanner<Void, Void> {
        private final List<JavaBindingArtifact> bindings;
        private final JavaProfileOptions options;
        private final DiscoveryResult discovery;
        private final SemanticModel semantic;
        private final Map<String, JavaBindingArtifact> bindingByClass = new LinkedHashMap<>();
        private final Set<String> usedExtensions = new LinkedHashSet<>();
        private final List<Map<String, Object>> conditions = new ArrayList<>();
        private final Map<String, String> stringConstants = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        private Map<String, JavaBindingArtifact> imports = Map.of();

        private ExtractionScanner(
                List<JavaBindingArtifact> bindings,
                JavaProfileOptions options,
                DiscoveryResult discovery,
                SemanticModel semantic) {
            this.bindings = bindings;
            this.options = options;
            this.discovery = discovery;
            this.semantic = semantic;
            for (JavaBindingArtifact binding : bindings) {
                for (JavaSymbolMapping mapping : binding.allMappings()) {
                    if (mapping.className() == null || binding.manifest().packageName() == null) {
                        continue;
                    }
                    bindingByClass.put(mapping.className(), binding);
                    bindingByClass.put(binding.manifest().packageName() + "." + mapping.className(), binding);
                }
            }
        }

        private void collect(CompilationUnitTree unit) {
            collectSchemas(unit);
            collectStringConstants(unit);
        }

        private void extract(CompilationUnitTree unit) {
            scan(unit, null);
        }

        @Override
        public Void visitCompilationUnit(CompilationUnitTree unit, Void unused) {
            imports = importsFor(unit);
            return super.visitCompilationUnit(unit, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            CallIdentity identity = callIdentity(node);
            if (identity == null) {
                return super.visitMethodInvocation(node, unused);
            }
            JavaBindingArtifact binding = bindingForClass(identity.className());
            if (binding == null) {
                return super.visitMethodInvocation(node, unused);
            }
            JavaSymbolMapping declaration = binding.findDeclaration(identity.className(), identity.memberName());
            if (declaration == null) {
                return super.visitMethodInvocation(node, unused);
            }
            conditions.add(parseCondition(binding, declaration, node));
            usedExtensions.add(binding.extensionId());
            return null;
        }

        Map<String, Object> profile() {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("apiVersion", "runtimeconditions.io/v1alpha1");
            profile.put("kind", "RuntimeConditionsProfile");
            profile.put("metadata", Map.of("name", options.name()));

            Map<String, Object> workload = new LinkedHashMap<>();
            if (!isBlank(options.workloadUri())) {
                workload.put("uri", options.workloadUri());
            }
            if (!isBlank(options.workloadVersion())) {
                workload.put("version", options.workloadVersion());
            }
            profile.put("workload", workload);
            profile.put("extensions", extensionClosure());
            profile.put("conditions", conditions);
            return profile;
        }

        private Map<String, JavaBindingArtifact> importsFor(CompilationUnitTree unit) {
            Map<String, JavaBindingArtifact> result = new LinkedHashMap<>();
            for (ImportTree item : unit.getImports()) {
                String imported = item.getQualifiedIdentifier().toString();
                if (imported.endsWith(".*")) {
                    String packageName = imported.substring(0, imported.length() - 2);
                    for (JavaBindingArtifact binding : bindings) {
                        if (!packageName.equals(binding.manifest().packageName())) {
                            continue;
                        }
                        for (JavaSymbolMapping mapping : binding.allMappings()) {
                            result.put(mapping.className(), binding);
                        }
                    }
                    continue;
                }
                String simpleName = simpleName(imported);
                JavaBindingArtifact binding = bindingByClass.get(imported);
                if (binding != null) {
                    result.put(simpleName, binding);
                }
            }
            return result;
        }

        private Map<String, Object> parseCondition(
                JavaBindingArtifact binding,
                JavaSymbolMapping declaration,
                MethodInvocationTree call) {
            List<? extends ExpressionTree> args = call.getArguments();
            String name = "";
            if (declaration.nameArg() != null) {
                name = stringArg(args, declaration.nameArg(), declaration.memberName(), "name");
            }

            Map<String, Object> condition = new LinkedHashMap<>();
            if (!isBlank(name)) {
                condition.put("name", name);
            }
            condition.put("kind", declaration.kind());
            Map<String, Object> iface = new LinkedHashMap<>();
            iface.put("type", nullToEmpty(declaration.interfaceType()));
            condition.put("interface", iface);

            for (int i = 0; i < args.size(); i++) {
                if (declaration.nameArg() != null && i == declaration.nameArg()) {
                    continue;
                }
                MethodInvocationTree optionCall = asCall(args.get(i));
                if (optionCall == null) {
                    continue;
                }
                OptionMatch match = conditionOptionForCall(binding, declaration, condition, optionCall);
                if (match == null) {
                    continue;
                }
                applyOption(condition, match.binding(), match.option(), optionCall);
                usedExtensions.add(match.binding().extensionId());
            }
            removeEmptyConfiguration(condition);
            return condition;
        }

        private OptionMatch conditionOptionForCall(
                JavaBindingArtifact declarationBinding,
                JavaSymbolMapping declaration,
                Map<String, Object> condition,
                MethodInvocationTree call) {
            CallIdentity identity = callIdentity(call);
            if (identity == null) {
                return null;
            }
            JavaBindingArtifact binding = bindingForClass(identity.className());
            if (binding == null) {
                return null;
            }
            if (binding == declarationBinding) {
                JavaSymbolMapping option = findOption(declaration.options(), identity);
                if (option != null) {
                    return new OptionMatch(binding, option);
                }
            }
            JavaSymbolMapping option = binding.findApplicableRootOption(identity.className(), identity.memberName(), condition);
            return option == null ? null : new OptionMatch(binding, option);
        }

        private void applyOption(
                Map<String, Object> condition,
                JavaBindingArtifact binding,
                JavaSymbolMapping option,
                MethodInvocationTree call) {
            switch (nullToEmpty(option.target())) {
                case "interface.spec" -> applySpec(condition, option, call);
                case "interface.operations[]" -> applyOperation(condition, binding, option, call);
                case "interface.type" -> applyInterfaceType(condition, binding, option, call);
                case "configuration.env[]" -> applyEnv(condition, binding, option, call);
                case "configuration.alternatives[]" -> applyAlternative(condition, binding, option, call);
                default -> throw new IllegalArgumentException("unsupported Java binding target " + option.target());
            }
        }

        private void applySpec(Map<String, Object> condition, JavaSymbolMapping option, MethodInvocationTree call) {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("format", stringArg(call.getArguments(), option.stringArgs().get("format"), option.memberName(), "format"));
            spec.put("uri", stringArg(call.getArguments(), option.stringArgs().get("uri"), option.memberName(), "uri"));
            String version = stringArg(call.getArguments(), option.stringArgs().get("version"), option.memberName(), "version");
            if (!isBlank(version)) {
                spec.put("version", version);
            }
            interfaceMap(condition).put("spec", spec);
        }

        private void applyOperation(
                Map<String, Object> condition,
                JavaBindingArtifact binding,
                JavaSymbolMapping option,
                MethodInvocationTree call) {
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("method", option.method());
            operation.put("path", stringArg(call.getArguments(), option.stringArgs().get("path"), option.memberName(), "path"));
            for (ExpressionTree arg : call.getArguments()) {
                MethodInvocationTree nestedCall = asCall(arg);
                if (nestedCall == null) {
                    continue;
                }
                OptionMatch match = nestedOptionForCall(binding, option.options(), nestedCall);
                if (match == null) {
                    continue;
                }
                applyOperationOption(operation, match.option(), nestedCall);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> operations = (List<Map<String, Object>>) interfaceMap(condition)
                    .computeIfAbsent("operations", ignored -> new ArrayList<Map<String, Object>>());
            operations.add(operation);
        }

        private void applyOperationOption(
                Map<String, Object> operation,
                JavaSymbolMapping option,
                MethodInvocationTree call) {
            if (option.classArg() == null || option.classArg() >= call.getArguments().size()) {
                throw new IllegalArgumentException(option.memberName() + " requires classArg in the binding manifest");
            }
            Object schema = schemaForClassLiteral(call.getArguments().get(option.classArg()));
            switch (nullToEmpty(option.target())) {
                case "requestBodySchema" -> operation.put("requestBodySchema", schema);
                case "responseSchema" -> operation.put("responseSchema", schema);
                default -> throw new IllegalArgumentException("unsupported operation option target " + option.target());
            }
        }

        private void applyInterfaceType(
                Map<String, Object> condition,
                JavaBindingArtifact binding,
                JavaSymbolMapping option,
                MethodInvocationTree call) {
            Map<String, Object> iface = interfaceMap(condition);
            iface.put("type", option.value());
            if (option.enumArg() != null && option.enumArg() < call.getArguments().size()) {
                iface.put("engine", bindingValue(call.getArguments().get(option.enumArg()), binding));
            }
        }

        private void applyEnv(
                Map<String, Object> condition,
                JavaBindingArtifact binding,
                JavaSymbolMapping option,
                MethodInvocationTree call) {
            Map<String, Object> configuration = configurationMap(condition);
            if (configuration.containsKey("alternatives")) {
                throw new IllegalArgumentException(option.memberName() + " cannot be combined with configuration alternatives");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> env = (List<Map<String, Object>>) configuration
                    .computeIfAbsent("env", ignored -> new ArrayList<Map<String, Object>>());
            env.add(envInput(binding, option, call));
        }

        private void applyAlternative(
                Map<String, Object> condition,
                JavaBindingArtifact binding,
                JavaSymbolMapping option,
                MethodInvocationTree call) {
            Map<String, Object> configuration = configurationMap(condition);
            if (configuration.containsKey("env")) {
                throw new IllegalArgumentException(option.memberName() + " cannot be combined with configuration env");
            }
            Map<String, Object> alternative = new LinkedHashMap<>();
            List<Map<String, Object>> env = new ArrayList<>();
            for (ExpressionTree arg : call.getArguments()) {
                MethodInvocationTree nestedCall = asCall(arg);
                if (nestedCall == null) {
                    throw new IllegalArgumentException(option.memberName() + " arguments must be nested env calls");
                }
                OptionMatch match = nestedOptionForCall(binding, option.options(), nestedCall);
                if (match == null) {
                    throw new IllegalArgumentException(option.memberName() + " arguments must match nested option calls");
                }
                env.add(envInput(match.binding(), match.option(), nestedCall));
            }
            alternative.put("env", env);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> alternatives = (List<Map<String, Object>>) configuration
                    .computeIfAbsent("alternatives", ignored -> new ArrayList<Map<String, Object>>());
            alternatives.add(alternative);
        }

        private Map<String, Object> envInput(
                JavaBindingArtifact binding,
                JavaSymbolMapping option,
                MethodInvocationTree call) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("property", stringArg(call.getArguments(), option.stringArgs().get("property"), option.memberName(), "property"));
            env.put("name", stringArg(call.getArguments(), option.stringArgs().get("name"), option.memberName(), "name"));
            for (ExpressionTree arg : call.getArguments()) {
                MethodInvocationTree nestedCall = asCall(arg);
                if (nestedCall == null) {
                    continue;
                }
                OptionMatch match = nestedOptionForCall(binding, option.options(), nestedCall);
                if (match == null) {
                    continue;
                }
                applyEnvOption(env, match.option());
            }
            return env;
        }

        private void applyEnvOption(Map<String, Object> env, JavaSymbolMapping option) {
            boolean value = Boolean.parseBoolean(option.value());
            switch (nullToEmpty(option.target())) {
                case "env.sensitive" -> {
                    if (value) {
                        env.put("sensitive", true);
                    }
                }
                case "env.required" -> env.put("required", value);
                default -> throw new IllegalArgumentException("unsupported env input option target " + option.target());
            }
        }

        private OptionMatch nestedOptionForCall(
                JavaBindingArtifact expectedBinding,
                List<JavaSymbolMapping> options,
                MethodInvocationTree call) {
            CallIdentity identity = callIdentity(call);
            if (identity == null) {
                return null;
            }
            JavaBindingArtifact binding = bindingForClass(identity.className());
            if (binding != expectedBinding) {
                return null;
            }
            JavaSymbolMapping option = findOption(options, identity);
            return option == null ? null : new OptionMatch(binding, option);
        }

        private JavaSymbolMapping findOption(List<JavaSymbolMapping> options, CallIdentity identity) {
            for (JavaSymbolMapping option : options) {
                if (identity.matches(option)) {
                    return option;
                }
            }
            return null;
        }

        private JavaBindingArtifact bindingForClass(String className) {
            JavaBindingArtifact binding = imports.get(className);
            if (binding != null) {
                return binding;
            }
            return bindingByClass.get(className);
        }

        private CallIdentity callIdentity(MethodInvocationTree call) {
            Element element = semantic.element(call.getMethodSelect());
            if (!(element instanceof ExecutableElement)) {
                element = semantic.element(call);
            }
            if (element instanceof ExecutableElement executable) {
                TypeElement owner = enclosingType(executable);
                if (owner != null) {
                    return new CallIdentity(owner.getQualifiedName().toString(), executable.getSimpleName().toString());
                }
            }
            ExpressionTree select = call.getMethodSelect();
            if (select instanceof MemberSelectTree member) {
                return new CallIdentity(member.getExpression().toString(), member.getIdentifier().toString());
            }
            if (select instanceof IdentifierTree identifier) {
                return new CallIdentity("", identifier.getName().toString());
            }
            return null;
        }

        private MethodInvocationTree asCall(ExpressionTree expr) {
            return expr instanceof MethodInvocationTree call ? call : null;
        }

        private String stringArg(List<? extends ExpressionTree> args, Integer index, String function, String name) {
            if (index == null || index >= args.size()) {
                throw new IllegalArgumentException(function + " requires " + name + " argument");
            }
            String value = stringValue(args.get(index));
            if (value == null) {
                throw new IllegalArgumentException(function + " " + name + " must be a string literal or string constant");
            }
            return value;
        }

        private String stringValue(ExpressionTree expr) {
            String semanticValue = semantic.constantString(expr);
            if (semanticValue != null) {
                return semanticValue;
            }
            if (expr instanceof LiteralTree literal && literal.getValue() instanceof String value) {
                return value;
            }
            if (expr instanceof IdentifierTree identifier) {
                return stringConstants.get(identifier.getName().toString());
            }
            return null;
        }

        private String bindingValue(ExpressionTree expr, JavaBindingArtifact binding) {
            String stringValue = stringValue(expr);
            if (stringValue != null) {
                return stringValue;
            }
            String value = binding.manifest().constants().get(expr.toString());
            if (value != null) {
                return value;
            }
            String semanticConstant = semantic.bindingConstantName(expr);
            if (semanticConstant != null) {
                value = binding.manifest().constants().get(semanticConstant);
                if (value != null) {
                    return value;
                }
            }
            throw new IllegalArgumentException("value must be a string literal, string constant, or binding constant");
        }

        private Object schemaForClassLiteral(ExpressionTree expr) {
            if (!(expr instanceof MemberSelectTree member) || !"class".equals(member.getIdentifier().toString())) {
                throw new IllegalArgumentException("schema option requires a class literal");
            }
            TypeMirror mirror = semantic.type(member.getExpression());
            if (mirror != null && mirror.getKind() != TypeKind.ERROR) {
                Object schema = schemaForTypeMirror(mirror, new LinkedHashSet<>());
                if (schema instanceof Map<?, ?> map && map.isEmpty()) {
                    throw new IllegalArgumentException("unsupported schema class " + member.getExpression());
                }
                return schema;
            }
            String className = simpleName(member.getExpression().toString());
            Map<String, Object> schema = schemas.get(className);
            if (schema == null) {
                throw new IllegalArgumentException("unsupported schema class " + className);
            }
            return deepCopy(schema);
        }

        private void collectStringConstants(CompilationUnitTree unit) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    String semanticValue = semantic.constantString(node);
                    if (semanticValue != null) {
                        ModifiersTree modifiers = node.getModifiers();
                        if (modifiers.getFlags().contains(Modifier.STATIC) || modifiers.getFlags().contains(Modifier.FINAL)) {
                            stringConstants.put(node.getName().toString(), semanticValue);
                        }
                    } else if (node.getInitializer() instanceof LiteralTree literal && literal.getValue() instanceof String value) {
                        ModifiersTree modifiers = node.getModifiers();
                        if (modifiers.getFlags().contains(Modifier.STATIC) || modifiers.getFlags().contains(Modifier.FINAL)) {
                            stringConstants.put(node.getName().toString(), value);
                        }
                    }
                    return super.visitVariable(node, unused);
                }
            }.scan(unit, null);
        }

        private void collectSchemas(CompilationUnitTree unit) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    Element element = semantic.element(node);
                    if (element instanceof TypeElement typeElement) {
                        Object semanticSchema = schemaForTypeElement(typeElement, new LinkedHashSet<>());
                        if (semanticSchema instanceof Map<?, ?> map && !map.isEmpty()) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> schema = (Map<String, Object>) semanticSchema;
                            schemas.put(typeElement.getSimpleName().toString(), schema);
                            schemas.put(typeElement.getQualifiedName().toString(), schema);
                        }
                    } else {
                        Map<String, Object> schema = new LinkedHashMap<>();
                        for (Tree member : node.getMembers()) {
                            if (!(member instanceof VariableTree field)) {
                                continue;
                            }
                            if (field.getModifiers().getFlags().contains(Modifier.STATIC)) {
                                continue;
                            }
                            schema.put(field.getName().toString(), schemaForType(field.getType()));
                        }
                        if (!schema.isEmpty()) {
                            schemas.put(node.getSimpleName().toString(), schema);
                        }
                    }
                    return super.visitClass(node, unused);
                }
            }.scan(unit, null);
        }

        private Object schemaForType(Tree type) {
            TypeMirror mirror = semantic.type(type);
            if (mirror != null && mirror.getKind() != TypeKind.ERROR) {
                return schemaForTypeMirror(mirror, new LinkedHashSet<>());
            }
            String value = type.toString();
            if (type instanceof ParameterizedTypeTree parameterized) {
                String raw = parameterized.getType().toString();
                List<? extends Tree> arguments = parameterized.getTypeArguments();
                if ((raw.equals("List") || raw.equals("java.util.List")) && !arguments.isEmpty()) {
                    return List.of(schemaForType(arguments.get(0)));
                }
                if ((raw.equals("Map") || raw.equals("java.util.Map")) && arguments.size() == 2) {
                    return Map.of("additionalProperties", schemaForType(arguments.get(1)));
                }
            }
            if (value.endsWith("[]")) {
                return List.of(schemaForName(value.substring(0, value.length() - 2)));
            }
            return schemaForName(value);
        }

        private Object schemaForName(String name) {
            return switch (name) {
                case "String", "java.lang.String" -> "string";
                case "boolean", "Boolean", "java.lang.Boolean" -> "boolean";
                case "byte", "short", "int", "long", "Byte", "Short", "Integer", "Long" -> "integer";
                case "float", "double", "Float", "Double" -> "number";
                default -> schemas.getOrDefault(simpleName(name), Map.of());
            };
        }

        private Object schemaForTypeMirror(TypeMirror type, Set<String> seen) {
            if (type == null) {
                return Map.of();
            }
            return switch (type.getKind()) {
                case BOOLEAN -> "boolean";
                case BYTE, SHORT, INT, LONG -> "integer";
                case FLOAT, DOUBLE -> "number";
                case ARRAY -> List.of(schemaForTypeMirror(((ArrayType) type).getComponentType(), seen));
                case DECLARED -> schemaForDeclaredType((DeclaredType) type, seen);
                default -> Map.of();
            };
        }

        private Object schemaForDeclaredType(DeclaredType type, Set<String> seen) {
            if (!(type.asElement() instanceof TypeElement element)) {
                return Map.of();
            }
            String qualifiedName = element.getQualifiedName().toString();
            return switch (qualifiedName) {
                case "java.lang.String" -> "string";
                case "java.lang.Boolean" -> "boolean";
                case "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" -> "integer";
                case "java.lang.Float", "java.lang.Double" -> "number";
                case "java.util.List", "java.util.Collection", "java.util.Set", "java.lang.Iterable" ->
                        List.of(type.getTypeArguments().isEmpty()
                                ? Map.of()
                                : schemaForTypeMirror(type.getTypeArguments().get(0), seen));
                case "java.util.Map" -> Map.of(
                        "additionalProperties",
                        type.getTypeArguments().size() < 2
                                ? Map.of()
                                : schemaForTypeMirror(type.getTypeArguments().get(1), seen));
                default -> schemaForTypeElement(element, seen);
            };
        }

        private Object schemaForTypeElement(TypeElement element, Set<String> seen) {
            String qualifiedName = element.getQualifiedName().toString();
            if (!seen.add(qualifiedName)) {
                return Map.of();
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            for (Element enclosed : element.getEnclosedElements()) {
                if (enclosed.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                }
                if (enclosed.getKind() == ElementKind.FIELD || enclosed.getKind() == ElementKind.RECORD_COMPONENT) {
                    schema.put(enclosed.getSimpleName().toString(), schemaForTypeMirror(enclosed.asType(), seen));
                }
            }
            seen.remove(qualifiedName);
            return schema;
        }

        private Map<String, Object> interfaceMap(Map<String, Object> condition) {
            @SuppressWarnings("unchecked")
            Map<String, Object> iface = (Map<String, Object>) condition.get("interface");
            return iface;
        }

        private Map<String, Object> configurationMap(Map<String, Object> condition) {
            @SuppressWarnings("unchecked")
            Map<String, Object> configuration = (Map<String, Object>) condition.computeIfAbsent("configuration", ignored -> new LinkedHashMap<String, Object>());
            return configuration;
        }

        private void removeEmptyConfiguration(Map<String, Object> condition) {
            Object configuration = condition.get("configuration");
            if (configuration instanceof Map<?, ?> map && map.isEmpty()) {
                condition.remove("configuration");
            }
        }

        private List<String> extensionClosure() {
            Map<String, List<String>> dependencies = new LinkedHashMap<>();
            for (ValidatedRuntimeConditionsArtifact artifact : discovery.validatedArtifacts()) {
                if (artifact.extensionId() != null) {
                    dependencies.put(artifact.extensionId(), artifact.dependencies());
                }
            }
            Set<String> resolved = new LinkedHashSet<>();
            for (String extension : usedExtensions) {
                addExtensionClosure(extension, dependencies, resolved);
            }
            return resolved.stream().sorted(Comparator.naturalOrder()).toList();
        }

        private void addExtensionClosure(String extension, Map<String, List<String>> dependencies, Set<String> resolved) {
            for (String dependency : dependencies.getOrDefault(extension, List.of())) {
                addExtensionClosure(dependency, dependencies, resolved);
            }
            resolved.add(extension);
        }
    }

    private static final class JavaBindingArtifact {
        private final ValidatedRuntimeConditionsArtifact artifact;

        private JavaBindingArtifact(ValidatedRuntimeConditionsArtifact artifact) {
            this.artifact = artifact;
        }

        private String extensionId() {
            return artifact.extensionId();
        }

        private JavaManifestModel manifest() {
            return artifact.javaManifest();
        }

        private List<JavaSymbolMapping> allMappings() {
            List<JavaSymbolMapping> mappings = new ArrayList<>();
            mappings.addAll(manifest().declarations());
            mappings.addAll(manifest().options());
            for (JavaSymbolMapping declaration : manifest().declarations()) {
                collectMappings(declaration.options(), mappings);
            }
            for (JavaSymbolMapping option : manifest().options()) {
                collectMappings(option.options(), mappings);
            }
            return mappings;
        }

        private void collectMappings(List<JavaSymbolMapping> source, List<JavaSymbolMapping> target) {
            for (JavaSymbolMapping item : source) {
                target.add(item);
                collectMappings(item.options(), target);
            }
        }

        private JavaSymbolMapping findDeclaration(String className, String memberName) {
            for (JavaSymbolMapping declaration : manifest().declarations()) {
                if (new CallIdentity(className, memberName).matches(declaration)) {
                    return declaration;
                }
            }
            return null;
        }

        private JavaSymbolMapping findApplicableRootOption(String className, String memberName, Map<String, Object> condition) {
            for (JavaSymbolMapping option : manifest().options()) {
                if (!new CallIdentity(className, memberName).matches(option)) {
                    continue;
                }
                if (appliesToCondition(option, condition)) {
                    return option;
                }
            }
            return null;
        }

        private boolean appliesToCondition(JavaSymbolMapping option, Map<String, Object> condition) {
            String kind = String.valueOf(condition.get("kind"));
            if (!option.appliesToKinds().isEmpty() && !option.appliesToKinds().contains(kind)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> iface = (Map<String, Object>) condition.get("interface");
            String interfaceType = String.valueOf(iface.get("type"));
            return option.appliesToInterfaceTypes().isEmpty()
                    || interfaceType.isBlank()
                    || option.appliesToInterfaceTypes().contains(interfaceType);
        }
    }

    private record OptionMatch(JavaBindingArtifact binding, JavaSymbolMapping option) {
    }

    private record CallIdentity(String className, String memberName) {
        private boolean matches(JavaSymbolMapping mapping) {
            if (!memberName.equals(mapping.memberName())) {
                return false;
            }
            String mappingClass = mapping.className();
            return className.equals(mappingClass) || className.endsWith("." + mappingClass);
        }
    }

    private static TypeElement enclosingType(Element element) {
        Element current = element;
        while (current != null) {
            if (current instanceof TypeElement typeElement) {
                return typeElement;
            }
            current = current.getEnclosingElement();
        }
        return null;
    }

    private static final class SemanticModel extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Map<Tree, Element> elements = new IdentityHashMap<>();
        private final Map<Tree, TypeMirror> types = new IdentityHashMap<>();

        private SemanticModel(Trees trees) {
            this.trees = trees;
        }

        private static SemanticModel index(JavacTask task, List<CompilationUnitTree> units) {
            SemanticModel model = new SemanticModel(Trees.instance(task));
            for (CompilationUnitTree unit : units) {
                model.scan(unit, null);
            }
            return model;
        }

        private Element element(Tree tree) {
            return elements.get(tree);
        }

        private TypeMirror type(Tree tree) {
            return types.get(tree);
        }

        private String constantString(Tree tree) {
            Element element = element(tree);
            if (element instanceof VariableElement variable) {
                Object value = variable.getConstantValue();
                if (value instanceof String stringValue) {
                    return stringValue;
                }
            }
            return null;
        }

        private String bindingConstantName(Tree tree) {
            Element element = element(tree);
            if (!(element instanceof VariableElement variable)) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            parts.add(variable.getSimpleName().toString());
            Element owner = variable.getEnclosingElement();
            while (owner instanceof TypeElement typeElement) {
                parts.add(0, typeElement.getSimpleName().toString());
                owner = typeElement.getEnclosingElement();
            }
            return String.join(".", parts);
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            recordCurrent(node);
            return super.visitClass(node, unused);
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            recordCurrent(node);
            recordChild(node.getType());
            recordChild(node.getInitializer());
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            recordCurrent(node);
            return super.visitIdentifier(node, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            recordCurrent(node);
            return super.visitMemberSelect(node, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            recordCurrent(node);
            recordChild(node.getMethodSelect());
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitParameterizedType(ParameterizedTypeTree node, Void unused) {
            recordCurrent(node);
            return super.visitParameterizedType(node, unused);
        }

        @Override
        public Void visitArrayType(ArrayTypeTree node, Void unused) {
            recordCurrent(node);
            return super.visitArrayType(node, unused);
        }

        private void recordCurrent(Tree tree) {
            TreePath path = getCurrentPath();
            if (path != null) {
                record(path, tree);
            }
        }

        private void recordChild(Tree tree) {
            if (tree == null || getCurrentPath() == null) {
                return;
            }
            record(new TreePath(getCurrentPath(), tree), tree);
        }

        private void record(TreePath path, Tree tree) {
            try {
                Element element = trees.getElement(path);
                if (element != null) {
                    elements.put(tree, element);
                }
            } catch (RuntimeException ignored) {
            }
            try {
                TypeMirror type = trees.getTypeMirror(path);
                if (type != null) {
                    types.put(tree, type);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String simpleName(String value) {
        int index = value.lastIndexOf('.');
        return index < 0 ? value : value.substring(index + 1);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }
}
