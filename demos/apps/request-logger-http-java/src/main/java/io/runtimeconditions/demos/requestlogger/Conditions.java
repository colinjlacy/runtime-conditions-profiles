package io.runtimeconditions.demos.requestlogger;

import io.runtimeconditions.extensions.commonintegrations.Api;
import io.runtimeconditions.extensions.commonintegrations.Cache;
import io.runtimeconditions.extensions.commonintegrations.Http;
import io.runtimeconditions.extensions.envconfiguration.EnvConfiguration;

final class Conditions {
    private Conditions() {
    }

    static void declare() {
        if (System.nanoTime() < 0) {
            Api.declare(
                    "todos-api",
                    Api.spec("openapi", "catalog://api/default/todos-api", "1.0.0"),
                    Http.get("/todos/{id}", Http.response(Todo.class)),
                    EnvConfiguration.env("baseUrl", "TODOS_API_URL"));

            Cache.declare(
                    "request-cache",
                    Cache.keyValue(Cache.Engine.REDIS),
                    EnvConfiguration.envAlternative(EnvConfiguration.env("url", "REDIS_URL")),
                    EnvConfiguration.envAlternative(
                            EnvConfiguration.env("hostname", "REDIS_HOST"),
                            EnvConfiguration.env("port", "REDIS_PORT")));
        }
    }
}
