package dev.ping.http;

import com.sun.net.httpserver.HttpsServer;
import dev.ping.cookies.CookieContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An HTTPS request through a proxy is a {@code CONNECT} tunnel, and the proxy's password belongs in
 * the CONNECT, not in the request the origin sees inside the tunnel.
 */
class ConnectTunnelTest {

    private TunnelProxy proxy;
    private HttpsServer origin;
    private final AtomicReference<String> originSawProxyAuth = new AtomicReference<>("unset");

    @BeforeEach
    void start() throws Exception {
        origin = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.setHttpsConfigurator(TlsFixtures.serverConfigurator(false));
        origin.setExecutor(Executors.newCachedThreadPool());
        origin.createContext("/", exchange -> {
            originSawProxyAuth.set(String.valueOf(exchange.getRequestHeaders().getFirst("Proxy-Authorization")));
            byte[] payload = "tunnelled".getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        origin.start();

        proxy = new TunnelProxy();
    }

    @AfterEach
    void stop() throws IOException {
        proxy.close();
        origin.stop(0);
    }

    private ResponseData send(String user, String password) {
        NetworkConfig network = new NetworkConfig(ProxyConfig.manual(
                proxy.address(), user, password, null));
        return new HttpEngine().send(
                new RequestSpec.Builder("https://localhost:" + origin.getAddress().getPort() + "/x")
                        .verifyTls(false).build(),
                Map.of(), FileAccess.LOCAL, CookieContext.NONE, network);
    }

    @Test
    void anHttpsRequestIsTunnelledThroughTheProxy() {
        assertEquals("tunnelled", send(null, null).body().content());
        assertEquals("CONNECT localhost:" + origin.getAddress().getPort() + " HTTP/1.1", proxy.connects.get(0));
    }

    @Test
    void theProxyPasswordIsInTheConnectAndNeverInsideTheTunnel() {
        proxy.requiredAuth = "bob:s3cret";
        ResponseData response = send("bob", "s3cret");

        assertEquals("tunnelled", response.body().content());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("bob:s3cret".getBytes(StandardCharsets.UTF_8)),
                proxy.proxyAuth.get(0));
        assertEquals("null", originSawProxyAuth.get(), "the origin must never see the proxy's credentials");
    }

    @Test
    void aWrongProxyPasswordIsTheProxysRefusal() {
        proxy.requiredAuth = "bob:s3cret";
        ResponseData response = send("bob", "wrong");
        assertEquals(407, response.status(), "the proxy's refusal is the response");
        assertEquals("unset", originSawProxyAuth.get(), "the request never reached the origin");
    }
}
