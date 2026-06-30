package io.runtimeconditions.demos.requestlogger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class RequestLoggerHttp {
    static {
        Conditions.declare();
    }

    private RequestLoggerHttp() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(envOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/ready", RequestLoggerHttp::readinessHandler);
        server.createContext("/demo", RequestLoggerHttp::demoHandler);
        server.start();
        System.out.println("request-logger Java demo listening on :" + port);
    }

    private static void readinessHandler(HttpExchange exchange) throws IOException {
        String todos = statusString(checkTodosApi());
        String cache = statusString(checkRedis());
        if (!"ok".equals(todos) || !"ok".equals(cache)) {
            write(exchange, 503, "One or more dependencies are not healthy: todosApi=" + todos + ", cache=" + cache);
            return;
        }
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private static void demoHandler(HttpExchange exchange) throws IOException {
        String todos = statusString(checkTodosApi());
        String cache = statusString(checkRedis());
        int status = "ok".equals(todos) && "ok".equals(cache) ? 200 : 500;
        write(exchange, status, "{\"todosApi\":\"" + todos + "\",\"cache\":\"" + cache + "\"}\n");
    }

    private static Exception checkTodosApi() {
        String baseUrl = trimTrailingSlash(System.getenv("TODOS_API_URL"));
        if (baseUrl.isBlank()) {
            return new IllegalStateException("TODOS_API_URL is not set");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/todos/1"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new IllegalStateException("todos-api returned HTTP " + response.statusCode());
            }
            String body = response.body();
            if (!body.contains("\"id\"") || !body.contains("\"title\"")) {
                return new IllegalStateException("todos-api response was incomplete");
            }
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private static Exception checkRedis() {
        try {
            InetSocketAddress address = redisAddress();
            try (Socket socket = new Socket()) {
                socket.connect(address, 3_000);
                socket.setSoTimeout(3_000);
                socket.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
                byte[] response = socket.getInputStream().readNBytes(7);
                String text = new String(response, StandardCharsets.UTF_8);
                if (!text.startsWith("+PONG")) {
                    return new IllegalStateException("redis ping returned " + text.strip());
                }
            }
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private static InetSocketAddress redisAddress() {
        String rawUrl = System.getenv("REDIS_URL");
        if (rawUrl != null && !rawUrl.isBlank()) {
            URI uri = URI.create(rawUrl);
            if (uri.getHost() != null && uri.getPort() > 0) {
                return new InetSocketAddress(uri.getHost(), uri.getPort());
            }
        }

        String host = System.getenv("REDIS_HOST");
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("REDIS_URL or REDIS_HOST must be set");
        }
        int port = Integer.parseInt(envOrDefault("REDIS_PORT", "6379"));
        return new InetSocketAddress(host, port);
    }

    private static String statusString(Exception error) {
        if (error == null) {
            return "ok";
        }
        System.err.println(error.getMessage());
        return "error";
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
