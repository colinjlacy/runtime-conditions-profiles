package example;

import io.runtimeconditions.extensions.commonintegrations.*;

final class App {
    void declarations() {
        Cache.declare(
                "session-cache",
                Cache.keyValue(Cache.Engine.REDIS));
    }
}
