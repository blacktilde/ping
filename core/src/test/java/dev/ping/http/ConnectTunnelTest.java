package dev.ping.http;

import com.sun.net.httpserver.HttpsServer;
import dev.ping.cookies.CookieContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An HTTPS request through a proxy is a {@code CONNECT} tunnel, and the proxy's password belongs in
 * the CONNECT, not in the request the origin sees inside the tunnel.
 */
class ConnectTunnelTest {

    private ServerSocket proxy;
    private HttpsServer origin;
    private final List<String> connects = new CopyOnWriteArrayList<>();
    private final List<String> proxyAuth = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> originSawProxyAuth = new AtomicReference<>("unset");
    private volatile String requiredAuth = "";

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

        proxy = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!proxy.isClosed()) {
                try {
                    Socket client = proxy.accept();
                    Thread.ofVirtual().start(() -> serve(client));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        proxy.close();
        origin.stop(0);
    }

    /** A minimal CONNECT proxy: reads the request head, checks the credentials, then pipes bytes. */
    private void serve(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            StringBuilder head = new StringBuilder();
            int previous = 0;
            while (!head.toString().endsWith("\r\n\r\n")) {
                int next = in.read();
                if (next < 0) {
                    return;
                }
                head.append((char) next);
                previous = next;
            }
            String[] lines = head.toString().split("\r\n");
            connects.add(lines[0]);
            String auth = "-";
            for (String line : lines) {
                if (line.toLowerCase().startsWith("proxy-authorization:")) {
                    auth = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            proxyAuth.add(auth);
            OutputStream out = client.getOutputStream();
            String expected = requiredAuth.isEmpty() ? null
                    : "Basic " + Base64.getEncoder().encodeToString(requiredAuth.getBytes(StandardCharsets.UTF_8));
            if (expected != null && !expected.equals(auth)) {
                out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                        + "Proxy-Authenticate: Basic realm=\"corp\"\r\nContent-Length: 0\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            String[] target = lines[0].split(" ")[1].split(":");
            try (Socket upstream = new Socket(target[0], Integer.parseInt(target[1]))) {
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread pump = Thread.ofVirtual().start(() -> copy(upstream, client));
                copy(client, upstream);
                pump.join();
            }
        } catch (IOException | InterruptedException e) {
            // A closed tunnel ends the exchange; the assertions report what mattered.
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
            to.shutdownOutput();
        } catch (IOException e) {
            // The other side hung up.
        }
    }

    private ResponseData send(String user, String password) {
        NetworkConfig network = new NetworkConfig(ProxyConfig.manual(
                "127.0.0.1:" + proxy.getLocalPort(), user, password, null));
        return new HttpEngine().send(
                new RequestSpec.Builder("https://localhost:" + origin.getAddress().getPort() + "/x")
                        .verifyTls(false).build(),
                Map.of(), FileAccess.LOCAL, CookieContext.NONE, network);
    }

    @Test
    void anHttpsRequestIsTunnelledThroughTheProxy() {
        assertEquals("tunnelled", send(null, null).body().content());
        assertEquals("CONNECT localhost:" + origin.getAddress().getPort() + " HTTP/1.1", connects.get(0));
    }

    @Test
    void theProxyPasswordIsInTheConnectAndNeverInsideTheTunnel() {
        requiredAuth = "bob:s3cret";
        ResponseData response = send("bob", "s3cret");

        assertEquals("tunnelled", response.body().content());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("bob:s3cret".getBytes(StandardCharsets.UTF_8)),
                proxyAuth.get(0));
        assertEquals("null", originSawProxyAuth.get(), "the origin must never see the proxy's credentials");
    }

    @Test
    void aWrongProxyPasswordIsTheProxysRefusal() {
        requiredAuth = "bob:s3cret";
        ResponseData response = send("bob", "wrong");
        assertEquals(407, response.status(), "the proxy's refusal is the response");
        assertEquals("unset", originSawProxyAuth.get(), "the request never reached the origin");
    }
}
