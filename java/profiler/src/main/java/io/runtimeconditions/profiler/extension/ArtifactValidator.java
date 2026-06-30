package io.runtimeconditions.profiler.extension;

import io.runtimeconditions.profiler.manifest.ManifestModel;
import io.runtimeconditions.profiler.manifest.ManifestParser;
import io.runtimeconditions.profiler.manifest.YamlDocument;
import io.runtimeconditions.profiler.project.ArtifactDiscovery;
import io.runtimeconditions.profiler.project.RuntimeConditionsArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ArtifactValidator {
    private static final String API_VERSION = "runtimeconditions.io/v1alpha1";
    private static final String EXTENSION_KIND = "RuntimeConditionsExtensionDefinition";
    private static final String BINDING_KIND = "RuntimeConditionsBinding";
    private static final String PACKAGE_KIND = "RuntimeConditionsPackage";
    private static final String LANGUAGE = "java";

    public List<ValidatedRuntimeConditionsArtifact> validate(List<RuntimeConditionsArtifact> artifacts) {
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
        ManifestModel javaManifest = null;
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
                javaManifest = new ManifestParser().parse(manifest, artifact.manifestUri(), diagnostics);
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
            new ManifestVocabularyValidator().validate(artifact, definitionModelsById);
        }
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

    private RuntimeConditionsDiagnostic error(String code, String source, String message) {
        return new RuntimeConditionsDiagnostic(RuntimeConditionsDiagnostic.Severity.ERROR, code, source, message);
    }
}
