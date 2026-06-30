package io.runtimeconditions.profiler.project;

import java.nio.file.Path;
import java.util.Objects;

public final class RuntimeConditionsArtifact {
    public enum Kind {
        BINDING,
        PACKAGE
    }

    private final Kind kind;
    private final String manifestUri;
    private final String extensionUri;
    private final String origin;
    private final Path sourcePath;

    RuntimeConditionsArtifact(Kind kind, String manifestUri, String extensionUri, String origin, Path sourcePath) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.manifestUri = Objects.requireNonNull(manifestUri, "manifestUri");
        this.extensionUri = extensionUri;
        this.origin = Objects.requireNonNull(origin, "origin");
        this.sourcePath = sourcePath;
    }

    public Kind kind() {
        return kind;
    }

    public String manifestUri() {
        return manifestUri;
    }

    public String extensionUri() {
        return extensionUri;
    }

    public String origin() {
        return origin;
    }

    public Path sourcePath() {
        return sourcePath;
    }
}
