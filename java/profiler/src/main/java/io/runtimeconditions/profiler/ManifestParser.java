package io.runtimeconditions.profiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JavaManifestParser {
    JavaManifestModel parse(YamlDocument document, String source, List<RuntimeConditionsDiagnostic> diagnostics) {
        Map<String, Object> javaSection = YamlDocument.asMap(document.value("java"));
        if (javaSection == null) {
            diagnostics.add(error("package-language", source, "java section must be a mapping"));
            return new JavaManifestModel(null, Map.of(), List.of(), List.of());
        }

        String packageName = scalar(javaSection, "package");
        if (isBlank(packageName)) {
            diagnostics.add(error("package-manifest", source, "java.package is required"));
        }
        if (javaSection.containsKey("class")) {
            diagnostics.add(error("package-manifest", source, "top-level java.class must not be used; Java bindings use per-entry class"));
        }

        Map<String, String> constants = parseConstants(javaSection.get("constants"), source, diagnostics);
        List<JavaSymbolMapping> declarations = parseMappings(
                javaSection.get("declarations"),
                "java.declarations",
                true,
                source,
                diagnostics);
        List<JavaSymbolMapping> options = parseMappings(
                javaSection.get("options"),
                "java.options",
                false,
                source,
                diagnostics);

        if (declarations.isEmpty() && options.isEmpty()) {
            diagnostics.add(error("package-manifest", source, "at least one java.declarations or java.options entry is required"));
        }

        return new JavaManifestModel(packageName, constants, declarations, options);
    }

    private Map<String, String> parseConstants(
            Object value,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> input = YamlDocument.asMap(value);
        if (input == null) {
            diagnostics.add(error("package-manifest", source, "java.constants must be a mapping"));
            return Map.of();
        }
        Map<String, String> constants = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String constant = scalar(entry.getValue());
            if (constant == null) {
                diagnostics.add(error("package-manifest", source, "java.constants." + entry.getKey() + " must be scalar"));
            } else {
                constants.put(entry.getKey(), constant);
            }
        }
        return constants;
    }

    private List<JavaSymbolMapping> parseMappings(
            Object value,
            String path,
            boolean declaration,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        if (value == null) {
            return List.of();
        }
        List<?> input = YamlDocument.asList(value);
        if (input == null) {
            diagnostics.add(error("package-manifest", source, path + " must be a sequence"));
            return List.of();
        }
        List<JavaSymbolMapping> mappings = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Map<String, Object> item = YamlDocument.asMap(input.get(i));
            String itemPath = path + "[" + i + "]";
            if (item == null) {
                diagnostics.add(error("package-manifest", source, itemPath + " must be a mapping"));
                continue;
            }
            mappings.add(parseMapping(item, itemPath, declaration, source, diagnostics));
        }
        return mappings;
    }

    private JavaSymbolMapping parseMapping(
            Map<String, Object> item,
            String path,
            boolean declaration,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        String className = requireScalar(item, "class", path, source, diagnostics);
        String function = scalar(item, "function");
        String methodMember = scalar(item, "method");
        String memberField = function != null ? "function" : "method";
        String memberName = function != null ? function : methodMember;
        if (isBlank(memberName)) {
            diagnostics.add(error("package-manifest", source, path + " must define function or method"));
        }

        String target = scalar(item, "target");
        String kind = scalar(item, "kind");
        if (declaration && isBlank(kind)) {
            diagnostics.add(error("package-manifest", source, path + ".kind is required"));
        }
        if (!declaration && isBlank(target)) {
            diagnostics.add(error("package-manifest", source, path + ".target is required"));
        }

        List<JavaSymbolMapping> options = parseMappings(item.get("options"), path + ".options", false, source, diagnostics);
        return new JavaSymbolMapping(
                className,
                memberName,
                memberField,
                target,
                kind,
                scalar(item, "interfaceType"),
                scalar(item, "value"),
                scalar(item, "method"),
                integer(item, "nameArg", path, source, diagnostics),
                integer(item, "classArg", path, source, diagnostics),
                integer(item, "enumArg", path, source, diagnostics),
                integerMap(item.get("stringArgs"), path + ".stringArgs", source, diagnostics),
                stringList(item.get("appliesToKinds"), path + ".appliesToKinds", source, diagnostics),
                stringList(item.get("appliesToInterfaceTypes"), path + ".appliesToInterfaceTypes", source, diagnostics),
                options);
    }

    private Map<String, Integer> integerMap(
            Object value,
            String path,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> input = YamlDocument.asMap(value);
        if (input == null) {
            diagnostics.add(error("package-manifest", source, path + " must be a mapping"));
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Integer parsed = integer(entry.getValue(), path + "." + entry.getKey(), source, diagnostics);
            if (parsed != null) {
                result.put(entry.getKey(), parsed);
            }
        }
        return result;
    }

    private List<String> stringList(
            Object value,
            String path,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        if (value == null) {
            return List.of();
        }
        List<?> input = YamlDocument.asList(value);
        if (input == null) {
            diagnostics.add(error("package-manifest", source, path + " must be a sequence"));
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            String parsed = scalar(input.get(i));
            if (parsed == null) {
                diagnostics.add(error("package-manifest", source, path + "[" + i + "] must be scalar"));
            } else {
                result.add(parsed);
            }
        }
        return List.copyOf(result);
    }

    private String requireScalar(
            Map<String, Object> input,
            String key,
            String path,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        String value = scalar(input, key);
        if (isBlank(value)) {
            diagnostics.add(error("package-manifest", source, path + "." + key + " is required"));
        }
        return value;
    }

    private Integer integer(
            Map<String, Object> input,
            String key,
            String path,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        return integer(input.get(key), path + "." + key, source, diagnostics);
    }

    private Integer integer(
            Object value,
            String path,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            int parsed = number.intValue();
            if (parsed < 0) {
                diagnostics.add(error("package-manifest", source, path + " must be zero or greater"));
                return null;
            }
            return parsed;
        }
        diagnostics.add(error("package-manifest", source, path + " must be an integer"));
        return null;
    }

    private String scalar(Map<String, Object> input, String key) {
        return scalar(input.get(key));
    }

    private String scalar(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private RuntimeConditionsDiagnostic error(String code, String source, String message) {
        return new RuntimeConditionsDiagnostic(RuntimeConditionsDiagnostic.Severity.ERROR, code, source, message);
    }
}
