package io.runtimeconditions.profiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExtensionVocabulary {
    private final List<ExtensionDefinitionModel> definitions;

    ExtensionVocabulary(List<ExtensionDefinitionModel> definitions) {
        this.definitions = List.copyOf(definitions);
    }

    int kindCount(String name) {
        int count = 0;
        for (ExtensionDefinitionModel definition : definitions) {
            for (ExtensionDefinitionModel.ExtensionKind item : definition.kinds()) {
                if (stringEquals(name, item.name())) {
                    count++;
                }
            }
        }
        return count;
    }

    int interfaceTypeCount(String kind, String name) {
        int count = 0;
        for (ExtensionDefinitionModel definition : definitions) {
            for (ExtensionDefinitionModel.ExtensionInterfaceType item : definition.interfaceTypes()) {
                if (stringEquals(kind, item.targetKind()) && stringEquals(name, item.name())) {
                    count++;
                }
            }
        }
        return count;
    }

    int interfaceFieldCount(String kind, String interfaceType, String name) {
        int count = 0;
        for (ExtensionDefinitionModel definition : definitions) {
            for (ExtensionDefinitionModel.ExtensionInterfaceField item : definition.interfaceFields()) {
                if (stringEquals(kind, item.targetKind())
                        && stringEquals(interfaceType, item.targetType())
                        && stringEquals(name, item.name())) {
                    count++;
                }
            }
        }
        return count;
    }

    int conditionFieldCount(String kind, String interfaceType, String name) {
        int count = 0;
        for (ExtensionDefinitionModel definition : definitions) {
            for (ExtensionDefinitionModel.ExtensionConditionField item : definition.conditionFields()) {
                if (stringEquals(name, item.name()) && conditionFieldApplies(item, kind, interfaceType)) {
                    count++;
                }
            }
        }
        return count;
    }

    int fieldValueCount(String field, String kind, String interfaceType, String value) {
        int count = 0;
        for (ExtensionDefinitionModel definition : definitions) {
            for (ExtensionDefinitionModel.ExtensionFieldValue item : definition.fieldValues()) {
                if (stringEquals(field, item.field())
                        && stringEquals(kind, item.targetKind())
                        && stringEquals(interfaceType, item.targetType())
                        && item.values().contains(value)) {
                    count++;
                }
            }
        }
        return count;
    }

    int fieldValueDefinitionCount(String field, String kind, String interfaceType) {
        int count = 0;
        for (ExtensionDefinitionModel definition : definitions) {
            for (ExtensionDefinitionModel.ExtensionFieldValue item : definition.fieldValues()) {
                if (stringEquals(field, item.field())
                        && stringEquals(kind, item.targetKind())
                        && stringEquals(interfaceType, item.targetType())) {
                    count++;
                }
            }
        }
        return count;
    }

    int fieldValueValueCount(String value) {
        int count = 0;
        for (ExtensionDefinitionModel definition : definitions) {
            for (ExtensionDefinitionModel.ExtensionFieldValue item : definition.fieldValues()) {
                if (item.values().contains(value)) {
                    count++;
                }
            }
        }
        return count;
    }

    Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ExtensionDefinitionModel definition : definitions) {
            for (ExtensionDefinitionModel.ExtensionKind item : definition.kinds()) {
                increment(counts, "kind:" + item.name());
            }
            for (ExtensionDefinitionModel.ExtensionInterfaceType item : definition.interfaceTypes()) {
                increment(counts, "interfaceType:" + item.targetKind() + ":" + item.name());
            }
            for (ExtensionDefinitionModel.ExtensionInterfaceField item : definition.interfaceFields()) {
                increment(counts, "interfaceField:" + item.targetKind() + ":" + item.targetType() + ":" + item.name());
            }
            for (ExtensionDefinitionModel.ExtensionFieldValue item : definition.fieldValues()) {
                increment(counts, "fieldValues:" + item.targetKind() + ":" + item.targetType() + ":" + item.field());
            }
        }
        return counts;
    }

    List<String> conditionFieldConflicts() {
        List<ConditionFieldDefinition> fields = new ArrayList<>();
        for (ExtensionDefinitionModel definition : definitions) {
            for (ExtensionDefinitionModel.ExtensionConditionField field : definition.conditionFields()) {
                fields.add(new ConditionFieldDefinition(definition, field));
            }
        }
        List<String> conflicts = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            for (int j = i + 1; j < fields.size(); j++) {
                ConditionFieldDefinition left = fields.get(i);
                ConditionFieldDefinition right = fields.get(j);
                if (stringEquals(left.field().name(), right.field().name())
                        && conditionFieldScopesOverlap(left.field(), right.field())) {
                    conflicts.add("conditionField:" + left.field().name() + " between "
                            + left.definition().id() + " and " + right.definition().id());
                }
            }
        }
        return conflicts;
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private static boolean conditionFieldApplies(
            ExtensionDefinitionModel.ExtensionConditionField field,
            String kind,
            String interfaceType) {
        return field.appliesToKinds().contains(kind)
                && (field.appliesToInterfaceTypes().isEmpty()
                || field.appliesToInterfaceTypes().contains(interfaceType));
    }

    private static boolean conditionFieldScopesOverlap(
            ExtensionDefinitionModel.ExtensionConditionField left,
            ExtensionDefinitionModel.ExtensionConditionField right) {
        for (String kind : left.appliesToKinds()) {
            if (right.appliesToKinds().contains(kind)
                    && stringSetsOverlapOrEitherEmpty(left.appliesToInterfaceTypes(), right.appliesToInterfaceTypes())) {
                return true;
            }
        }
        return false;
    }

    private static boolean stringSetsOverlapOrEitherEmpty(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return true;
        }
        for (String item : left) {
            if (right.contains(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean stringEquals(String left, String right) {
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private record ConditionFieldDefinition(
            ExtensionDefinitionModel definition,
            ExtensionDefinitionModel.ExtensionConditionField field) {
    }
}
