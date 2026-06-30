package io.runtimeconditions.profiler.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlDocument {
    private final Map<String, Object> root;

    private YamlDocument(Map<String, Object> root) {
        this.root = Map.copyOf(root);
    }

    public static YamlDocument parse(String source) {
        LoaderOptions options = new LoaderOptions();
        Object loaded = new Yaml(new SafeConstructor(options)).load(source);
        if (loaded == null) {
            return new YamlDocument(Map.of());
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("YAML document must be a mapping");
        }
        return new YamlDocument(copyStringMap(map));
    }

    public Object value(String... path) {
        Object current = root;
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    public String scalar(String... path) {
        Object value = value(path);
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    public List<String> stringList(String... path) {
        Object value = value(path);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String || item instanceof Number || item instanceof Boolean) {
                result.add(String.valueOf(item));
            }
        }
        return List.copyOf(result);
    }

    public boolean hasSection(String section) {
        return root.containsKey(section);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return (Map<String, Object>) map;
    }

    public static List<?> asList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        return list;
    }

    private static Map<String, Object> copyStringMap(Map<?, ?> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("YAML mapping keys must be strings");
            }
            result.put(key, copyValue(entry.getValue()));
        }
        return result;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyStringMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(copyValue(item));
            }
            return List.copyOf(result);
        }
        return value;
    }
}
