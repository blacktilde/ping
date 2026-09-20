package dev.ping.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ping.cookies.CookieContext;
import dev.ping.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request through a real proxy socket. The origin used is a name that does not resolve, so a
 * response can only have come from the proxy; a proxy that was skipped would fail to connect.
 */
class ProxySendTest {

    /** What the proxy was asked. */
    private record Seen(String target, String proxyAuthorization, String authorization) {
    }

    private HttpServer proxy;
    private HttpServer origin;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final HttpEngine engine = new HttpEngine();

    /** Answers 407 until the request carries these credentials; empty means never ask. */
    private volatile String requireCredentials = "";
    private volatile int status = 200;
    private volatile String location;

    @BeforeEach
    void start() throws IOException {
        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.setExecutor(Executors.newCachedThreadPool());
        // A proxy receives the absolute form: "GET http://host/path HTTP/1.1".
        proxy.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
            seen.add(new Seen(exchange.getRequestURI().toString(), auth,
                    exchange.getRequestHeaders().getFirst("Authorization")));
            if (!requireCredentials.isEmpty()
                    && !("Basic " + Base64.getEncoder().encodeToString(
                            requireCredentials.getBytes(StandardCharsets.UTF_8))).equals(auth)) {
                exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"corp\"");
                reply(exchange, 407, "denied");
                return;
            }
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            if (status == 401) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"origin\"");
            }
            reply(exchange, status, "via-proxy");
        });
        proxy.start();

        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.setExecutor(Executors.newCachedThreadPool());
        origin.createContext("/", exchange -> reply(exchange, 200, "direct"));
        origin.start();
    }

    @AfterEach
    void stop() {
        proxy.stop(0);
        origin.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
    }

    private String proxyAddress() {
        return "127.0.0.1:" + proxy.getAddress().getPort();
    }

    private ResponseData send(RequestSpec spec, NetworkConfig network) {
        return engine.send(spec, Map.of(), FileAccess.LOCAL, CookieContext.NONE, network);
    }

    private NetworkConfig through(String user, String password, String... bypass) {
        return new NetworkConfig(ProxyConfig.manual(proxyAddress(), user, password, List.of(bypass)));
    }

    @Test
    void aRequestForAnUnreachableHostIsAnsweredByTheProxy() {
        ResponseData response = send(
                new RequestSpec.Builder("http://origin.invalid:8080/hello").query("x", "1").build(),
                through(null, null));

        assertEquals(200, response.status());
        assertEquals("via-proxy", response.body().content());
        assertEquals(1, seen.size());
        assertEquals("http://origin.invalid:8080/hello?x=1", seen.get(0).target());
    }

    @Test
    void dnsIsNotMeasuredForAnOriginTheProxyResolves() {
        ResponseData response = send(new RequestSpec.Builder("http://origin.invalid/").build(), through(null, null));
        assertNull(response.timing().dnsMs());
    }

    @Test
    void theConfiguredCredentialsAreSentToTheProxy() {
        requireCredentials = "bob:s3cret";
        ResponseData response = send(
                new RequestSpec.Builder("http://origin.invalid/").build(), through("bob", "s3cret"));

        assertEquals(200, response.status());
        assertEquals(1, seen.size());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("bob:s3cret".getBytes(StandardCharsets.UTF_8)),
                seen.get(0).proxyAuthorization());
    }

    @Test
    void wrongCredentialsLeaveTheProxysChallengeAsTheResponse() {
        requireCredentials = "bob:s3cret";
        ResponseData response = send(new RequestSpec.Builder("http://origin.invalid/").build(), through("bob", "nope"));
        assertEquals(407, response.status());
        assertEquals(1, seen.size(), "no retry loop");
    }

    @Test
    void withoutCredentialsTheChallengeIsTheResponse() {
        requireCredentials = "bob:s3cret";
        ResponseData response = send(new RequestSpec.Builder("http://origin.invalid/").build(), through(null, null));
        assertEquals(407, response.status());
    }

    @Test
    void anOriginsUnauthorizedResponseStaysVisibleAndIsNotAnsweredWithTheProxyPassword() {
        // With a JDK authenticator installed this request would fail outright; the 401 must
        // come back as a response, and the origin's challenge gets nothing.
        status = 401;
        ResponseData response = send(
                new RequestSpec.Builder("http://origin.invalid/").build(), through("bob", "s3cret"));

        assertEquals(401, response.status());
        assertEquals(1, seen.size(), "no retry: nothing was offered for the origin's challenge");
        assertNull(seen.get(0).authorization());
    }

    @Test
    void aProxyAuthorizationTheUserSetWins() {
        ResponseData response = send(
                new RequestSpec.Builder("http://origin.invalid/").header("Proxy-Authorization", "Basic dXNlcjpwdw==").build(),
                through("bob", "s3cret"));
        assertEquals(200, response.status());
        assertEquals("Basic dXNlcjpwdw==", seen.get(0).proxyAuthorization());
    }

    @Test
    void aBypassedHostGoesDirect() {
        ResponseData response = send(
                new RequestSpec.Builder("http://127.0.0.1:" + origin.getAddress().getPort() + "/").build(),
                through(null, null, "127.0.0.1"));

        assertEquals("direct", response.body().content());
        assertTrue(seen.isEmpty(), "the proxy must not have seen a bypassed request");
    }

    @Test
    void loopbackIsProxiedWhenNotListed() {
        ResponseData response = send(
                new RequestSpec.Builder("http://127.0.0.1:" + origin.getAddress().getPort() + "/").build(),
                through(null, null));

        assertEquals("via-proxy", response.body().content());
        assertEquals(1, seen.size());
    }

    @Test
    void aRedirectHopIsProxiedToo() {
        status = 302;
        location = "http://second.invalid/next";
        ResponseData response = send(
                new RequestSpec.Builder("http://first.invalid/start").redirects("normal").build(),
                through(null, null));

        assertEquals(302, response.status(), "the proxy answers every hop with the same 302 here");
        assertTrue(seen.size() > 1, "the hop went to the proxy as well: " + seen);
        assertEquals("http://second.invalid/next", seen.get(1).target());
    }

    @Test
    void systemModeReadsTheSuppliedEnvironment() {
        NetworkConfig network = new NetworkConfig(ProxyConfig.system(Map.of("HTTP_PROXY", "http://" + proxyAddress())));
        assertEquals("via-proxy", send(new RequestSpec.Builder("http://origin.invalid/").build(), network).body().content());
    }

    @Test
    void systemModeWithNothingSetGoesDirect() {
        NetworkConfig network = new NetworkConfig(ProxyConfig.system(Map.of()));
        ResponseData response = send(
                new RequestSpec.Builder("http://127.0.0.1:" + origin.getAddress().getPort() + "/").build(), network);
        assertEquals("direct", response.body().content());
    }

    @Test
    void anUnusableProxyIsReportedNotSkipped() {
        NetworkConfig network = new NetworkConfig(ProxyConfig.manual("socks5://proxy:1080", null, null, null));
        RpcException e = assertThrows(RpcException.class,
                () -> send(new RequestSpec.Builder("http://127.0.0.1:" + origin.getAddress().getPort() + "/").build(),
                        network));
        assertTrue(e.getMessage().contains("SOCKS"), e.getMessage());
    }

    @Test
    void aDeadProxyFailsTheRequest() throws IOException {
        int freePort;
        try (var socket = new java.net.ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        NetworkConfig network = new NetworkConfig(
                ProxyConfig.manual("127.0.0.1:" + freePort, null, null, null));
        assertThrows(RpcException.class,
                () -> send(new RequestSpec.Builder("http://origin.invalid/").timeoutMs(3000).build(), network));
    }

    // --- protocol version -------------------------------------------------------------------

    private String upgradeHeaderSeenBy(String version) {
        var upgrade = new java.util.concurrent.atomic.AtomicReference<String>();
        origin.createContext("/version", exchange -> {
            upgrade.set(String.valueOf(exchange.getRequestHeaders().getFirst("Upgrade")));
            reply(exchange, 200, "ok");
        });
        RequestSpec.Builder builder = new RequestSpec.Builder(
                "http://127.0.0.1:" + origin.getAddress().getPort() + "/version");
        if (version != null) {
            builder.httpVersion(version);
        }
        engine.send(builder.build());
        return upgrade.get();
    }

    @Test
    void pinningHttp11NeverAsksToUpgrade() {
        assertEquals("null", upgradeHeaderSeenBy("1.1"));
    }

    @Test
    void preferringHttp2AsksToUpgradeOverPlainText() {
        assertEquals("h2c", upgradeHeaderSeenBy("2"));
    }

    @Test
    void anUnknownVersionIsBadInput() {
        RpcException e = assertThrows(RpcException.class, () -> upgradeHeaderSeenBy("3"));
        assertTrue(e.getMessage().contains("HTTP version"), e.getMessage());
    }
}
