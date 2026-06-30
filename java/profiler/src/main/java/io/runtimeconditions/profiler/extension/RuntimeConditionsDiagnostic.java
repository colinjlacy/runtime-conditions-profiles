package io.runtimeconditions.profiler.extension;

import java.util.Objects;

public final class RuntimeConditionsDiagnostic {
    public enum Severity {
        ERROR
    }

    private final Severity severity;
    private final String code;
    private final String source;
    private final String message;

    public RuntimeConditionsDiagnostic(Severity severity, String code, String source, String message) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.code = Objects.requireNonNull(code, "code");
        this.source = source == null ? "" : source;
        this.message = Objects.requireNonNull(message, "message");
    }

    public Severity severity() {
        return severity;
    }

    public String code() {
        return code;
    }

    public String source() {
        return source;
    }

    public String message() {
        return message;
    }
}
