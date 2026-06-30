package io.runtimeconditions.profiler.profile;

import io.runtimeconditions.profiler.manifest.SymbolMapping;

record CallIdentity(String className, String memberName) {
    boolean matches(SymbolMapping mapping) {
        if (!memberName.equals(mapping.memberName())) {
            return false;
        }
        String mappingClass = mapping.className();
        return className.equals(mappingClass) || className.endsWith("." + mappingClass);
    }
}
