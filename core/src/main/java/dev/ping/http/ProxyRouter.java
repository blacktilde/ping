package dev.ping.http;

import dev.ping.rpc.RpcException;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides, per URL, whether a request goes through a proxy and which one.
 *
 * <p>The JDK's own selectors are not used because none of them behaves the way a user
 * expects. {@code ProxySelector.of} has no bypass list at all, and the default selector
 * bypasses loopback unconditionally, so a proxy could never be pointed at a local server, and
 * the environment's {@code NO_PROXY} would be ignored.
 *
 * <p>Only plain HTTP proxies are supported: {@code java.net.http} cannot tunnel through a
 * SOCKS proxy or speak TLS to the proxy itself. Both are refused with a message rather than
 * ignored, since ignoring one sends a request somewhere the user did not intend.
 *
 * <p>Credentials go to the proxy only, and never in answer to a server's 401.
 */
public final class ProxyRouter {

    /** One proxy to connect through, with the credentials it wants, if any. */
    public record Endpoint(String host, int port, String username, String password) {

        boolean hasCredentials() {
            return username != null && !username.isEmpty();
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    /** What one scheme resolves to: a proxy, direct, or an address that cannot be used. */
    private record Route(Endpoint endpoint, String problem) {
        static final Route DIRECT = new Route(null, null);
    }

    private final Route http;
    private final Route https;
    private final List<String> bypass;

    private ProxyRouter(Route http, Route https, List<String> bypass) {
        this.http = http;
        this.https = https;
        this.bypass = bypass;
    }

    /** @return null when the config means no proxy */
    public static ProxyRouter of(ProxyConfig config) {
        String mode = config.mode() == null ? "none" : config.mode().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "none" -> null;
            case "manual" -> manual(config);
            case "system" -> system(config.env() == null ? Map.of() : config.env());
            default -> throw RpcException.invalidParams(
                    "Unknown proxy mode: " + config.mode() + " (use none, system or manual)");
        };
    }

    private static ProxyRouter manual(ProxyConfig config) {
        if (config.url() == null || config.url().isBlank()) {
            throw RpcException.invalidParams("A manual proxy needs an address");
        }
        Endpoint parsed = parse(config.url(), "The proxy address");
        String username = config.username() != null && !config.username().isEmpty()
                ? config.username() : parsed.username();
        String password = config.username() != null && !config.username().isEmpty()
                ? config.password() : parsed.password();
        Route route = new Route(new Endpoint(parsed.host(), parsed.port(), username, password), null);
        return new ProxyRouter(route, route, tokens(config.bypass()));
    }

    private static ProxyRouter system(Map<String, String> env) {
        String all = variable(env, "ALL_PROXY");
        Route http = route(variable(env, "HTTP_PROXY"), all, "HTTP_PROXY");
        Route https = route(variable(env, "HTTPS_PROXY"), all, "HTTPS_PROXY");
        String noProxy = variable(env, "NO_PROXY");
        return new ProxyRouter(http, https,
                noProxy == null ? List.of() : tokens(List.of(noProxy.split("[,\\s]+"))));
    }

    private static Route route(String own, String all, String name) {
        String value = own != null ? own : all;
        if (value == null) {
            return Route.DIRECT;
        }
        try {
            return new Route(parse(value, own != null ? name : "ALL_PROXY"), null);
        } catch (RpcException e) {
            // Held until a request needs this scheme, so a bad HTTPS_PROXY does not break
            // plain HTTP requests, and never falls back to going direct.
            return new Route(null, e.getMessage());
        }
    }

    /** The first set, non-empty value of a variable in upper or lower case. */
    private static String variable(Map<String, String> env, String name) {
        for (String key : List.of(name, name.toLowerCase(Locale.ROOT))) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    static Endpoint parse(String raw, String label) {
        String text = raw.trim();
        URI uri;
        try {
            uri = URI.create(text.contains("://") ? text : "http://" + text);
        } catch (IllegalArgumentException e) {
            // Deliberately not echoed: the address may carry a password.
            throw RpcException.invalidParams(label + " is not a valid address");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.startsWith("socks")) {
            throw RpcException.invalidParams(label + " is a SOCKS proxy, which is not supported; "
                    + "use an HTTP proxy");
        }
        if (scheme.equals("https")) {
            throw RpcException.invalidParams(label + " is an https:// proxy. Talking TLS to the proxy "
                    + "itself is not supported; use an http:// address (HTTPS requests are still "
                    + "tunnelled through it)");
        }
        if (!scheme.equals("http") || uri.getHost() == null) {
            throw RpcException.invalidParams(label + " is not a valid http:// address");
        }
        String user = null;
        String password = null;
        String info = uri.getUserInfo(); // already percent-decoded
        if (info != null) {
            int colon = info.indexOf(':');
            user = colon < 0 ? info : info.substring(0, colon);
            password = colon < 0 ? null : info.substring(colon + 1);
        }
        return new Endpoint(uri.getHost(), uri.getPort() < 0 ? 80 : uri.getPort(), user, password);
    }

    /**
     * The proxy this URL goes through, or null for direct.
     *
     * @throws RpcException when the address that applies cannot be used
     */
    public Endpoint proxyFor(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        Route route = scheme.equals("https") ? https : scheme.equals("http") ? http : Route.DIRECT;
        if (route.problem() != null) {
            throw RpcException.invalidParams(route.problem());
        }
        if (route.endpoint() == null || bypassed(uri)) {
            return null;
        }
        return route.endpoint();
    }

    /** The selector handed to the client; it fails closed rather than going direct on a bad route. */
    public ProxySelector selector() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                Endpoint endpoint = proxyFor(uri);
                if (endpoint == null) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(new Proxy(Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved(endpoint.host(), endpoint.port())));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) {
                // The engine reports the failure; there is nothing to fail over to.
            }
        };
    }

    /**
     * The {@code Proxy-Authorization} value for a URL's proxy, or null when it goes direct or
     * the proxy wants no credentials.
     *
     * <p>Sent up front rather than in answer to a 407 because the JDK's authenticator hook
     * cannot be scoped to the proxy: with one installed, a plain 401 from the server being called
     * fails the request ("No credentials provided") instead of being returned. The JDK does not
     * forward a {@code Proxy-*} header into a tunnel to the origin, so an HTTPS request's
     * password reaches the proxy in the CONNECT and nobody else.
     */
    public String authorizationFor(URI uri) {
        Endpoint endpoint = proxyFor(uri);
        if (endpoint == null || !endpoint.hasCredentials()) {
            return null;
        }
        String password = endpoint.password() == null ? "" : endpoint.password();
        return "Basic " + Base64.getEncoder().encodeToString(
                (endpoint.username() + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    // --- bypass -------------------------------------------------------------------------------

    private static List<String> tokens(List<String> raw) {
        List<String> tokens = new ArrayList<>();
        if (raw != null) {
            for (String token : raw) {
                String trimmed = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    tokens.add(trimmed);
                }
            }
        }
        return tokens;
    }

    /**
     * {@code *} matches everything; {@code example.com}, {@code .example.com} and
     * {@code *.example.com} match the host and its subdomains; {@code host:port} matches only
     * that port. Loopback is bypassed only when listed, as {@code localhost} or an address.
     * CIDR ranges are not understood.
     */
    private boolean bypassed(URI uri) {
        String host = plain(uri.getHost());
        if (host == null) {
            return false;
        }
        int port = uri.getPort() >= 0 ? uri.getPort()
                : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        for (String token : bypass) {
            if (token.equals("*")) {
                return true;
            }
            String pattern = token;
            int colon = pattern.lastIndexOf(':');
            if (colon > 0 && pattern.indexOf(':') == colon && digits(pattern.substring(colon + 1))) {
                if (Integer.parseInt(pattern.substring(colon + 1)) != port) {
                    continue;
                }
                pattern = pattern.substring(0, colon);
            }
            pattern = plain(pattern.startsWith("*.") ? pattern.substring(1) : pattern);
            if (pattern.startsWith(".")) {
                if (host.endsWith(pattern) || host.equals(pattern.substring(1))) {
                    return true;
                }
            } else if (host.equals(pattern) || host.endsWith("." + pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean digits(String value) {
        return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }

    /** Lower-case, without the brackets of an IPv6 literal. */
    private static String plain(String host) {
        if (host == null) {
            return null;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.startsWith("[") && lower.endsWith("]") ? lower.substring(1, lower.length() - 1) : lower;
    }
}
