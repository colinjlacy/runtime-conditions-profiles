package io.runtimeconditions.profiler.manifest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SymbolMapping {
    private final String className;
    private final String memberName;
    private final String memberField;
    private final String target;
    private final String kind;
    private final String interfaceType;
    private final String value;
    private final String method;
    private final Integer nameArg;
    private final Integer classArg;
    private final Integer enumArg;
    private final Map<String, Integer> stringArgs;
    private final List<String> appliesToKinds;
    private final List<String> appliesToInterfaceTypes;
    private final List<SymbolMapping> options;

    SymbolMapping(
            String className,
            String memberName,
            String memberField,
            String target,
            String kind,
            String interfaceType,
            String value,
            String method,
            Integer nameArg,
            Integer classArg,
            Integer enumArg,
            Map<String, Integer> stringArgs,
            List<String> appliesToKinds,
            List<String> appliesToInterfaceTypes,
            List<SymbolMapping> options) {
        this.className = className;
        this.memberName = memberName;
        this.memberField = memberField;
        this.target = target;
        this.kind = kind;
        this.interfaceType = interfaceType;
        this.value = value;
        this.method = method;
        this.nameArg = nameArg;
        this.classArg = classArg;
        this.enumArg = enumArg;
        this.stringArgs = Map.copyOf(Objects.requireNonNull(stringArgs, "stringArgs"));
        this.appliesToKinds = List.copyOf(Objects.requireNonNull(appliesToKinds, "appliesToKinds"));
        this.appliesToInterfaceTypes = List.copyOf(Objects.requireNonNull(appliesToInterfaceTypes, "appliesToInterfaceTypes"));
        this.options = List.copyOf(Objects.requireNonNull(options, "options"));
    }

    public String className() {
        return className;
    }

    public String memberName() {
        return memberName;
    }

    public String memberField() {
        return memberField;
    }

    public String target() {
        return target;
    }

    public String kind() {
        return kind;
    }

    public String interfaceType() {
        return interfaceType;
    }

    public String value() {
        return value;
    }

    public String method() {
        return method;
    }

    public Integer nameArg() {
        return nameArg;
    }

    public Integer classArg() {
        return classArg;
    }

    public Integer enumArg() {
        return enumArg;
    }

    public Map<String, Integer> stringArgs() {
        return stringArgs;
    }

    public List<String> appliesToKinds() {
        return appliesToKinds;
    }

    public List<String> appliesToInterfaceTypes() {
        return appliesToInterfaceTypes;
    }

    public List<SymbolMapping> options() {
        return options;
    }
}
