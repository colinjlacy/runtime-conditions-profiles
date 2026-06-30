package io.runtimeconditions.profiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class JavaProfileValidator {
    private static final String API_VERSION = "runtimeconditions.io/v1alpha1";
    private final List<RuntimeConditionsDiagnostic> diagnostics = new ArrayList<>();

    List<RuntimeConditionsDiagnostic> validate(Map<String, Object> profile, DiscoveryResult discovery) {
        diagnostics.clear();
        if (!API_VERSION.equals(scalar(profile.get("apiVersion")))) {
            add("apiVersion must be " + API_VERSION);
        }
        if (!"RuntimeConditionsProfile".equals(scalar(profile.get("kind")))) {
            add("kind must be RuntimeConditionsProfile");
        }

        List<String> declaredExtensions = stringList(profile.get("extensions"), "extensions");
        List<?> conditions = list(profile.get("conditions"), "conditions");
        if (declaredExtensions.isEmpty() && !conditions.isEmpty()) {
            add("extensions must declare the extension dependency closure used by conditions");
        }

        Map<String, ExtensionDefinitionModel> definitions = definitionsById(discovery.validatedArtifacts());
        Set<String> declared = new LinkedHashSet<>();
        for (String id : declaredExtensions) {
            if (!declared.add(id)) {
                add("duplicate extension id " + id);
                continue;
            }
            if (!definitions.containsKey(id)) {
                add("missing extension definition for " + id);
            }
        }

        Set<String> closure = extensionClosure(declaredExtensions, definitions);
        for (String id : closure) {
            if (!declared.contains(id)) {
                add("extensions missing dependency " + id);
            }
        }

        ExtensionVocabulary vocabulary = new ExtensionVocabulary(closure.stream()
                .map(definitions::get)
                .filter(definition -> definition != null)
                .toList());
        checkResolvedConflicts(vocabulary);
        for (int i = 0; i < conditions.size(); i++) {
            Map<?, ?> condition = map(conditions.get(i), "conditions[" + i + "]");
            if (condition != null) {
                validateCondition(i, vocabulary, condition);
            }
        }
        return List.copyOf(diagnostics);
    }

    private Map<String, ExtensionDefinitionModel> definitionsById(List<ValidatedRuntimeConditionsArtifact> artifacts) {
        Map<String, ExtensionDefinitionModel> definitions = new LinkedHashMap<>();
        for (ValidatedRuntimeConditionsArtifact artifact : artifacts) {
            ExtensionDefinitionModel definition = artifact.extensionDefinition();
            if (definition != null && definition.id() != null) {
                definitions.putIfAbsent(definition.id(), definition);
            }
        }
        return definitions;
    }

    private Set<String> extensionClosure(
            List<String> roots,
            Map<String, ExtensionDefinitionModel> definitions) {
        Set<String> resolved = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String root : roots) {
            addExtensionClosure(root, definitions, visiting, resolved);
        }
        return resolved;
    }

    private void addExtensionClosure(
            String id,
            Map<String, ExtensionDefinitionModel> definitions,
            Set<String> visiting,
            Set<String> resolved) {
        if (resolved.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            add("extension dependency cycle includes " + id);
            return;
        }
        ExtensionDefinitionModel definition = definitions.get(id);
        if (definition != null) {
            for (String dependency : definition.dependencies()) {
                addExtensionClosure(dependency, definitions, visiting, resolved);
            }
        }
        visiting.remove(id);
        resolved.add(id);
    }

    private void checkResolvedConflicts(ExtensionVocabulary vocabulary) {
        for (Map.Entry<String, Integer> entry : vocabulary.counts().entrySet()) {
            if (entry.getValue() > 1) {
                add("resolved extension set contains vocabulary conflict for " + entry.getKey());
            }
        }
        for (String conflict : vocabulary.conditionFieldConflicts()) {
            add("resolved extension set contains vocabulary conflict for " + conflict);
        }
    }

    private void validateCondition(int index, ExtensionVocabulary vocabulary, Map<?, ?> condition) {
        String prefix = "conditions[" + index + "]";
        String kind = scalar(condition.get("kind"));
        Map<?, ?> iface = map(condition.get("interface"), prefix + ".interface");
        String interfaceType = iface == null ? "" : scalar(iface.get("type"));

        expectExactlyOne(vocabulary.kindCount(kind), prefix + ".kind " + kind);
        expectExactlyOne(vocabulary.interfaceTypeCount(kind, interfaceType), prefix + ".interface.type " + kind + "/" + interfaceType);
        if (iface == null) {
            return;
        }

        if (iface.get("spec") != null) {
            expectExactlyOne(vocabulary.interfaceFieldCount(kind, interfaceType, "spec"), prefix + ".interface.spec for " + kind + "/" + interfaceType);
            Map<?, ?> spec = map(iface.get("spec"), prefix + ".interface.spec");
            if (spec != null) {
                String format = scalar(spec.get("format"));
                expectExactlyOne(vocabulary.fieldValueCount("interface.spec.format", kind, interfaceType, format), prefix + ".interface.spec.format " + format + " for " + kind + "/" + interfaceType);
            }
        }

        List<?> operations = optionalList(iface.get("operations"), prefix + ".interface.operations");
        if (!operations.isEmpty()) {
            expectExactlyOne(vocabulary.interfaceFieldCount(kind, interfaceType, "operations"), prefix + ".interface.operations for " + kind + "/" + interfaceType);
            for (int i = 0; i < operations.size(); i++) {
                Map<?, ?> operation = map(operations.get(i), prefix + ".interface.operations[" + i + "]");
                if (operation == null) {
                    continue;
                }
                String method = scalar(operation.get("method"));
                expectExactlyOne(vocabulary.fieldValueCount("interface.operations[].method", kind, interfaceType, method), prefix + ".interface.operations[" + i + "].method " + method + " for " + kind + "/" + interfaceType);
            }
        }

        List<?> subjects = optionalList(iface.get("subjects"), prefix + ".interface.subjects");
        if (!subjects.isEmpty()) {
            expectExactlyOne(vocabulary.interfaceFieldCount(kind, interfaceType, "subjects"), prefix + ".interface.subjects for " + kind + "/" + interfaceType);
        }

        String engine = scalar(iface.get("engine"));
        if (!engine.isBlank()) {
            expectExactlyOne(vocabulary.interfaceFieldCount(kind, interfaceType, "engine"), prefix + ".interface.engine for " + kind + "/" + interfaceType);
            expectExactlyOne(vocabulary.fieldValueCount("interface.engine", kind, interfaceType, engine), prefix + ".interface.engine " + engine + " for " + kind + "/" + interfaceType);
        }

        String bucketClass = scalar(iface.get("bucketClass"));
        if (!bucketClass.isBlank()) {
            expectExactlyOne(vocabulary.interfaceFieldCount(kind, interfaceType, "bucketClass"), prefix + ".interface.bucketClass for " + kind + "/" + interfaceType);
            expectExactlyOne(vocabulary.fieldValueCount("interface.bucketClass", kind, interfaceType, bucketClass), prefix + ".interface.bucketClass " + bucketClass + " for " + kind + "/" + interfaceType);
        }

        if (condition.get("configuration") != null) {
            expectExactlyOne(vocabulary.conditionFieldCount(kind, interfaceType, "configuration"), prefix + ".configuration for " + kind + "/" + interfaceType);
            Map<?, ?> configuration = map(condition.get("configuration"), prefix + ".configuration");
            if (configuration != null) {
                validateConfiguration(prefix, vocabulary, kind, interfaceType, configuration);
            }
        }
    }

    private void validateConfiguration(
            String prefix,
            ExtensionVocabulary vocabulary,
            String kind,
            String interfaceType,
            Map<?, ?> configuration) {
        List<?> env = optionalList(configuration.get("env"), prefix + ".configuration.env");
        for (int i = 0; i < env.size(); i++) {
            Map<?, ?> item = map(env.get(i), prefix + ".configuration.env[" + i + "]");
            if (item != null) {
                String property = scalar(item.get("property"));
                expectExactlyOne(vocabulary.fieldValueCount("configuration.env[].property", kind, interfaceType, property), prefix + ".configuration.env[" + i + "].property " + property + " for " + kind + "/" + interfaceType);
            }
        }

        List<?> alternatives = optionalList(configuration.get("alternatives"), prefix + ".configuration.alternatives");
        for (int i = 0; i < alternatives.size(); i++) {
            Map<?, ?> alternative = map(alternatives.get(i), prefix + ".configuration.alternatives[" + i + "]");
            if (alternative == null) {
                continue;
            }
            List<?> alternativeEnv = optionalList(alternative.get("env"), prefix + ".configuration.alternatives[" + i + "].env");
            for (int j = 0; j < alternativeEnv.size(); j++) {
                Map<?, ?> item = map(alternativeEnv.get(j), prefix + ".configuration.alternatives[" + i + "].env[" + j + "]");
                if (item != null) {
                    String property = scalar(item.get("property"));
                    expectExactlyOne(vocabulary.fieldValueCount("configuration.alternatives[].env[].property", kind, interfaceType, property), prefix + ".configuration.alternatives[" + i + "].env[" + j + "].property " + property + " for " + kind + "/" + interfaceType);
                }
            }
        }
    }

    private void expectExactlyOne(int count, String message) {
        if (count != 1) {
            add(message + ": expected exactly one definition, got " + count);
        }
    }

    private List<?> optionalList(Object value, String path) {
        if (value == null) {
            return List.of();
        }
        return list(value, path);
    }

    private List<?> list(Object value, String path) {
        if (value instanceof List<?> list) {
            return list;
        }
        add(path + " must be a sequence");
        return List.of();
    }

    private List<String> stringList(Object value, String path) {
        List<?> input = optionalList(value, path);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            String parsed = scalar(input.get(i));
            if (parsed.isBlank()) {
                add(path + "[" + i + "] must be scalar");
            } else {
                result.add(parsed);
            }
        }
        return List.copyOf(result);
    }

    private Map<?, ?> map(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        add(path + " must be a mapping");
        return null;
    }

    private String scalar(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "";
    }

    private void add(String message) {
        diagnostics.add(new RuntimeConditionsDiagnostic(
                RuntimeConditionsDiagnostic.Severity.ERROR,
                "profile-validation",
                "profile",
                message));
    }

}
