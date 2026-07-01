package io.runtimeconditions.profiler.profile;

import io.runtimeconditions.profiler.extension.ValidatedRuntimeConditionsArtifact;
import io.runtimeconditions.profiler.manifest.ManifestModel;
import io.runtimeconditions.profiler.manifest.SymbolMapping;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wraps a validated binding artifact and exposes its Java manifest as
 * call-identity lookups, used to match source method calls to the binding's
 * declarations and options.
 */
final class BindingArtifact {
    private final ValidatedRuntimeConditionsArtifact artifact;

    BindingArtifact(ValidatedRuntimeConditionsArtifact artifact) {
        this.artifact = artifact;
    }

    String extensionId() {
        return artifact.extensionId();
    }

    ManifestModel manifest() {
        return artifact.javaManifest();
    }

    List<SymbolMapping> allMappings() {
        List<SymbolMapping> mappings = new ArrayList<>();
        mappings.addAll(manifest().declarations());
        mappings.addAll(manifest().options());
        for (SymbolMapping declaration : manifest().declarations()) {
            collectMappings(declaration.options(), mappings);
        }
        for (SymbolMapping option : manifest().options()) {
            collectMappings(option.options(), mappings);
        }
        return mappings;
    }

    private void collectMappings(List<SymbolMapping> source, List<SymbolMapping> target) {
        for (SymbolMapping item : source) {
            target.add(item);
            collectMappings(item.options(), target);
        }
    }

    SymbolMapping findDeclaration(String className, String memberName) {
        for (SymbolMapping declaration : manifest().declarations()) {
            if (new CallIdentity(className, memberName).matches(declaration)) {
                return declaration;
            }
        }
        return null;
    }

    SymbolMapping findApplicableRootOption(String className, String memberName, Map<String, Object> condition) {
        for (SymbolMapping option : manifest().options()) {
            if (!new CallIdentity(className, memberName).matches(option)) {
                continue;
            }
            if (appliesToCondition(option, condition)) {
                return option;
            }
        }
        return null;
    }

    private boolean appliesToCondition(SymbolMapping option, Map<String, Object> condition) {
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
