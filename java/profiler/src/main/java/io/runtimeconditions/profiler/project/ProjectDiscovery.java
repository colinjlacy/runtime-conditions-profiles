package io.runtimeconditions.profiler.project;

import io.runtimeconditions.profiler.classpath.BuildToolClasspathResolver;
import io.runtimeconditions.profiler.extension.ArtifactValidator;
import io.runtimeconditions.profiler.extension.ValidatedRuntimeConditionsArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public final class ProjectDiscovery {
    private static final Pattern GRADLE_INCLUDE = Pattern.compile("include\\s*(?:\\(|\\s)([^\\n)]*)");
    private static final Pattern GRADLE_PROJECT = Pattern.compile("['\"]:?(.*?)['\"]");

    public DiscoveryResult discover(Path projectRoot, List<Path> classpathEntries) throws IOException {
        return discover(projectRoot, new DiscoveryOptions(classpathEntries, false));
    }

    public DiscoveryResult discover(Path projectRoot, DiscoveryOptions options) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        BuildTool buildTool = detectBuildTool(root);
        List<Path> modules = discoverModules(root, buildTool);
        List<Path> artifactRoots = new ArrayList<>();
        artifactRoots.add(root);
        artifactRoots.addAll(modules);

        Set<Path> resolvedClasspath = new LinkedHashSet<>();
        if (options.resolveBuildClasspath()) {
            resolvedClasspath.addAll(new BuildToolClasspathResolver().resolve(root, buildTool, modules));
        }
        for (Path entry : options.classpathEntries()) {
            resolvedClasspath.add(entry.toAbsolutePath().normalize());
        }

        ArtifactDiscovery artifactDiscovery = new ArtifactDiscovery();
        List<RuntimeConditionsArtifact> artifacts = new ArrayList<>();
        for (Path artifactRoot : artifactRoots) {
            artifacts.addAll(artifactDiscovery.discoverProjectArtifacts(
                    artifactRoot,
                    buildTool,
                    !options.resolveBuildClasspath()));
        }
        for (Path classpathEntry : resolvedClasspath) {
            artifacts.addAll(artifactDiscovery.discoverClasspathArtifact(classpathEntry));
        }
        List<ValidatedRuntimeConditionsArtifact> validatedArtifacts = new ArtifactValidator().validate(artifacts);
        return new DiscoveryResult(root, buildTool, modules, List.copyOf(resolvedClasspath), artifacts, validatedArtifacts);
    }

    private BuildTool detectBuildTool(Path root) {
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        if (Files.isRegularFile(root.resolve("settings.gradle"))
                || Files.isRegularFile(root.resolve("settings.gradle.kts"))
                || Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            return BuildTool.GRADLE;
        }
        return BuildTool.SOURCE_ONLY;
    }

    private List<Path> discoverModules(Path root, BuildTool buildTool) throws IOException {
        return switch (buildTool) {
            case MAVEN -> discoverMavenModules(root);
            case GRADLE -> discoverGradleModules(root);
            case SOURCE_ONLY -> List.of();
        };
    }

    private List<Path> discoverMavenModules(Path root) throws IOException {
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return List.of();
        }
        try (InputStream input = Files.newInputStream(pom)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            NodeList moduleNodes = factory.newDocumentBuilder()
                    .parse(input)
                    .getElementsByTagNameNS("*", "module");
            List<Path> modules = new ArrayList<>();
            for (int i = 0; i < moduleNodes.getLength(); i++) {
                String module = moduleNodes.item(i).getTextContent().trim();
                if (!module.isEmpty()) {
                    modules.add(root.resolve(module).normalize());
                }
            }
            return modules;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("failed to parse Maven POM " + pom, e);
        }
    }

    private List<Path> discoverGradleModules(Path root) throws IOException {
        Path settings = Files.isRegularFile(root.resolve("settings.gradle.kts"))
                ? root.resolve("settings.gradle.kts")
                : root.resolve("settings.gradle");
        if (!Files.isRegularFile(settings)) {
            return List.of();
        }
        String source = Files.readString(settings);
        Matcher includeMatcher = GRADLE_INCLUDE.matcher(source);
        List<Path> modules = new ArrayList<>();
        while (includeMatcher.find()) {
            Matcher projectMatcher = GRADLE_PROJECT.matcher(includeMatcher.group(1));
            while (projectMatcher.find()) {
                String project = projectMatcher.group(1).trim();
                if (!project.isEmpty()) {
                    modules.add(root.resolve(project.replace(':', '/')).normalize());
                }
            }
        }
        return modules;
    }
}
