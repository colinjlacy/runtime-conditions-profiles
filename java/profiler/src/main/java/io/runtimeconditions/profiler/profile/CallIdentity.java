package io.runtimeconditions.profiler.profile;

import io.runtimeconditions.profiler.manifest.SymbolMapping;

/**
 * Identifies a called method by its owning class and member name. Matching
 * accepts either the simple or fully qualified class name so calls imported by
 * simple name still map to a binding declaration.
 */
record CallIdentity(String className, String memberName) {
    boolean matches(SymbolMapping mapping) {
        if (!memberName.equals(mapping.memberName())) {
            return false;
        }
        String mappingClass = mapping.className();
        return className.equals(mappingClass) || className.endsWith("." + mappingClass);
    }
}
