package io.runtimeconditions.profiler.project;

import java.nio.file.Path;
import java.util.List;

public record DiscoveryOptions(List<Path> classpathEntries, boolean resolveBuildClasspath) {
    public DiscoveryOptions {
        classpathEntries = List.copyOf(classpathEntries);
    }
}
