package dev.ping.run;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * A small collection on disk and a loopback server for it to talk to.
 *
 * <p>Sidebar order is: the {@code auth} folder first (Login), then requests by display name
 * (Dead, Health, Wrong). Health passes only when the {@code staging} environment supplies
 * {@code who}; Wrong asserts a status the server never returns; Dead points at a closed port.
 */
public final class RunFixture {

    private RunFixture() {
    }

    public static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/ok", exchange -> reply(exchange, "{\"id\":7}"));
        server.createContext("/who", exchange -> reply(exchange,
                "{\"who\":\"" + exchange.getRequestHeaders().getFirst("X-Who") + "\"}"));
        server.createContext("/login", exchange -> reply(exchange, "{\"token\":\"tok-secret-99\",\"n\":3}"));
        // A session: /session sets a cookie, /me answers with whatever Cookie header it received.
        server.createContext("/session", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "sid=run-cookie-4711; Path=/");
            reply(exchange, "{\"started\":true}");
        });
        server.createContext("/me", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            boolean ok = cookie != null && cookie.contains("sid=run-cookie-4711");
            byte[] payload = ("{\"cookie\":\"" + (cookie == null ? "" : cookie) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(ok ? 200 : 401, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        // Reports what it was sent, so a test can prove a file arrived byte for byte.
        server.createContext("/upload", exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String sha;
                try {
                    sha = java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(body));
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IOException(e);
                }
                String head = new String(body, 0, Math.min(body.length, 400), StandardCharsets.ISO_8859_1)
                        .replaceAll("[^\\x20-\\x7E]", ".");
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        java.util.Map.of("bytes", body.length, "sha256", sha, "head", head));
                byte[] payload = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        // A feed that never ends: one event, then keep-alives until the client goes away.
        server.createContext("/feed", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (exchange; OutputStream out = exchange.getResponseBody()) {
                out.write("data: hello\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                while (true) {
                    Thread.sleep(100);
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException | InterruptedException e) {
                // The client hung up, which is how a run ends this feed.
            }
        });
        server.start();
        return server;
    }

    public static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * @param includeBroken false leaves only Login and Health, a collection that can pass
     * @return the collection folder, {@code root/demo}
     */
    public static Path write(Path root, String baseUrl, boolean includeBroken) throws IOException {
        Path demo = root.resolve("demo");
        Files.createDirectories(demo.resolve("auth"));
        Files.createDirectories(demo.resolve("environments"));

        Files.writeString(demo.resolve("collection.yaml"), """
                name: Demo
                variables:
                - name: baseUrl
                  value: "%s"
                  enabled: true
                """.formatted(baseUrl));
        Files.writeString(demo.resolve("environments/staging.yaml"), """
                name: Staging
                variables:
                - name: who
                  value: staging
                  enabled: true
                """);
        Files.writeString(demo.resolve("auth/login.yaml"), """
                name: Login
                method: POST
                url: "{{baseUrl}}/ok"
                asserts:
                - type: status
                  expected: "200"
                """);
        Files.writeString(demo.resolve("health.yaml"), """
                name: Health
                method: GET
                url: "{{baseUrl}}/who"
                headers:
                - name: X-Who
                  value: "{{who}}"
                  enabled: true
                asserts:
                - type: status
                  expected: "200"
                - type: jsonpath
                  target: $.who
                  op: equals
                  expected: staging
                """);
        if (includeBroken) {
            Files.writeString(demo.resolve("wrong.yaml"), """
                    name: Wrong
                    method: GET
                    url: "{{baseUrl}}/ok"
                    asserts:
                    - type: status
                      expected: "404"
                    """);
            Files.writeString(demo.resolve("dead.yaml"), """
                    name: Dead
                    method: GET
                    url: "http://127.0.0.1:1/"
                    """);
        }
        return demo;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        try (exchange) {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
    }
}
