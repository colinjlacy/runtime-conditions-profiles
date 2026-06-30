package io.runtimeconditions.profiler.profile;

import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public final class ProfileYamlWriter {
    private ProfileYamlWriter() {
    }

    public static String write(Map<String, Object> profile) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(profile);
    }
}
