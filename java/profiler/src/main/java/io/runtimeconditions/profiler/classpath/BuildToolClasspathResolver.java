package io.runtimeconditions.profiler.classpath;

import io.runtimeconditions.profiler.command.CommandRunner;
import io.runtimeconditions.profiler.command.DefaultCommandRunner;
import io.runtimeconditions.profiler.project.BuildTool;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point that dispatches classpath resolution to the resolver matching
 * the detected build tool. Source-only projects have no build classpath, so
 * they resolve to an empty list.
 */
public final class BuildToolClasspathResolver {
    private final CommandRunner commandRunner;

    public BuildToolClasspathResolver() {
        this(new DefaultCommandRunner());
    }

    BuildToolClasspathResolver(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    public List<Path> resolve(Path projectRoot, BuildTool buildTool, List<Path> modules) throws IOException {
        return switch (buildTool) {
            case MAVEN -> new MavenClasspathResolver(commandRunner).resolve(projectRoot, modules);
            case GRADLE -> new GradleClasspathResolver(commandRunner).resolve(projectRoot, modules);
            case SOURCE_ONLY -> List.of();
        };
    }
}
