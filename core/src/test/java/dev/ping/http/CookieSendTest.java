package dev.ping.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ping.cookies.CookieContext;
import dev.ping.cookies.CookieJar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A session over a real socket: what one response sets, the next request carries. */
class CookieSendTest {

    private HttpServer server;
    private HttpEngine engine;
    private String baseUrl;
    private int port;
    private final CookieJar jar = new CookieJar();
    private final AtomicReference<String> seen = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        engine = new HttpEngine();

        // /login sets a session cookie; /me says which Cookie header it received.
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "sid=abc123; Path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "theme=dark; Path=/");
            reply(exchange, 200, "ok");
        });
        server.createContext("/me", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Cookie"));
            reply(exchange, 200, String.valueOf(seen.get()));
        });
        // A redirect that also sets a cookie: the cookie has to be stored before the hop is followed.
        server.createContext("/bounce", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "hop=1; Path=/");
            exchange.getResponseHeaders().add("Location", "/me");
            reply(exchange, 302, "");
        });
        server.createContext("/leave", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "hostonly=1; Path=/");
            exchange.getResponseHeaders().add("Location", "http://localhost:" + port + "/me");
            reply(exchange, 302, "");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        }
    }

    private RequestSpec.Builder get(String path) {
        return new RequestSpec.Builder(baseUrl + path);
    }

    private void send(RequestSpec spec, String scope) {
        engine.send(spec, Map.of(), FileAccess.LOCAL, new CookieContext(jar, scope));
    }

    @Test
    void aLoginResponseSetsCookiesAndTheNextRequestCarriesThem() {
        send(get("/login").build(), "dev");
        send(get("/me").build(), "dev");
        assertEquals("sid=abc123; theme=dark", seen.get());
    }

    @Test
    void aRedirectResponsesCookiesAreSentOnTheNextHop() {
        send(get("/bounce").redirects("normal").build(), "dev");
        assertEquals("hop=1", seen.get(), "the 302's Set-Cookie must reach /me");
    }

    @Test
    void aHostOnlyCookieIsNotLeakedToADifferentHostByARedirect() {
        send(get("/leave").redirects("normal").build(), "dev");
        assertNull(seen.get(), "localhost is another host from 127.0.0.1: " + seen.get());
    }

    @Test
    void anExplicitCookieHeaderReplacesTheJarsForThatRequest() {
        send(get("/login").build(), "dev");
        send(get("/me").header("Cookie", "manual=1").build(), "dev");
        assertEquals("manual=1", seen.get());
    }

    @Test
    void aDisabledCookieHeaderDoesNotSuppressTheJar() {
        send(get("/login").build(), "dev");
        send(get("/me").header("Cookie", "manual=1", false).build(), "dev");
        assertEquals("sid=abc123; theme=dark", seen.get());
    }

    @Test
    void optingOutNeitherSendsNorStores() {
        send(get("/login").cookies(false).build(), "dev");
        assertEquals(List.of(), jar.list("dev", System.currentTimeMillis()), "nothing stored");

        send(get("/login").build(), "dev");
        send(get("/me").cookies(false).build(), "dev");
        assertNull(seen.get(), "nothing sent");
        send(get("/me").build(), "dev");
        assertEquals("sid=abc123; theme=dark", seen.get(), "the jar is untouched for other requests");
    }

    @Test
    void aSecondScopeDoesNotSeeTheFirstsCookies() {
        send(get("/login").build(), "dev");
        send(get("/me").build(), "prod");
        assertNull(seen.get());
        send(get("/me").build(), "dev");
        assertEquals("sid=abc123; theme=dark", seen.get());
    }

    @Test
    void withNoContextTheJarIsNeitherReadNorWritten() {
        engine.send(get("/login").build());
        engine.send(get("/me").build());
        assertNull(seen.get());
        assertEquals(List.of(), jar.list("dev", System.currentTimeMillis()));
    }

    @Test
    void aFailedExchangeStoresNothing() {
        server.stop(0);
        assertThrows(dev.ping.rpc.RpcException.class, () -> send(get("/login").build(), "dev"));
        assertEquals(List.of(), jar.list("dev", System.currentTimeMillis()));
    }

    @Test
    void theJarKeepsCookiesTheServerSetEvenWhenTheResponseIsAnError() throws Exception {
        server.createContext("/fail", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "csrf=xyz; Path=/");
            reply(exchange, 500, "boom");
        });
        send(get("/fail").build(), "dev");
        send(get("/me").build(), "dev");
        assertEquals("csrf=xyz", seen.get());
        assertTrue(true);
    }
}
