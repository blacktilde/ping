package dev.ping.http;

import java.net.http.HttpClient;

/**
 * Where a send goes over the network, as opposed to what it says. Today that is the proxy;
 * client certificates join it later.
 *
 * <p>Like {@code CookieContext}, a call with none behaves as it always did, which is what
 * direct callers and tests get.
 */
public record NetworkConfig(ProxyConfig proxy) {

    public static final NetworkConfig NONE = new NetworkConfig(null);

    /** The proxy routing, or null when requests go direct. Fails on a proxy Ping cannot use. */
    public ProxyRouter router() {
        return proxy == null ? null : ProxyRouter.of(proxy);
    }

    /**
     * Applies the proxy to a client under construction.
     *
     * @return the router, so a caller can ask what a given URL will do
     */
    public ProxyRouter applyTo(HttpClient.Builder builder) {
        ProxyRouter router = router();
        if (router != null) {
            builder.proxy(router.selector());
        }
        return router;
    }
}
