package example;

import io.runtimeconditions.extensions.commonintegrations.Cache;
import io.runtimeconditions.extensions.envconfiguration.EnvConfiguration;

final class App {
    void declarations() {
        EnvConfiguration.sensitive();
        Cache.declare(
                "unused-env-cache",
                Cache.keyValue(Cache.Engine.REDIS));
    }
}
