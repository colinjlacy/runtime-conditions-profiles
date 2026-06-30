package io.runtimeconditions.profiler.extension;

import io.runtimeconditions.profiler.manifest.ManifestModel;
import io.runtimeconditions.profiler.manifest.SymbolMapping;
import io.runtimeconditions.profiler.project.RuntimeConditionsArtifact;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ManifestVocabularyValidator {
    void validate(
            ValidatedRuntimeConditionsArtifact artifact,
            Map<String, ExtensionDefinitionModel> definitionsById) {
        ManifestModel manifest = artifact.javaManifest();
        if (manifest == null || artifact.extensionId() == null) {
            return;
        }
        ExtensionVocabulary vocabulary = new ExtensionVocabulary(resolveDefinitions(
                artifact.extensionId(),
                definitionsById,
                new ArrayList<>()));
        validateResolvedConflicts(artifact, vocabulary);
        validateManifestVocabulary(artifact, manifest, vocabulary);
        new SourceInspector().validate(artifact, manifest);
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
            ManifestModel manifest,
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
        for (SymbolMapping declaration : manifest.declarations()) {
            validateDeclarationVocabulary(artifact, vocabulary, declaration);
        }
        for (SymbolMapping option : manifest.options()) {
            validateOptionVocabulary(artifact, vocabulary, option, scopesFromOption(option, vocabulary));
        }
    }

    private void validateDeclarationVocabulary(
            ValidatedRuntimeConditionsArtifact artifact,
            ExtensionVocabulary vocabulary,
            SymbolMapping declaration) {
        String source = artifact.artifact().manifestUri();
        if (artifact.artifact().kind() == RuntimeConditionsArtifact.Kind.BINDING && !isBlank(declaration.memberField()) && !"function".equals(declaration.memberField())) {
            artifact.addDiagnostic(error("package-manifest", source, "Java declaration " + declaration.className() + "." + declaration.memberName() + " must use function, not method"));
        }
        expectExactlyOne(artifact, vocabulary.kindCount(declaration.kind()), "declaration kind " + declaration.kind());
        if (!isBlank(declaration.interfaceType())) {
            expectExactlyOne(artifact, vocabulary.interfaceTypeCount(declaration.kind(), declaration.interfaceType()), "declaration interfaceType " + declaration.kind() + "/" + declaration.interfaceType());
        }
        List<Scope> scopes = List.of(new Scope(declaration.kind(), declaration.interfaceType()));
        for (SymbolMapping option : declaration.options()) {
            validateOptionVocabulary(artifact, vocabulary, option, scopes);
        }
    }

    private void validateOptionVocabulary(
            ValidatedRuntimeConditionsArtifact artifact,
            ExtensionVocabulary vocabulary,
            SymbolMapping option,
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
        for (SymbolMapping nested : option.options()) {
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

    private List<Scope> scopesFromOption(SymbolMapping option, ExtensionVocabulary vocabulary) {
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
}
