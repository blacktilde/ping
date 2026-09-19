package dev.ping.auth;

import com.sun.net.httpserver.HttpServer;
import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;
import dev.ping.vars.Interpolation;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs an OAuth2 authorization-code flow with PKCE.
 *
 * <p>Starting is non-blocking: it opens a loopback listener and returns the authorize URL so
 * the shell can put it in a browser. A virtual thread then waits for the redirect, exchanges
 * the code, caches the token under the same key the engine looks it up by, and reports the
 * outcome as a notification. The client secret never leaves the core.
 */
public final class OAuth2Flow {

    public interface Notifier {
        void completed(Map<String, Object> params);
    }

    private static final long TIMEOUT_SECONDS = 300;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TokenClient tokens;
    private final TokenCache cache;
    private final Notifier notifier;

    public OAuth2Flow(TokenClient tokens, TokenCache cache, Notifier notifier) {
        this.tokens = tokens;
        this.cache = cache;
        this.notifier = notifier;
    }

    public Map<String, Object> start(RequestSpec.Auth auth, Map<String, String> variables) {
        String authUrl = value(auth.authUrl(), variables);
        String tokenUrl = value(auth.tokenUrl(), variables);
        String clientId = value(auth.clientId(), variables);
        String clientSecret = value(auth.clientSecret(), variables);
        String scopes = scopes(value(auth.scopes(), variables));

        if (authUrl == null || authUrl.isBlank()) {
            throw RpcException.authFailed("An authorize URL is required");
        }
        if (tokenUrl == null || tokenUrl.isBlank()) {
            throw RpcException.authFailed("A token URL is required");
        }

        String verifier = randomUrlSafe(48);
        String state = randomUrlSafe(24);

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw RpcException.authFailed("Could not open a loopback redirect: " + e.getMessage(), e);
        }
        int port = server.getAddress().getPort();
        String redirectUri = "http://127.0.0.1:" + port + "/callback";

        BlockingQueue<String> results = new ArrayBlockingQueue<>(1);
        server.createContext("/callback", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String code = query.get("code");
            String returnedState = query.get("state");
            String error = query.get("error");

            byte[] page = page(error == null
                            ? "Authorization complete. You can close this window."
                            : "Authorization failed. You can close this window.")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
            exchange.close();

            if (error != null) {
                results.offer("error:" + error);
            } else if (code != null && state.equals(returnedState)) {
                results.offer(code);
            } else {
                results.offer("error:state_mismatch");
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        String flowId = UUID.randomUUID().toString();
        String authorizeUrl = authorizeUrl(authUrl, clientId, redirectUri, scopes, state, challenge(verifier));

        Thread.ofVirtual().start(() -> {
            try {
                String result = results.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (result == null) {
                    notifier.completed(failure(flowId, "Timed out waiting for authorization"));
                    return;
                }
                if (result.startsWith("error:")) {
                    notifier.completed(failure(flowId, "Authorization failed: " + result.substring(6)));
                    return;
                }

                TokenClient.Token token = tokens.authorizationCode(
                        tokenUrl, result, redirectUri, clientId, clientSecret, verifier);
                String key = Authenticator.cacheKey("authorization-code", tokenUrl, clientId, scopes);
                cache.put(key, token);

                Map<String, Object> completed = new LinkedHashMap<>();
                completed.put("flowId", flowId);
                completed.put("grantKey", key);
                completed.put("accessToken", token.accessToken());
                if (token.refreshToken() != null) {
                    completed.put("refreshToken", token.refreshToken());
                }
                completed.put("expiresAtMillis", token.expiresAtMillis());
                notifier.completed(completed);
            } catch (RpcException e) {
                notifier.completed(failure(flowId, e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notifier.completed(failure(flowId, "Interrupted"));
            } finally {
                server.stop(0);
            }
        });

        Map<String, Object> started = new LinkedHashMap<>();
        started.put("flowId", flowId);
        started.put("authorizeUrl", authorizeUrl);
        started.put("redirectUri", redirectUri);
        return started;
    }

    private static Map<String, Object> failure(String flowId, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flowId", flowId);
        out.put("error", message);
        return out;
    }

    private static String authorizeUrl(
            String base, String clientId, String redirectUri, String scopes, String state, String challenge) {
        StringBuilder url = new StringBuilder(base);
        char separator = base.contains("?") ? '&' : '?';
        append(url, separator, "response_type", "code");
        append(url, '&', "client_id", clientId);
        append(url, '&', "redirect_uri", redirectUri);
        append(url, '&', "state", state);
        append(url, '&', "code_challenge", challenge);
        append(url, '&', "code_challenge_method", "S256");
        append(url, '&', "scope", scopes);
        return url.toString();
    }

    private static void append(StringBuilder url, char separator, String name, String value) {
        if (value == null) {
            return;
        }
        url.append(separator).append(encode(name)).append('=').append(encode(value));
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw RpcException.authFailed("SHA-256 is unavailable, so PKCE cannot run", e);
        }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator == -1) {
                continue;
            }
            query.put(decode(pair.substring(0, separator)), decode(pair.substring(separator + 1)));
        }
        return query;
    }

    private static String page(String message) {
        return "<!doctype html><html><body style=\"font-family:sans-serif;padding:2rem\">"
                + "<p>" + message + "</p></body></html>";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String value(String raw, Map<String, String> variables) {
        return Interpolation.apply(raw, variables);
    }

    private static String scopes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().replaceAll("[,\\s]+", " ");
    }
}
