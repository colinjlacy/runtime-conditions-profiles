package io.runtimeconditions.profiler.project;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

public final class ArtifactDiscovery {
    public static final String RESOURCE_ROOT = "META-INF/runtimeconditions";
    static final String BINDINGS_MANIFEST = "runtimeconditions.bindings.yaml";
    static final String PACKAGE_MANIFEST = "runtimeconditions.package.yaml";
    public static final String EXTENSION_DEFINITION = "runtimeconditions.extension.yaml";

    public List<RuntimeConditionsArtifact> discoverProjectArtifacts(Path projectRoot, BuildTool buildTool) throws IOException {
        return discoverProjectArtifacts(projectRoot, buildTool, true);
    }

    public List<RuntimeConditionsArtifact> discoverProjectArtifacts(Path projectRoot, BuildTool buildTool, boolean includeSourceLayout) throws IOException {
        List<Path> candidates = new ArrayList<>();
        if (includeSourceLayout) {
            candidates.add(projectRoot.resolve("src/main/resources").resolve(RESOURCE_ROOT));
        }
        candidates.add(projectRoot);

        List<RuntimeConditionsArtifact> artifacts = new ArrayList<>();
        for (Path candidate : candidates) {
            artifacts.addAll(discoverDirectory(candidate, "project:" + projectRoot));
        }
        return artifacts;
    }

    public List<RuntimeConditionsArtifact> discoverClasspathArtifact(Path entry) throws IOException {
        if (Files.isDirectory(entry)) {
            List<RuntimeConditionsArtifact> artifacts = new ArrayList<>();
            artifacts.addAll(discoverDirectory(entry.resolve(RESOURCE_ROOT), "classpath:" + entry));
            artifacts.addAll(discoverDirectory(entry, "classpath:" + entry));
            return artifacts;
        }
        if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
            return discoverJar(entry);
        }
        return List.of();
    }

    public List<RuntimeConditionsArtifact> discoverArtifactsUnder(Path root) throws IOException {
        Path resolvedRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(resolvedRoot)) {
            return List.of();
        }
        Set<Path> manifestDirs = new LinkedHashSet<>();
        try (var stream = Files.walk(resolvedRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(BINDINGS_MANIFEST)
                            || path.getFileName().toString().equals(PACKAGE_MANIFEST))
                    .filter(path -> !containsPathSegment(path, "target"))
                    .filter(path -> !containsPathSegment(path, "build"))
                    .filter(path -> !containsPathSegment(path, ".gradle"))
                    .sorted()
                    .forEach(path -> manifestDirs.add(path.getParent()));
        }
        List<RuntimeConditionsArtifact> artifacts = new ArrayList<>();
        for (Path dir : manifestDirs) {
            artifacts.addAll(discoverDirectory(dir, "root:" + resolvedRoot));
        }
        return artifacts;
    }

    private List<RuntimeConditionsArtifact> discoverDirectory(Path directory, String origin) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        Path extension = directory.resolve(EXTENSION_DEFINITION);
        List<RuntimeConditionsArtifact> artifacts = new ArrayList<>();
        Path bindings = directory.resolve(BINDINGS_MANIFEST);
        if (Files.isRegularFile(bindings)) {
            artifacts.add(new RuntimeConditionsArtifact(
                    RuntimeConditionsArtifact.Kind.BINDING,
                    bindings.toUri().toString(),
                    Files.isRegularFile(extension) ? extension.toUri().toString() : null,
                    origin,
                    bindings));
        }
        Path manifest = directory.resolve(PACKAGE_MANIFEST);
        if (Files.isRegularFile(manifest)) {
            artifacts.add(new RuntimeConditionsArtifact(
                    RuntimeConditionsArtifact.Kind.PACKAGE,
                    manifest.toUri().toString(),
                    Files.isRegularFile(extension) ? extension.toUri().toString() : null,
                    origin,
                    manifest));
        }
        return artifacts;
    }

    private List<RuntimeConditionsArtifact> discoverJar(Path jar) throws IOException {
        List<RuntimeConditionsArtifact> artifacts = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            String extension = RESOURCE_ROOT + "/" + EXTENSION_DEFINITION;
            String bindings = RESOURCE_ROOT + "/" + BINDINGS_MANIFEST;
            String manifest = RESOURCE_ROOT + "/" + PACKAGE_MANIFEST;
            boolean hasExtension = jarFile.getEntry(extension) != null;
            if (jarFile.getEntry(bindings) != null) {
                artifacts.add(new RuntimeConditionsArtifact(
                        RuntimeConditionsArtifact.Kind.BINDING,
                        jarUri(jar, bindings),
                        hasExtension ? jarUri(jar, extension) : null,
                        "jar:" + jar,
                        jar));
            }
            if (jarFile.getEntry(manifest) != null) {
                artifacts.add(new RuntimeConditionsArtifact(
                        RuntimeConditionsArtifact.Kind.PACKAGE,
                        jarUri(jar, manifest),
                        hasExtension ? jarUri(jar, extension) : null,
                        "jar:" + jar,
                        jar));
            }
        }
        return artifacts;
    }

    private String jarUri(Path jar, String entry) {
        return URI.create("jar:" + jar.toUri() + "!/" + entry).toString();
    }

    private boolean containsPathSegment(Path path, String segment) {
        for (Path part : path) {
            if (part.toString().equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
