package io.runtimeconditions.profiler.extension;

import io.runtimeconditions.profiler.manifest.ManifestModel;
import io.runtimeconditions.profiler.project.RuntimeConditionsArtifact;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ValidatedRuntimeConditionsArtifact {
    private final RuntimeConditionsArtifact artifact;
    private final String manifestExtensionId;
    private final String extensionId;
    private final String extensionDefinitionUri;
    private final ExtensionDefinitionModel extensionDefinition;
    private final ManifestModel javaManifest;
    private final List<String> dependencies;
    private final List<RuntimeConditionsDiagnostic> diagnostics;

    ValidatedRuntimeConditionsArtifact(
            RuntimeConditionsArtifact artifact,
            String manifestExtensionId,
            String extensionId,
            String extensionDefinitionUri,
            ExtensionDefinitionModel extensionDefinition,
            ManifestModel javaManifest,
            List<String> dependencies,
            List<RuntimeConditionsDiagnostic> diagnostics) {
        this.artifact = Objects.requireNonNull(artifact, "artifact");
        this.manifestExtensionId = manifestExtensionId;
        this.extensionId = extensionId;
        this.extensionDefinitionUri = extensionDefinitionUri;
        this.extensionDefinition = extensionDefinition;
        this.javaManifest = javaManifest;
        this.dependencies = List.copyOf(dependencies);
        this.diagnostics = new ArrayList<>(diagnostics);
    }

    public RuntimeConditionsArtifact artifact() {
        return artifact;
    }

    public String manifestExtensionId() {
        return manifestExtensionId;
    }

    public String extensionId() {
        return extensionId;
    }

    public String extensionDefinitionUri() {
        return extensionDefinitionUri;
    }

    public ExtensionDefinitionModel extensionDefinition() {
        return extensionDefinition;
    }

    public ManifestModel javaManifest() {
        return javaManifest;
    }

    public List<String> dependencies() {
        return dependencies;
    }

    public List<RuntimeConditionsDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    void addDiagnostic(RuntimeConditionsDiagnostic diagnostic) {
        diagnostics.add(Objects.requireNonNull(diagnostic, "diagnostic"));
    }
}
