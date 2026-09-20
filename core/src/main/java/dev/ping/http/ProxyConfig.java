package dev.ping.http;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * How requests reach the network: directly, through the proxy named by the environment, or
 * through one the user configured.
 *
 * <p>This never comes from a collection. The shell reads it from the user's own settings and
 * injects it into a call, because a proxy sees every request and its credentials; a shared
 * collection must not be able to choose either.
 *
 * @param mode     {@code none}, {@code system} or {@code manual}; absent means {@code none}
 * @param url      manual: {@code host:port} or {@code http://[user:pass@]host:port}
 * @param username manual: overrides a user name in the address
 * @param password manual: the resolved password, never a reference; overrides the address's
 * @param bypass   manual: hosts that skip the proxy, in {@code NO_PROXY} form
 * @param env      system: the environment to read {@code HTTP_PROXY}, {@code HTTPS_PROXY},
 *                 {@code ALL_PROXY} and {@code NO_PROXY} from, given by the caller so the core
 *                 never reads the process environment itself
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProxyConfig(
        String mode,
        String url,
        String username,
        String password,
        List<String> bypass,
        Map<String, String> env) {

    public static final ProxyConfig NONE = new ProxyConfig(null, null, null, null, null, null);

    public static ProxyConfig manual(String url, String username, String password, List<String> bypass) {
        return new ProxyConfig("manual", url, username, password, bypass, null);
    }

    public static ProxyConfig system(Map<String, String> env) {
        return new ProxyConfig("system", null, null, null, null, env);
    }

    /** A password in a log line or an exception message is a leaked password. */
    @Override
    public String toString() {
        return "ProxyConfig[mode=" + mode + "]";
    }
}
