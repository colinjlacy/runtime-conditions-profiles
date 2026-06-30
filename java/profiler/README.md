# Java Profiler

The Java profiler starts with build-tool-aware artifact discovery and can generate Runtime Conditions Profiles from declarative Java binding packages.

Current implementation:

- Detects Maven, Gradle, or source-only project layouts.
- Resolves build-tool classpath entries through Maven or Gradle when `--resolve-build-classpath` is used.
  - Maven uses `./mvnw` when present, otherwise `mvn`.
  - Gradle uses `./gradlew` when present, otherwise `gradle`.
- Discovers Runtime Conditions artifacts in Java resource layout:
  - `META-INF/runtimeconditions/runtimeconditions.bindings.yaml`
  - `META-INF/runtimeconditions/runtimeconditions.package.yaml`
  - `META-INF/runtimeconditions/runtimeconditions.extension.yaml`
- Discovers the same artifacts in JAR classpath entries.
- Supports repository-local source layouts where the manifest is placed at the package root.
- Validates discovered artifacts before they can be used by future extraction:
  - manifest kind and `metadata.language`
  - required Java manifest section
  - full Java manifest structure, including constants, declarations, nested options, and YAML anchors
  - package-local `runtimeconditions.extension.yaml`
  - manifest extension ID against extension definition `metadata.id`
  - duplicate extension definitions and unresolved extension dependencies across discovered artifacts
- Generates Runtime Conditions Profiles from `RuntimeConditionsBinding` declarative Java calls.
- Emits profile YAML from Java declarations, nested options, enum constants, static imports, fully qualified declaration calls, cross-file string constants, class literals, and Java schema classes.
- Validates generated profiles against the resolved extension dependency closure and vocabulary before output.
- Validates Java extension package artifacts recursively with `validate-extension` and `validate-extensions`.

Not implemented yet:

- Embedded Maven Resolver or Gradle Tooling API integration.
- SDK/runtime `RuntimeConditionsPackage` extraction.
- Extension JSON Schema execution during generated profile validation.

## Compile and Run

```sh
mvn -q package

java -jar target/runtimeconditions-java-profiler-0.1.0-SNAPSHOT.jar discover \
  --project src/testdata/maven-app \
  --resolve-build-classpath

java -jar target/runtimeconditions-java-profiler-0.1.0-SNAPSHOT.jar validate-extensions \
  --root ../../extensions

java -jar target/runtimeconditions-java-profiler-0.1.0-SNAPSHOT.jar generate \
  --project src/testdata/declarative-app \
  --classpath ../../extensions/common-integrations/java:../../extensions/env-configuration/java \
  --name java-declarative-app \
  --workload-uri example/java-declarative-app \
  --workload-version test
```

## Test

```sh
mvn -q test
```
