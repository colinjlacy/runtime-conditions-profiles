# Java Runtime Conditions

This tree contains Java-native Runtime Conditions tooling.

## Layout

- `profiler/`: Java profiler module. It discovers Runtime Conditions artifacts from Maven and Gradle project layouts, validates Java binding packages, generates Runtime Conditions Profiles from declarative Java source, and packages as an executable JAR.

The Java profiler is intentionally separate from the Go profiler. Shared behavior should live in manifest conventions, extension validation, profile validation, and cross-language fixtures, not in a Go parser for Java source.
