package io.runtimeconditions.profiler.manifest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ManifestModel {
    private final String packageName;
    private final Map<String, String> constants;
    private final List<SymbolMapping> declarations;
    private final List<SymbolMapping> options;

    ManifestModel(
            String packageName,
            Map<String, String> constants,
            List<SymbolMapping> declarations,
            List<SymbolMapping> options) {
        this.packageName = packageName;
        this.constants = Map.copyOf(Objects.requireNonNull(constants, "constants"));
        this.declarations = List.copyOf(Objects.requireNonNull(declarations, "declarations"));
        this.options = List.copyOf(Objects.requireNonNull(options, "options"));
    }

    public String packageName() {
        return packageName;
    }

    public Map<String, String> constants() {
        return constants;
    }

    public List<SymbolMapping> declarations() {
        return declarations;
    }

    public List<SymbolMapping> options() {
        return options;
    }
}
