package example;

import static io.runtimeconditions.extensions.commonintegrations.Http.post;
import static io.runtimeconditions.extensions.commonintegrations.Http.request;
import static io.runtimeconditions.extensions.commonintegrations.Http.response;
import static io.runtimeconditions.extensions.envconfiguration.EnvConfiguration.env;
import static io.runtimeconditions.extensions.envconfiguration.EnvConfiguration.optional;

import example.models.UserRequest;
import example.models.UserResponse;
import example.settings.Settings;
import io.runtimeconditions.extensions.commonintegrations.Api;

final class App {
    void declarations() {
        Api.declare(
                Settings.API_NAME,
                Api.spec("openapi", Settings.SPEC_URI, Settings.SPEC_VERSION),
                post(Settings.USERS_PATH, request(UserRequest.class), response(UserResponse.class)),
                env("token", Settings.AUTH_TOKEN, optional()));

        io.runtimeconditions.extensions.commonintegrations.Cache.declare(
                "semantic-cache",
                io.runtimeconditions.extensions.commonintegrations.Cache.keyValue(
                        io.runtimeconditions.extensions.commonintegrations.Cache.Engine.MEMCACHED));
    }
}
