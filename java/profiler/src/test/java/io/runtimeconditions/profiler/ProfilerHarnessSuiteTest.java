package io.runtimeconditions.profiler;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProfilerHarnessSuiteTest {
    private final Path repoRoot = Path.of("../..").toAbsolutePath().normalize();
    private final Path testdata = Path.of("src/testdata").toAbsolutePath().normalize();

    @Test
    void artifactDiscoveryHarnessPasses() throws Exception {
        ArtifactDiscoveryTest.main(new String[]{testdata.toString()});
    }

    @Test
    void classpathResolverHarnessPasses() throws Exception {
        ClasspathResolverTest.main(new String[0]);
    }

    @Test
    void manifestValidationHarnessPasses() throws Exception {
        ManifestValidationTest.main(new String[]{testdata.toString(), repoRoot.toString()});
    }

    @Test
    void profileGenerationHarnessPasses() throws Exception {
        ProfileGenerationTest.main(new String[]{repoRoot.toString()});
    }
}
