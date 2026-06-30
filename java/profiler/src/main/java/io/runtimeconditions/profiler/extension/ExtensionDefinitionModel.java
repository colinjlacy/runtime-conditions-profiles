package io.runtimeconditions.profiler.extension;

import io.runtimeconditions.profiler.manifest.YamlDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ExtensionDefinitionModel {
    private final String id;
    private final String source;
    private final List<String> dependencies;
    private final List<ExtensionKind> kinds;
    private final List<ExtensionInterfaceType> interfaceTypes;
    private final List<ExtensionConditionField> conditionFields;
    private final List<ExtensionInterfaceField> interfaceFields;
    private final List<ExtensionFieldValue> fieldValues;

    private ExtensionDefinitionModel(
            String id,
            String source,
            List<String> dependencies,
            List<ExtensionKind> kinds,
            List<ExtensionInterfaceType> interfaceTypes,
            List<ExtensionConditionField> conditionFields,
            List<ExtensionInterfaceField> interfaceFields,
            List<ExtensionFieldValue> fieldValues) {
        this.id = id;
        this.source = source;
        this.dependencies = List.copyOf(dependencies);
        this.kinds = List.copyOf(kinds);
        this.interfaceTypes = List.copyOf(interfaceTypes);
        this.conditionFields = List.copyOf(conditionFields);
        this.interfaceFields = List.copyOf(interfaceFields);
        this.fieldValues = List.copyOf(fieldValues);
    }

    public static ExtensionDefinitionModel parse(
            YamlDocument document,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        return new ExtensionDefinitionModel(
                document.scalar("metadata", "id"),
                source,
                document.stringList("spec", "dependencies"),
                parseKinds(document.value("spec", "kinds"), source, diagnostics),
                parseInterfaceTypes(document.value("spec", "interfaceTypes"), source, diagnostics),
                parseConditionFields(document.value("spec", "conditionFields"), source, diagnostics),
                parseInterfaceFields(document.value("spec", "interfaceFields"), source, diagnostics),
                parseFieldValues(document.value("spec", "fieldValues"), source, diagnostics));
    }

    public String id() {
        return id;
    }

    public String source() {
        return source;
    }

    public List<String> dependencies() {
        return dependencies;
    }

    public List<ExtensionKind> kinds() {
        return kinds;
    }

    public List<ExtensionInterfaceType> interfaceTypes() {
        return interfaceTypes;
    }

    public List<ExtensionConditionField> conditionFields() {
        return conditionFields;
    }

    public List<ExtensionInterfaceField> interfaceFields() {
        return interfaceFields;
    }

    public List<ExtensionFieldValue> fieldValues() {
        return fieldValues;
    }

    private static List<ExtensionKind> parseKinds(
            Object value,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        List<?> input = optionalList(value, "spec.kinds", source, diagnostics);
        List<ExtensionKind> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Map<String, Object> item = map(input.get(i), "spec.kinds[" + i + "]", source, diagnostics);
            if (item != null) {
                result.add(new ExtensionKind(scalar(item.get("name"))));
            }
        }
        return result;
    }

    private static List<ExtensionInterfaceType> parseInterfaceTypes(
            Object value,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        List<?> input = optionalList(value, "spec.interfaceTypes", source, diagnostics);
        List<ExtensionInterfaceType> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Map<String, Object> item = map(input.get(i), "spec.interfaceTypes[" + i + "]", source, diagnostics);
            if (item != null) {
                result.add(new ExtensionInterfaceType(
                        scalar(item.get("name")),
                        scalar(item.get("targetKind"))));
            }
        }
        return result;
    }

    private static List<ExtensionConditionField> parseConditionFields(
            Object value,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        List<?> input = optionalList(value, "spec.conditionFields", source, diagnostics);
        List<ExtensionConditionField> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Map<String, Object> item = map(input.get(i), "spec.conditionFields[" + i + "]", source, diagnostics);
            if (item != null) {
                result.add(new ExtensionConditionField(
                        scalar(item.get("name")),
                        stringList(item.get("appliesToKinds"), "spec.conditionFields[" + i + "].appliesToKinds", source, diagnostics),
                        stringList(item.get("appliesToInterfaceTypes"), "spec.conditionFields[" + i + "].appliesToInterfaceTypes", source, diagnostics)));
            }
        }
        return result;
    }

    private static List<ExtensionInterfaceField> parseInterfaceFields(
            Object value,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        List<?> input = optionalList(value, "spec.interfaceFields", source, diagnostics);
        List<ExtensionInterfaceField> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Map<String, Object> item = map(input.get(i), "spec.interfaceFields[" + i + "]", source, diagnostics);
            if (item != null) {
                result.add(new ExtensionInterfaceField(
                        scalar(item.get("name")),
                        scalar(item.get("targetKind")),
                        scalar(item.get("targetType"))));
            }
        }
        return result;
    }

    private static List<ExtensionFieldValue> parseFieldValues(
            Object value,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        List<?> input = optionalList(value, "spec.fieldValues", source, diagnostics);
        List<ExtensionFieldValue> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Map<String, Object> item = map(input.get(i), "spec.fieldValues[" + i + "]", source, diagnostics);
            if (item != null) {
                result.add(new ExtensionFieldValue(
                        scalar(item.get("field")),
                        scalar(item.get("targetKind")),
                        scalar(item.get("targetType")),
                        stringList(item.get("values"), "spec.fieldValues[" + i + "].values", source, diagnostics)));
            }
        }
        return result;
    }

    private static List<?> optionalList(
            Object value,
            String path,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        if (value == null) {
            return List.of();
        }
        List<?> list = YamlDocument.asList(value);
        if (list == null) {
            diagnostics.add(error(source, path + " must be a sequence"));
            return List.of();
        }
        return list;
    }

    private static List<String> stringList(
            Object value,
            String path,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        if (value == null) {
            return List.of();
        }
        List<?> input = YamlDocument.asList(value);
        if (input == null) {
            diagnostics.add(error(source, path + " must be a sequence"));
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            String parsed = scalar(input.get(i));
            if (parsed == null) {
                diagnostics.add(error(source, path + "[" + i + "] must be scalar"));
            } else {
                result.add(parsed);
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> map(
            Object value,
            String path,
            String source,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        Map<String, Object> result = YamlDocument.asMap(value);
        if (result == null) {
            diagnostics.add(error(source, path + " must be a mapping"));
        }
        return result;
    }

    private static String scalar(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    private static RuntimeConditionsDiagnostic error(String source, String message) {
        return new RuntimeConditionsDiagnostic(
                RuntimeConditionsDiagnostic.Severity.ERROR,
                "extension-definition",
                source,
                message);
    }

    record ExtensionKind(String name) {
    }

    record ExtensionInterfaceType(String name, String targetKind) {
    }

    record ExtensionConditionField(
            String name,
            List<String> appliesToKinds,
            List<String> appliesToInterfaceTypes) {
        ExtensionConditionField {
            appliesToKinds = List.copyOf(appliesToKinds);
            appliesToInterfaceTypes = List.copyOf(appliesToInterfaceTypes);
        }
    }

    record ExtensionInterfaceField(String name, String targetKind, String targetType) {
    }

    record ExtensionFieldValue(String field, String targetKind, String targetType, List<String> values) {
        ExtensionFieldValue {
            values = List.copyOf(values);
        }
    }
}
