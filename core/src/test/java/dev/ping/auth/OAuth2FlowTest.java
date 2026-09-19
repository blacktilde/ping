package dev.ping.auth;

import com.sun.net.httpserver.HttpServer;
import dev.ping.http.HttpEngine;
import dev.ping.http.RequestSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the authorization-code flow against a mock identity provider.
 *
 * <p>The test plays the browser: it reads the authorize URL, then calls the loopback
 * redirect itself. That reaches every part of the flow except the user's consent screen.
 */
class OAuth2FlowTest {

    private HttpServer server;
    private String baseUrl;
    private final TokenCache cache = new TokenCache();
    private final BlockingQueue<Map<String, Object>> completed = new LinkedBlockingQueue<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void runsPkceAndCachesATokenTheEngineReuses() throws Exception {
        AtomicReference<String> tokenForm = new AtomicReference<>();
        AtomicReference<String> resourceAuth = new AtomicReference<>();

        server.createContext("/token", exchange -> {
            try (exchange) {
                tokenForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] payload = ("{\"access_token\":\"flow-token\",\"refresh_token\":\"refresh-token\","
                        + "\"expires_in\":3600}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        server.createContext("/resource", exchange -> {
            try (exchange) {
                resourceAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(204, -1);
            }
        });

        RequestSpec.Auth auth = RequestSpec.Auth.authorizationCode(
                "https://idp.example/authorize", baseUrl + "/token", "client", "secret", "read write");

        OAuth2Flow flow = new OAuth2Flow(new TokenClient(), cache, completed::offer);
        Map<String, Object> started = flow.start(auth, Map.of());

        Map<String, String> query = query((String) started.get("authorizeUrl"));
        assertEquals("code", query.get("response_type"));
        assertEquals("client", query.get("client_id"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertTrue(query.get("code_challenge").length() >= 43, "a PKCE challenge is present");
        assertEquals("read write", query.get("scope"));

        String redirectUri = query.get("redirect_uri");
        String state = query.get("state");

        HttpResponse<String> page = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(redirectUri + "?code=abc&state=" + state)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode(), "the browser sees a landing page");

        Map<String, Object> result = completed.poll(10, TimeUnit.SECONDS);
        assertNotNull(result, "the flow must report completion");
        assertEquals("flow-token", result.get("accessToken"));
        assertEquals("refresh-token", result.get("refreshToken"));
        String grantKey = (String) result.get("grantKey");
        assertTrue(grantKey.startsWith("authorization-code|"), grantKey);

        assertTrue(tokenForm.get().contains("grant_type=authorization_code"), tokenForm.get());
        assertTrue(tokenForm.get().contains("code=abc"), tokenForm.get());
        assertTrue(tokenForm.get().contains("code_verifier="), tokenForm.get());

        // A send with the same config now finds the token the flow cached.
        new HttpEngine(cache).send(new RequestSpec.Builder(baseUrl + "/resource").auth(auth).build());
        assertEquals("Bearer flow-token", resourceAuth.get());
    }

    @Test
    void rejectsAMismatchedState() throws Exception {
        RequestSpec.Auth auth = RequestSpec.Auth.authorizationCode(
                "https://idp.example/authorize", baseUrl + "/token", "client", null, null);

        OAuth2Flow flow = new OAuth2Flow(new TokenClient(), cache, completed::offer);
        Map<String, Object> started = flow.start(auth, Map.of());
        String redirectUri = query((String) started.get("authorizeUrl")).get("redirect_uri");

        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(redirectUri + "?code=abc&state=wrong")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        Map<String, Object> result = completed.poll(10, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result.get("error").toString().contains("state_mismatch"), result.toString());
    }

    private static Map<String, String> query(String url) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            query.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return query;
    }
}
