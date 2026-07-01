package io.runtimeconditions.profiler.command;

public record CommandResult(int exitCode, String stdout, String stderr) {
}
