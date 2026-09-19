package dev.ping.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access tokens held for the life of the core process, keyed by the grant they belong to.
 *
 * <p>In memory only: the shell owns persistence, and re-injects a refresh token from
 * `safeStorage` when the app starts again.
 */
public final class TokenCache {

    private final Map<String, TokenClient.Token> tokens = new ConcurrentHashMap<>();

    public TokenClient.Token get(String key) {
        return tokens.get(key);
    }

    public void put(String key, TokenClient.Token token) {
        tokens.put(key, token);
    }

    public void remove(String key) {
        tokens.remove(key);
    }
}
