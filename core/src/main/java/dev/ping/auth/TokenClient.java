package dev.ping.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.http.NetworkConfig;
import dev.ping.http.ProxyRouter;
import dev.ping.rpc.RpcException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exchanges an OAuth2 grant for a token at a token endpoint.
 *
 * <p>This is the part of auth that belongs in the core: the CLI runner needs it too, and it
 * is HTTP, which is what the core is for. Secrets arrive already interpolated, so this never
 * reads a file or a keychain.
 */
public final class TokenClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;
    private final ProxyRouter router;

    public TokenClient() {
        this(NetworkConfig.NONE);
    }

    private TokenClient(NetworkConfig network) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        this.router = network.applyTo(builder);
        this.client = builder.build();
    }

    /**
     * A client whose token exchanges use this network configuration. A token endpoint sits
     * behind the same corporate proxy as the API it protects.
     */
    public TokenClient through(NetworkConfig network) {
        return network == null || network.proxy() == null ? this : new TokenClient(network);
    }

    /** @param expiresAtMillis epoch millis, or 0 when the server did not say */
    public record Token(String accessToken, String refreshToken, String tokenType, long expiresAtMillis) {

        /** Treats a token as spent slightly early, so it cannot expire mid-flight. */
        public boolean isValid() {
            return accessToken != null && !accessToken.isBlank()
                    && (expiresAtMillis <= 0 || System.currentTimeMillis() < expiresAtMillis - 10_000);
        }
    }

    public Token clientCredentials(
            String tokenUrl, String clientId, String clientSecret, String scopes, String audience) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("scope", scopes);
        form.put("audience", audience);
        return post(tokenUrl, form);
    }

    public Token authorizationCode(
            String tokenUrl, String code, String redirectUri, String clientId, String clientSecret,
            String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("code_verifier", codeVerifier);
        return post(tokenUrl, form);
    }

    public Token refresh(String tokenUrl, String refreshToken, String clientId, String clientSecret) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        return post(tokenUrl, form);
    }

    private Token post(String tokenUrl, Map<String, String> form) {
        if (tokenUrl == null || tokenUrl.isBlank()) {
            throw RpcException.authFailed("An OAuth2 token URL is required");
        }

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : form.entrySet()) {
            if (field.getValue() == null) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(encode(field.getKey())).append('=').append(encode(field.getValue()));
        }

        HttpRequest request;
        try {
            URI uri = URI.create(tokenUrl);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            String proxyAuthorization = router == null ? null : router.authorizationFor(uri);
            if (proxyAuthorization != null) {
                builder.header("Proxy-Authorization", proxyAuthorization);
            }
            request = builder.build();
        } catch (IllegalArgumentException e) {
            throw RpcException.authFailed("Malformed token URL: " + tokenUrl);
        }

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw RpcException.authFailed("Token request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RpcException.authFailed("Token request was interrupted", e);
        }

        JsonNode json;
        try {
            json = JSON.readTree(response.body());
        } catch (IOException e) {
            throw RpcException.authFailed("Token endpoint returned non-JSON (HTTP " + response.statusCode() + ")");
        }

        if (response.statusCode() / 100 != 2) {
            String error = json.path("error").asText(null);
            String description = json.path("error_description").asText(null);
            String detail = description != null ? description : error;
            throw RpcException.authFailed("Token endpoint rejected the grant"
                    + (detail == null ? "" : ": " + detail)
                    + " (HTTP " + response.statusCode() + ")");
        }

        String accessToken = json.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw RpcException.authFailed("Token endpoint returned no access_token");
        }

        long expiresAt = 0;
        if (json.hasNonNull("expires_in")) {
            expiresAt = System.currentTimeMillis() + json.path("expires_in").asLong() * 1000L;
        }
        return new Token(accessToken, json.path("refresh_token").asText(null),
                json.path("token_type").asText("Bearer"), expiresAt);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
