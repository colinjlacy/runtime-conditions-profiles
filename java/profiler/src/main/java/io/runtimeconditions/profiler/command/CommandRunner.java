package io.runtimeconditions.profiler.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction over external process execution so build-tool classpath
 * resolvers can be unit-tested with a fake runner instead of spawning
 * Maven or Gradle.
 */
public interface CommandRunner {
    CommandResult run(List<String> command, Path workingDirectory) throws IOException;
}
