package io.runtimeconditions.profiler.project;

import io.runtimeconditions.profiler.extension.RuntimeConditionsDiagnostic;
import io.runtimeconditions.profiler.extension.ValidatedRuntimeConditionsArtifact;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DiscoveryResult {
    private final Path projectRoot;
    private final BuildTool buildTool;
    private final List<Path> modules;
    private final List<Path> classpathEntries;
    private final List<RuntimeConditionsArtifact> artifacts;
    private final List<ValidatedRuntimeConditionsArtifact> validatedArtifacts;

    public DiscoveryResult(
            Path projectRoot,
            BuildTool buildTool,
            List<Path> modules,
            List<Path> classpathEntries,
            List<RuntimeConditionsArtifact> artifacts,
            List<ValidatedRuntimeConditionsArtifact> validatedArtifacts) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.buildTool = Objects.requireNonNull(buildTool, "buildTool");
        this.modules = List.copyOf(modules);
        this.classpathEntries = List.copyOf(classpathEntries);
        this.artifacts = List.copyOf(artifacts);
        this.validatedArtifacts = List.copyOf(validatedArtifacts);
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public BuildTool buildTool() {
        return buildTool;
    }

    public List<Path> modules() {
        return modules;
    }

    public List<Path> classpathEntries() {
        return classpathEntries;
    }

    public List<RuntimeConditionsArtifact> artifacts() {
        return artifacts;
    }

    public List<ValidatedRuntimeConditionsArtifact> validatedArtifacts() {
        return validatedArtifacts;
    }

    public List<RuntimeConditionsDiagnostic> diagnostics() {
        List<RuntimeConditionsDiagnostic> diagnostics = new ArrayList<>();
        for (ValidatedRuntimeConditionsArtifact artifact : validatedArtifacts) {
            diagnostics.addAll(artifact.diagnostics());
        }
        return List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.severity() == RuntimeConditionsDiagnostic.Severity.ERROR);
    }
}
