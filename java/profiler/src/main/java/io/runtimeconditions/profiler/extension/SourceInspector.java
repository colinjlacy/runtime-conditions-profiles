package io.runtimeconditions.profiler.extension;

import io.runtimeconditions.profiler.manifest.ManifestModel;
import io.runtimeconditions.profiler.manifest.SymbolMapping;
import io.runtimeconditions.profiler.project.RuntimeConditionsArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SourceInspector {
    private static final Pattern JAVA_PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;");
    private static final Pattern JAVA_METHOD = Pattern.compile("(?m)\\bpublic\\s+static\\s+(?:<[^>]+>\\s+)?[A-Za-z0-9_.$<>\\[\\]?]+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)");
    private static final Pattern JAVA_ENUM_VALUE = Pattern.compile("(?m)\\b([A-Z][A-Z0-9_]*)\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern JAVA_STRING_CONSTANT = Pattern.compile("(?m)\\b([A-Z][A-Z0-9_]*)\\s*=\\s*\"([^\"]*)\"");

    void validate(ValidatedRuntimeConditionsArtifact artifact, ManifestModel manifest) {
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
        for (SymbolMapping declaration : manifest.declarations()) {
            validateJavaMappingSource(artifact, sourceRoot, manifest.packageName(), declaration, true, classes);
        }
        for (SymbolMapping option : manifest.options()) {
            validateJavaMappingSource(artifact, sourceRoot, manifest.packageName(), option, false, classes);
        }
    }

    private void validateJavaMappingSource(
            ValidatedRuntimeConditionsArtifact artifact,
            Path sourceRoot,
            String packageName,
            SymbolMapping mapping,
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
        for (SymbolMapping option : mapping.options()) {
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
            SymbolMapping mapping) {
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private RuntimeConditionsDiagnostic error(String code, String source, String message) {
        return new RuntimeConditionsDiagnostic(RuntimeConditionsDiagnostic.Severity.ERROR, code, source, message);
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
