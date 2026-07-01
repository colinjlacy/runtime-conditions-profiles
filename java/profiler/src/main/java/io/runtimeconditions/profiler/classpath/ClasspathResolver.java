package io.runtimeconditions.profiler.classpath;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

interface ClasspathResolver {
    List<Path> resolve(Path projectRoot, List<Path> modules) throws IOException;
}
