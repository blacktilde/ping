package dev.ping.auth;

import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;
import dev.ping.vars.Interpolation;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns an auth config into the headers and query parameters it contributes.
 *
 * <p>Values are interpolated first, so a secret is just a variable the shell resolved from
 * `safeStorage` and merged in at runtime precedence. Nothing here needs to know which values
 * are sensitive, and nothing is ever read from or written to a collection file.
 */
public final class Authenticator {

    public record Applied(Map<String, String> headers, List<RequestSpec.Param> query) {
        public static final Applied NONE = new Applied(Map.of(), List.of());
    }

    private final TokenClient tokens;
    private final TokenCache cache;

    public Authenticator(TokenClient tokens, TokenCache cache) {
        this.tokens = tokens;
        this.cache = cache;
    }

    public Applied apply(RequestSpec.Auth auth, Map<String, String> variables) {
        if (auth == null || !auth.isConfigured()) {
            return Applied.NONE;
        }
        return switch (auth.type().toLowerCase(Locale.ROOT)) {
            case "none" -> Applied.NONE;
            case "basic" -> authorization("Basic " + base64(
                    required(value(auth.username(), variables), "username") + ":"
                            + required(value(auth.password(), variables), "password")));
            case "bearer" -> authorization(
                    "Bearer " + required(value(auth.token(), variables), "bearer token"));
            case "api-key", "apikey", "api_key" -> apiKey(auth, variables);
            case "oauth2-client-credentials", "client-credentials" -> authorization(
                    "Bearer " + clientCredentialsToken(auth, variables));
            case "oauth2-authorization-code", "authorization-code" -> authorization(
                    "Bearer " + authorizationCodeToken(auth, variables));
            default -> throw RpcException.authFailed("Unknown auth type: " + auth.type());
        };
    }

    /**
     * The cache key for a grant. Shared with the interactive flow so a token obtained by
     * authorizing is the same token a later send picks up.
     */
    public static String cacheKey(String grant, String tokenUrl, String clientId, String scopes) {
        return grant + "|" + text(tokenUrl) + "|" + text(clientId) + "|" + text(scopes);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public String cacheKey(String grant, RequestSpec.Auth auth, Map<String, String> variables) {
        return cacheKey(grant,
                value(auth.tokenUrl(), variables),
                value(auth.clientId(), variables),
                scopes(value(auth.scopes(), variables)));
    }

    public TokenCache cache() {
        return cache;
    }

    private String clientCredentialsToken(RequestSpec.Auth auth, Map<String, String> variables) {
        String key = cacheKey("client-credentials", auth, variables);
        TokenClient.Token cached = cache.get(key);
        if (cached != null && cached.isValid()) {
            return cached.accessToken();
        }

        TokenClient.Token token = tokens.clientCredentials(
                value(auth.tokenUrl(), variables),
                value(auth.clientId(), variables),
                value(auth.clientSecret(), variables),
                scopes(value(auth.scopes(), variables)),
                null);
        cache.put(key, token);
        return token.accessToken();
    }

    private String authorizationCodeToken(RequestSpec.Auth auth, Map<String, String> variables) {
        String key = cacheKey("authorization-code", auth, variables);

        // A token the shell restored from safeStorage is used first, so a session survives
        // a restart without re-running the browser flow.
        if (auth.accessToken() != null && !auth.accessToken().isBlank()) {
            TokenClient.Token provided = new TokenClient.Token(
                    auth.accessToken(), auth.refreshToken(), "Bearer",
                    auth.expiresAtMillis() == null ? 0 : auth.expiresAtMillis());
            if (provided.isValid()) {
                cache.put(key, provided);
                return provided.accessToken();
            }
        }

        TokenClient.Token cached = cache.get(key);
        if (cached != null && cached.isValid()) {
            return cached.accessToken();
        }

        if (cached != null && cached.refreshToken() != null) {
            TokenClient.Token refreshed = tokens.refresh(
                    value(auth.tokenUrl(), variables),
                    cached.refreshToken(),
                    value(auth.clientId(), variables),
                    value(auth.clientSecret(), variables));
            cache.put(key, refreshed);
            return refreshed.accessToken();
        }

        throw RpcException.authFailed("Not authorized yet; run Authorize for this request");
    }

    private static Applied apiKey(RequestSpec.Auth auth, Map<String, String> variables) {
        String name = required(value(auth.key(), variables), "API key name");
        String value = value(auth.value(), variables);
        String keyValue = value == null ? "" : value;

        if ("query".equalsIgnoreCase(auth.in())) {
            return new Applied(Map.of(), List.of(new RequestSpec.Param(name, keyValue, true)));
        }
        return new Applied(Map.of(name, keyValue), List.of());
    }

    private static Applied authorization(String header) {
        return new Applied(Map.of("Authorization", header), List.of());
    }

    private static String value(String raw, Map<String, String> variables) {
        return Interpolation.apply(raw, variables);
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw RpcException.authFailed("The " + what + " is missing");
        }
        return value;
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** OAuth2 scope is space-delimited; accept commas and repeated spaces too. */
    private static String scopes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().replaceAll("[,\\s]+", " ");
    }
}
