package io.runtimeconditions.profiler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ArtifactValidator {
    private static final String API_VERSION = "runtimeconditions.io/v1alpha1";
    private static final String EXTENSION_KIND = "RuntimeConditionsExtensionDefinition";
    private static final String BINDING_KIND = "RuntimeConditionsBinding";
    private static final String PACKAGE_KIND = "RuntimeConditionsPackage";
    private static final String LANGUAGE = "java";
    private static final Pattern JAVA_PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;");
    private static final Pattern JAVA_METHOD = Pattern.compile("(?m)\\bpublic\\s+static\\s+(?:<[^>]+>\\s+)?[A-Za-z0-9_.$<>\\[\\]?]+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)");
    private static final Pattern JAVA_ENUM_VALUE = Pattern.compile("(?m)\\b([A-Z][A-Z0-9_]*)\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern JAVA_STRING_CONSTANT = Pattern.compile("(?m)\\b([A-Z][A-Z0-9_]*)\\s*=\\s*\"([^\"]*)\"");

    List<ValidatedRuntimeConditionsArtifact> validate(List<RuntimeConditionsArtifact> artifacts) {
        List<ValidatedRuntimeConditionsArtifact> validated = new ArrayList<>();
        for (RuntimeConditionsArtifact artifact : artifacts) {
            validated.add(validateArtifact(artifact));
        }
        validateExtensionSet(validated);
        return validated;
    }

    private ValidatedRuntimeConditionsArtifact validateArtifact(RuntimeConditionsArtifact artifact) {
        List<RuntimeConditionsDiagnostic> diagnostics = new ArrayList<>();
        String manifestExtensionId = null;
        String extensionId = null;
        String extensionDefinitionUri = null;
        ExtensionDefinitionModel extensionDefinition = null;
        JavaManifestModel javaManifest = null;
        List<String> dependencies = List.of();
        YamlDocument manifest = null;

        try {
            manifest = YamlDocument.parse(readResource(artifact.manifestUri()));
        } catch (IOException | IllegalArgumentException e) {
            diagnostics.add(error("package-manifest", artifact.manifestUri(), "failed to read manifest: " + e.getMessage()));
        }

        if (manifest != null) {
            validateRequiredValue(manifest.scalar("apiVersion"), API_VERSION, "apiVersion", artifact.manifestUri(), diagnostics);
            String expectedKind = artifact.kind() == RuntimeConditionsArtifact.Kind.BINDING ? BINDING_KIND : PACKAGE_KIND;
            validateRequiredValue(manifest.scalar("kind"), expectedKind, "kind", artifact.manifestUri(), diagnostics);
            validateRequiredValue(manifest.scalar("metadata", "language"), LANGUAGE, "metadata.language", artifact.manifestUri(), diagnostics);
            if (!manifest.hasSection(LANGUAGE)) {
                diagnostics.add(error("package-language", artifact.manifestUri(), "java section is required"));
            } else {
                javaManifest = new JavaManifestParser().parse(manifest, artifact.manifestUri(), diagnostics);
            }

            if (artifact.kind() == RuntimeConditionsArtifact.Kind.BINDING) {
                manifestExtensionId = requireScalar(manifest, artifact.manifestUri(), diagnostics, "metadata", "extension");
                requireScalar(manifest, artifact.manifestUri(), diagnostics, "java", "package");
                extensionDefinitionUri = resolveExtensionDefinition(
                        artifact,
                        manifest.scalar("metadata", "extensionDefinition"),
                        diagnostics);
            } else {
                requireScalar(manifest, artifact.manifestUri(), diagnostics, "metadata", "package");
                manifestExtensionId = requireScalar(manifest, artifact.manifestUri(), diagnostics, "extension", "id");
                extensionDefinitionUri = resolveExtensionDefinition(
                        artifact,
                        manifest.scalar("extension", "definition"),
                        diagnostics);
            }
            validateExtensionId(manifestExtensionId, artifact.manifestUri(), diagnostics);
        }

        if (extensionDefinitionUri != null) {
            try {
                YamlDocument extension = YamlDocument.parse(readResource(extensionDefinitionUri));
                validateRequiredValue(extension.scalar("apiVersion"), API_VERSION, "apiVersion", extensionDefinitionUri, diagnostics);
                validateRequiredValue(extension.scalar("kind"), EXTENSION_KIND, "kind", extensionDefinitionUri, diagnostics);
                extensionId = requireScalar(extension, extensionDefinitionUri, diagnostics, "metadata", "id");
                validateExtensionId(extensionId, extensionDefinitionUri, diagnostics);
                extensionDefinition = ExtensionDefinitionModel.parse(extension, extensionDefinitionUri, diagnostics);
                dependencies = extension.stringList("spec", "dependencies");
            } catch (IOException | IllegalArgumentException e) {
                diagnostics.add(error("extension-definition", extensionDefinitionUri, "failed to read extension definition: " + e.getMessage()));
            }
        }

        if (manifestExtensionId != null && extensionId != null && !manifestExtensionId.equals(extensionId)) {
            diagnostics.add(error(
                    "extension-definition",
                    artifact.manifestUri(),
                    "manifest extension id " + manifestExtensionId + " does not match extension definition " + extensionId));
        }

        return new ValidatedRuntimeConditionsArtifact(
                artifact,
                manifestExtensionId,
                extensionId,
                extensionDefinitionUri,
                extensionDefinition,
                javaManifest,
                dependencies,
                diagnostics);
    }

    private void validateExtensionSet(List<ValidatedRuntimeConditionsArtifact> artifacts) {
        Map<String, String> definitionsById = new LinkedHashMap<>();
        Map<String, ExtensionDefinitionModel> definitionModelsById = new LinkedHashMap<>();
        for (ValidatedRuntimeConditionsArtifact artifact : artifacts) {
            if (artifact.extensionId() == null) {
                continue;
            }
            String previous = definitionsById.putIfAbsent(artifact.extensionId(), artifact.extensionDefinitionUri());
            if (previous != null && !previous.equals(artifact.extensionDefinitionUri())) {
                artifact.addDiagnostic(error(
                        "extension-definition",
                        artifact.extensionDefinitionUri(),
                        "duplicate extension id " + artifact.extensionId() + " already defined by " + previous));
            }
            if (artifact.extensionDefinition() != null) {
                definitionModelsById.putIfAbsent(artifact.extensionId(), artifact.extensionDefinition());
            }
        }
        for (ValidatedRuntimeConditionsArtifact artifact : artifacts) {
            for (String dependency : artifact.dependencies()) {
                if (!definitionsById.containsKey(dependency)) {
                    artifact.addDiagnostic(error(
                            "extension-dependency",
                            artifact.extensionDefinitionUri(),
                            "dependency " + dependency + " cannot be resolved from discovered Runtime Conditions artifacts"));
                }
            }
        }
        for (ValidatedRuntimeConditionsArtifact artifact : artifacts) {
            validateArtifactBindings(artifact, definitionModelsById);
        }
    }

    private void validateArtifactBindings(
            ValidatedRuntimeConditionsArtifact artifact,
            Map<String, ExtensionDefinitionModel> definitionsById) {
        JavaManifestModel manifest = artifact.javaManifest();
        if (manifest == null || artifact.extensionId() == null) {
            return;
        }
        ExtensionVocabulary vocabulary = new ExtensionVocabulary(resolveDefinitions(
                artifact.extensionId(),
                definitionsById,
                new ArrayList<>()));
        validateResolvedConflicts(artifact, vocabulary);
        validateManifestVocabulary(artifact, manifest, vocabulary);
        validateJavaSource(artifact, manifest);
    }

    private List<ExtensionDefinitionModel> resolveDefinitions(
            String extensionId,
            Map<String, ExtensionDefinitionModel> definitionsById,
            List<String> seen) {
        if (seen.contains(extensionId)) {
            return List.of();
        }
        seen.add(extensionId);
        ExtensionDefinitionModel definition = definitionsById.get(extensionId);
        if (definition == null) {
            return List.of();
        }
        List<ExtensionDefinitionModel> definitions = new ArrayList<>();
        for (String dependency : definition.dependencies()) {
            definitions.addAll(resolveDefinitions(dependency, definitionsById, seen));
        }
        definitions.add(definition);
        return definitions;
    }

    private void validateResolvedConflicts(
            ValidatedRuntimeConditionsArtifact artifact,
            ExtensionVocabulary vocabulary) {
        for (Map.Entry<String, Integer> entry : vocabulary.counts().entrySet()) {
            if (entry.getValue() > 1) {
                artifact.addDiagnostic(error(
                        "extension-vocabulary",
                        artifact.extensionDefinitionUri(),
                        "resolved extension set contains vocabulary conflict for " + entry.getKey()));
            }
        }
        for (String conflict : vocabulary.conditionFieldConflicts()) {
            artifact.addDiagnostic(error(
                    "extension-vocabulary",
                    artifact.extensionDefinitionUri(),
                    "resolved extension set contains vocabulary conflict for " + conflict));
        }
    }

    private void validateManifestVocabulary(
            ValidatedRuntimeConditionsArtifact artifact,
            JavaManifestModel manifest,
            ExtensionVocabulary vocabulary) {
        String source = artifact.artifact().manifestUri();
        for (Map.Entry<String, String> constant : manifest.constants().entrySet()) {
            if (vocabulary.fieldValueValueCount(constant.getValue()) == 0) {
                artifact.addDiagnostic(error(
                        "package-manifest",
                        source,
                        "constant " + constant.getKey() + " value \"" + constant.getValue() + "\" is not defined by resolved field values"));
            }
        }
        for (JavaSymbolMapping declaration : manifest.declarations()) {
            validateDeclarationVocabulary(artifact, vocabulary, declaration);
        }
        for (JavaSymbolMapping option : manifest.options()) {
            validateOptionVocabulary(artifact, vocabulary, option, scopesFromOption(option, vocabulary));
        }
    }

    private void validateDeclarationVocabulary(
            ValidatedRuntimeConditionsArtifact artifact,
            ExtensionVocabulary vocabulary,
            JavaSymbolMapping declaration) {
        String source = artifact.artifact().manifestUri();
        if (artifact.artifact().kind() == RuntimeConditionsArtifact.Kind.BINDING && !isBlank(declaration.memberField()) && !"function".equals(declaration.memberField())) {
            artifact.addDiagnostic(error("package-manifest", source, "Java declaration " + declaration.className() + "." + declaration.memberName() + " must use function, not method"));
        }
        expectExactlyOne(artifact, vocabulary.kindCount(declaration.kind()), "declaration kind " + declaration.kind());
        if (!isBlank(declaration.interfaceType())) {
            expectExactlyOne(artifact, vocabulary.interfaceTypeCount(declaration.kind(), declaration.interfaceType()), "declaration interfaceType " + declaration.kind() + "/" + declaration.interfaceType());
        }
        List<Scope> scopes = List.of(new Scope(declaration.kind(), declaration.interfaceType()));
        for (JavaSymbolMapping option : declaration.options()) {
            validateOptionVocabulary(artifact, vocabulary, option, scopes);
        }
    }

    private void validateOptionVocabulary(
            ValidatedRuntimeConditionsArtifact artifact,
            ExtensionVocabulary vocabulary,
            JavaSymbolMapping option,
            List<Scope> scopes) {
        List<Scope> nestedScopes = scopes;
        switch (nullToEmpty(option.target())) {
            case "interface.spec" -> {
                for (Scope scope : scopes) {
                    expectExactlyOne(artifact, vocabulary.interfaceFieldCount(scope.kind(), scope.interfaceType(), "spec"), "binding option interface.spec for " + scope.kind() + "/" + scope.interfaceType());
                }
            }
            case "interface.operations[]" -> {
                for (Scope scope : scopes) {
                    expectExactlyOne(artifact, vocabulary.interfaceFieldCount(scope.kind(), scope.interfaceType(), "operations"), "binding option interface.operations[] for " + scope.kind() + "/" + scope.interfaceType());
                    expectExactlyOne(artifact, vocabulary.fieldValueCount("interface.operations[].method", scope.kind(), scope.interfaceType(), option.method()), "binding option method " + option.method() + " for " + scope.kind() + "/" + scope.interfaceType());
                }
            }
            case "interface.type" -> {
                List<Scope> updated = new ArrayList<>();
                for (Scope scope : scopes) {
                    expectExactlyOne(artifact, vocabulary.interfaceTypeCount(scope.kind(), option.value()), "binding option interface.type " + scope.kind() + "/" + option.value());
                    if (option.enumArg() != null) {
                        expectExactlyOne(artifact, vocabulary.interfaceFieldCount(scope.kind(), option.value(), "engine"), "binding option engine for " + scope.kind() + "/" + option.value());
                    }
                    updated.add(new Scope(scope.kind(), option.value()));
                }
                nestedScopes = List.copyOf(updated);
            }
            case "configuration.env[]" ->
                    validateConfigurationOption(artifact, vocabulary, scopes, "configuration.env[].property");
            case "configuration.alternatives[]" ->
                    validateConfigurationOption(artifact, vocabulary, scopes, "configuration.alternatives[].env[].property");
            case "requestBodySchema", "responseSchema", "env.sensitive", "env.required" -> {
            }
            case "" -> artifact.addDiagnostic(error(
                    "package-manifest",
                    artifact.artifact().manifestUri(),
                    "binding option " + option.memberName() + " is missing target"));
            default -> artifact.addDiagnostic(error(
                    "package-manifest",
                    artifact.artifact().manifestUri(),
                    "unsupported binding option target " + option.target()));
        }
        for (JavaSymbolMapping nested : option.options()) {
            validateOptionVocabulary(artifact, vocabulary, nested, nestedScopes);
        }
    }

    private void validateConfigurationOption(
            ValidatedRuntimeConditionsArtifact artifact,
            ExtensionVocabulary vocabulary,
            List<Scope> scopes,
            String propertyField) {
        if (scopes.isEmpty()) {
            artifact.addDiagnostic(error(
                    "package-manifest",
                    artifact.artifact().manifestUri(),
                    "configuration binding option requires appliesToKinds/appliesToInterfaceTypes or a declaration scope"));
            return;
        }
        for (Scope scope : scopes) {
            expectExactlyOne(artifact, vocabulary.conditionFieldCount(scope.kind(), scope.interfaceType(), "configuration"), "binding option configuration for " + scope.kind() + "/" + scope.interfaceType());
            expectExactlyOne(artifact, vocabulary.fieldValueDefinitionCount(propertyField, scope.kind(), scope.interfaceType()), "binding option property field " + propertyField + " for " + scope.kind() + "/" + scope.interfaceType());
        }
    }

    private List<Scope> scopesFromOption(JavaSymbolMapping option, ExtensionVocabulary vocabulary) {
        if (option.appliesToKinds().isEmpty()) {
            return List.of();
        }
        List<Scope> scopes = new ArrayList<>();
        if (option.appliesToInterfaceTypes().isEmpty()) {
            for (String kind : option.appliesToKinds()) {
                scopes.add(new Scope(kind, ""));
            }
            return List.copyOf(scopes);
        }
        for (String kind : option.appliesToKinds()) {
            for (String interfaceType : option.appliesToInterfaceTypes()) {
                if (vocabulary.interfaceTypeCount(kind, interfaceType) == 1) {
                    scopes.add(new Scope(kind, interfaceType));
                }
            }
        }
        return List.copyOf(scopes);
    }

    private void expectExactlyOne(ValidatedRuntimeConditionsArtifact artifact, int count, String message) {
        if (count != 1) {
            artifact.addDiagnostic(error(
                    "package-manifest",
                    artifact.artifact().manifestUri(),
                    message + ": expected exactly one definition, got " + count));
        }
    }

    private void validateJavaSource(ValidatedRuntimeConditionsArtifact artifact, JavaManifestModel manifest) {
        Path sourceRoot = sourceRootForArtifact(artifact.artifact());
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return;
        }
        Map<String, JavaClassSource> classes = new HashMap<>();
        for (Map.Entry<String, String> constant : manifest.constants().entrySet()) {
            String[] parts = constant.getKey().split("\\.");
            if (parts.length < 2) {
                artifact.addDiagnostic(error(
                        "package-source",
                        artifact.artifact().manifestUri(),
                        "Java constant " + constant.getKey() + " must include class and constant name"));
                continue;
            }
            String className = parts[0];
            String constantName = parts[parts.length - 1];
            JavaClassSource source = readJavaClass(artifact, sourceRoot, manifest.packageName(), className, classes);
            if (source == null) {
                continue;
            }
            String actual = source.constants().get(constantName);
            if (actual == null) {
                artifact.addDiagnostic(error(
                        "package-source",
                        sourceRoot.toString(),
                        "binding constant " + constant.getKey() + " is not declared in Java class " + className));
            } else if (!actual.equals(constant.getValue())) {
                artifact.addDiagnostic(error(
                        "package-source",
                        sourceRoot.toString(),
                        "binding constant " + constant.getKey() + " value \"" + constant.getValue() + "\" does not match Java value \"" + actual + "\""));
            }
        }
        for (JavaSymbolMapping declaration : manifest.declarations()) {
            validateJavaMappingSource(artifact, sourceRoot, manifest.packageName(), declaration, true, classes);
        }
        for (JavaSymbolMapping option : manifest.options()) {
            validateJavaMappingSource(artifact, sourceRoot, manifest.packageName(), option, false, classes);
        }
    }

    private void validateJavaMappingSource(
            ValidatedRuntimeConditionsArtifact artifact,
            Path sourceRoot,
            String packageName,
            JavaSymbolMapping mapping,
            boolean declaration,
            Map<String, JavaClassSource> classes) {
        if (isBlank(mapping.className()) || isBlank(mapping.memberName())) {
            return;
        }
        JavaClassSource source = readJavaClass(artifact, sourceRoot, packageName, mapping.className(), classes);
        if (source == null) {
            return;
        }
        List<JavaMethodSource> methods = source.methods().getOrDefault(mapping.memberName(), List.of());
        if (methods.isEmpty()) {
            String kind = declaration ? "declaration" : "option";
            artifact.addDiagnostic(error(
                    "package-source",
                    sourceRoot.toString(),
                    "binding " + kind + " function " + mapping.className() + "." + mapping.memberName() + " is not declared in Java package"));
        } else {
            validateJavaFunctionIndexes(artifact, methods.get(0), mapping);
        }
        for (JavaSymbolMapping option : mapping.options()) {
            validateJavaMappingSource(artifact, sourceRoot, packageName, option, false, classes);
        }
    }

    private JavaClassSource readJavaClass(
            ValidatedRuntimeConditionsArtifact artifact,
            Path sourceRoot,
            String packageName,
            String className,
            Map<String, JavaClassSource> classes) {
        if (classes.containsKey(className)) {
            return classes.get(className);
        }
        if (className.contains(".") || className.contains("/") || className.contains("$")) {
            artifact.addDiagnostic(error(
                    "package-source",
                    sourceRoot.toString(),
                    "Java binding class " + className + " must be a top-level class name"));
            classes.put(className, null);
            return null;
        }
        Path classPath = sourceRoot;
        if (!isBlank(packageName)) {
            classPath = classPath.resolve(packageName.replace('.', java.io.File.separatorChar));
        }
        classPath = classPath.resolve(className + ".java");
        try {
            String source = Files.readString(classPath);
            JavaClassSource parsed = parseJavaClassSource(className, source);
            if (!isBlank(packageName) && !packageName.equals(parsed.packageName())) {
                artifact.addDiagnostic(error(
                        "package-source",
                        classPath.toString(),
                        "Java package " + parsed.packageName() + " does not match binding package " + packageName));
            }
            classes.put(className, parsed);
            return parsed;
        } catch (IOException e) {
            artifact.addDiagnostic(error(
                    "package-source",
                    sourceRoot.toString(),
                    "failed to read Java class " + className + ": " + e.getMessage()));
            classes.put(className, null);
            return null;
        }
    }

    private JavaClassSource parseJavaClassSource(String className, String source) {
        String packageName = "";
        Matcher packageMatcher = JAVA_PACKAGE.matcher(source);
        if (packageMatcher.find()) {
            packageName = packageMatcher.group(1);
        }
        Map<String, List<JavaMethodSource>> methods = new LinkedHashMap<>();
        Matcher methodMatcher = JAVA_METHOD.matcher(source);
        while (methodMatcher.find()) {
            String methodName = methodMatcher.group(1);
            methods.computeIfAbsent(methodName, ignored -> new ArrayList<>())
                    .add(new JavaMethodSource(methodName, parseJavaParams(methodMatcher.group(2))));
        }
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher enumMatcher = JAVA_ENUM_VALUE.matcher(source);
        while (enumMatcher.find()) {
            constants.put(enumMatcher.group(1), enumMatcher.group(2));
        }
        Matcher stringMatcher = JAVA_STRING_CONSTANT.matcher(source);
        while (stringMatcher.find()) {
            constants.put(stringMatcher.group(1), stringMatcher.group(2));
        }
        return new JavaClassSource(className, packageName, methods, constants);
    }

    private List<JavaParamSource> parseJavaParams(String params) {
        params = params.trim();
        if (params.isEmpty()) {
            return List.of();
        }
        List<JavaParamSource> result = new ArrayList<>();
        for (String part : splitJavaParams(params)) {
            part = part.trim();
            if (part.startsWith("final ")) {
                part = part.substring("final ".length()).trim();
            }
            int space = part.lastIndexOf(' ');
            if (space < 0) {
                continue;
            }
            String type = part.substring(0, space).trim().replace("...", "");
            String name = part.substring(space + 1).trim();
            result.add(new JavaParamSource(name, type));
        }
        return List.copyOf(result);
    }

    private List<String> splitJavaParams(String params) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        for (int i = 0; i < params.length(); i++) {
            char ch = params.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>' && depth > 0) {
                depth--;
            } else if (ch == ',' && depth == 0) {
                parts.add(params.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(params.substring(start));
        return parts;
    }

    private void validateJavaFunctionIndexes(
            ValidatedRuntimeConditionsArtifact artifact,
            JavaMethodSource method,
            JavaSymbolMapping mapping) {
        if (mapping.nameArg() != null) {
            validateJavaParamIndex(artifact, method, mapping.nameArg(), "nameArg", "String");
        }
        for (Map.Entry<String, Integer> entry : mapping.stringArgs().entrySet()) {
            validateJavaParamIndex(artifact, method, entry.getValue(), "stringArgs." + entry.getKey(), "String");
        }
        if (mapping.enumArg() != null) {
            validateJavaParamIndex(artifact, method, mapping.enumArg(), "enumArg", "");
        }
        if (mapping.classArg() != null) {
            validateJavaParamIndex(artifact, method, mapping.classArg(), "classArg", "Class");
        }
    }

    private void validateJavaParamIndex(
            ValidatedRuntimeConditionsArtifact artifact,
            JavaMethodSource method,
            int index,
            String field,
            String expectedType) {
        if (index < 0 || index >= method.params().size()) {
            artifact.addDiagnostic(error(
                    "package-source",
                    artifact.artifact().manifestUri(),
                    "binding " + field + " for Java function " + method.name() + " index " + index + " is out of range"));
            return;
        }
        if (expectedType.isEmpty()) {
            return;
        }
        JavaParamSource param = method.params().get(index);
        if (!javaTypeMatches(param.type(), expectedType)) {
            artifact.addDiagnostic(error(
                    "package-source",
                    artifact.artifact().manifestUri(),
                    "binding " + field + " for Java function " + method.name() + " points at non-" + expectedType + " parameter " + param.name() + " " + param.type()));
        }
    }

    private boolean javaTypeMatches(String actual, String expected) {
        actual = actual.trim();
        return switch (expected) {
            case "String" -> actual.equals("String") || actual.equals("java.lang.String");
            case "Class" -> actual.equals("Class") || actual.startsWith("Class<")
                    || actual.equals("java.lang.Class") || actual.startsWith("java.lang.Class<");
            default -> actual.equals(expected);
        };
    }

    private Path sourceRootForArtifact(RuntimeConditionsArtifact artifact) {
        Path sourcePath = artifact.sourcePath();
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            return null;
        }
        Path manifestDir = sourcePath.getParent();
        Path packageRoot = manifestDir.resolve("src").resolve("main").resolve("java");
        if (Files.isDirectory(packageRoot)) {
            return packageRoot;
        }
        if (manifestDir.endsWith(Path.of("src", "main", "resources", "META-INF", "runtimeconditions"))) {
            Path main = manifestDir.getParent().getParent().getParent();
            Path sourceRoot = main.resolve("java");
            if (Files.isDirectory(sourceRoot)) {
                return sourceRoot;
            }
        }
        return null;
    }

    private String resolveExtensionDefinition(
            RuntimeConditionsArtifact artifact,
            String overridePath,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        if (overridePath != null && !overridePath.isBlank()) {
            try {
                return resolveRelative(artifact.manifestUri(), overridePath);
            } catch (IllegalArgumentException e) {
                diagnostics.add(error("extension-definition", artifact.manifestUri(), e.getMessage()));
                return null;
            }
        }
        if (artifact.extensionUri() == null) {
            diagnostics.add(error(
                    "extension-definition",
                    artifact.manifestUri(),
                    ArtifactDiscovery.EXTENSION_DEFINITION + " is required next to the manifest"));
            return null;
        }
        return artifact.extensionUri();
    }

    private String resolveRelative(String manifestUri, String relativePath) {
        if (manifestUri.startsWith("jar:")) {
            int separator = manifestUri.indexOf("!/");
            if (separator < 0) {
                throw new IllegalArgumentException("invalid JAR manifest URI: " + manifestUri);
            }
            String jarRoot = manifestUri.substring(0, separator + 2);
            String entry = manifestUri.substring(separator + 2);
            Path resolved = Path.of(entry).getParent().resolve(relativePath).normalize();
            if (resolved.startsWith("..")) {
                throw new IllegalArgumentException("extension definition override escapes the JAR artifact: " + relativePath);
            }
            return jarRoot + resolved.toString().replace('\\', '/');
        }
        URI uri = URI.create(manifestUri);
        if (!"file".equals(uri.getScheme())) {
            throw new IllegalArgumentException("unsupported manifest URI scheme for extension definition override: " + uri.getScheme());
        }
        return Path.of(uri).getParent().resolve(relativePath).normalize().toUri().toString();
    }

    private String readResource(String uriString) throws IOException {
        URI uri = URI.create(uriString);
        if ("file".equals(uri.getScheme())) {
            return java.nio.file.Files.readString(Path.of(uri));
        }
        if ("jar".equals(uri.getScheme())) {
            URL url = uri.toURL();
            try (InputStream input = url.openStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("unsupported artifact URI scheme: " + uri.getScheme());
    }

    private String requireScalar(
            YamlDocument document,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics,
            String... path) {
        String value = document.scalar(path);
        if (value == null || value.isBlank()) {
            diagnostics.add(error("package-manifest", source, String.join(".", path) + " is required"));
            return null;
        }
        return value;
    }

    private void validateRequiredValue(
            String actual,
            String expected,
            String field,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        if (actual == null || actual.isBlank()) {
            diagnostics.add(error("package-manifest", source, field + " is required"));
        } else if (!expected.equals(actual)) {
            diagnostics.add(error("package-manifest", source, field + " must be " + expected));
        }
    }

    private void validateExtensionId(String id, String source, List<RuntimeConditionsDiagnostic> diagnostics) {
        if (id == null || id.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(id);
            if (!uri.isAbsolute() || uri.getHost() == null || (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))) {
                diagnostics.add(error("extension-definition", source, "extension id must be an absolute HTTP or HTTPS URI"));
            }
        } catch (IllegalArgumentException e) {
            diagnostics.add(error("extension-definition", source, "extension id must be an absolute HTTP or HTTPS URI"));
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private RuntimeConditionsDiagnostic error(String code, String source, String message) {
        return new RuntimeConditionsDiagnostic(RuntimeConditionsDiagnostic.Severity.ERROR, code, source, message);
    }

    private record Scope(String kind, String interfaceType) {
        private Scope {
            interfaceType = interfaceType == null ? "" : interfaceType;
        }
    }

    private record JavaClassSource(
            String name,
            String packageName,
            Map<String, List<JavaMethodSource>> methods,
            Map<String, String> constants) {
        private JavaClassSource {
            methods = Map.copyOf(methods);
            constants = Map.copyOf(constants);
        }
    }

    private record JavaMethodSource(String name, List<JavaParamSource> params) {
        private JavaMethodSource {
            params = List.copyOf(params);
        }
    }

    private record JavaParamSource(String name, String type) {
    }
}
