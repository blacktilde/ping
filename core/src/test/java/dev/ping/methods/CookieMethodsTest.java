package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.ping.auth.TokenCache;
import dev.ping.cookies.CookieJar;
import dev.ping.rpc.RpcServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cookies over RPC, as JSON: the scope and opt-out cross Jackson the way they do in the shipped
 * binary, and a cookie's value never comes back from a list.
 */
class CookieMethodsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final CookieJar jar = new CookieJar();
    private final AtomicReference<String> seen = new AtomicReference<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "sid=very-secret-session; Path=/; HttpOnly");
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.createContext("/me", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Cookie"));
            try (exchange) {
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** One request per session, sharing the jar; sessions run their handlers concurrently. */
    private JsonNode call(String method, Map<String, Object> params) throws Exception {
        String line = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RpcServer rpc = new RpcServer(new ByteArrayInputStream((line + "\n").getBytes(StandardCharsets.UTF_8)), out);
        HttpMethods.registerOn(rpc, new TokenCache(), jar);
        CookieMethods.registerOn(rpc, jar);
        rpc.serve();
        return out.toString(StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank())
                .map(l -> {
                    try {
                        return json.readTree(l);
                    } catch (Exception e) {
                        throw new AssertionError(l, e);
                    }
                })
                .filter(n -> n.has("id")).findFirst().orElseThrow();
    }

    private void send(String path, Map<String, Object> extra) throws Exception {
        Map<String, Object> params = new java.util.LinkedHashMap<>(extra);
        params.put("url", baseUrl + path);
        assertFalse(call("http.send", params).has("error"));
    }

    @Test
    void aScopedSendStoresAndReplaysCookiesAndListNeverReturnsTheValue() throws Exception {
        send("/login", Map.of("cookieScope", "root|demo|dev"));
        send("/me", Map.of("cookieScope", "root|demo|dev"));
        assertEquals("sid=very-secret-session", seen.get());

        JsonNode listed = call("cookies.list", Map.of("scope", "root|demo|dev")).path("result").path("cookies");
        assertEquals(1, listed.size());
        assertEquals("sid", listed.get(0).path("name").asText());
        assertEquals("127.0.0.1", listed.get(0).path("domain").asText());
        assertTrue(listed.get(0).path("httpOnly").asBoolean());
        assertFalse(listed.toString().contains("very-secret-session"), "a value must never cross: " + listed);
    }

    @Test
    void aSendWithNoScopeIsStatelessAndCookiesFalseOptsOut() throws Exception {
        send("/login", Map.of());
        send("/me", Map.of());
        assertNull(seen.get());

        send("/login", Map.of("cookieScope", "s", "cookies", false));
        assertEquals(0, call("cookies.list", Map.of("scope", "s")).path("result").path("cookies").size());
    }

    @Test
    void scopesAreIsolatedAndClearRemovesJustWhatWasAsked() throws Exception {
        send("/login", Map.of("cookieScope", "a"));
        send("/login", Map.of("cookieScope", "b"));

        assertEquals(1, call("cookies.clear", Map.of("scope", "a", "name", "sid")).path("result").path("removed").asInt());
        assertEquals(0, call("cookies.list", Map.of("scope", "a")).path("result").path("cookies").size());
        assertEquals(1, call("cookies.list", Map.of("scope", "b")).path("result").path("cookies").size());

        call("cookies.clearAll", Map.of());
        assertEquals(0, call("cookies.list", Map.of("scope", "b")).path("result").path("cookies").size());
    }

    @Test
    void aScopeIsRequired() throws Exception {
        assertEquals(-32602, call("cookies.list", Map.of()).path("error").path("code").asInt());
        assertEquals(-32602, call("cookies.clear", Map.of("scope", "")).path("error").path("code").asInt());
    }
}
