package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import dev.ping.http.TlsFixtures;
import dev.ping.rpc.RpcServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code net.probe} as JSON: the request and the result cross Jackson here, which is also what the
 * native-image tracing agent needs to see for {@code ProbeResult}.
 */
class NetMethodsTest {

    private final ObjectMapper json = new ObjectMapper();

    private List<JsonNode> exchange(String... lines) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RpcServer rpc = new RpcServer(
                new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8)), out);
        NetMethods.registerOn(rpc);
        rpc.serve();
        return out.toString(StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return json.readTree(line);
                    } catch (Exception e) {
                        throw new AssertionError(line, e);
                    }
                })
                .filter(node -> node.has("id")).toList();
    }

    @Test
    void aProbeCrossesTheBoundaryAsJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            int port = server.getAddress().getPort();
            List<JsonNode> responses = exchange("""
                    {"jsonrpc":"2.0","id":1,"method":"net.probe","params":{"url":"http://127.0.0.1:%d/secret?k=hunter2","timeoutMs":3000}}
                    {"jsonrpc":"2.0","id":2,"method":"net.probe","params":{"url":"http://127.0.0.1:%d/","network":{"proxy":{"mode":"manual","url":"127.0.0.1:1"}}}}
                    {"jsonrpc":"2.0","id":3,"method":"net.probe","params":{"url":"ftp://x/"}}
                    {"jsonrpc":"2.0","id":4,"method":"net.probe","params":{}}"""
                    .formatted(port, port));
            JsonNode[] byId = new JsonNode[4];
            responses.forEach(node -> byId[node.path("id").asInt() - 1] = node);

            JsonNode ok = byId[0].path("result");
            assertEquals("http://127.0.0.1:" + port, ok.path("origin").asText());
            assertTrue(ok.path("connectMs").isNumber(), ok.toString());
            assertFalse(ok.has("tlsMs"), "a stage that did not run is absent");
            assertFalse(ok.toString().contains("hunter2"), "the query never comes back");

            // The proxy from `network` is honoured: it is the thing connected to, and it is dead.
            JsonNode viaProxy = byId[1].path("result");
            assertTrue(viaProxy.path("viaProxy").asBoolean(), viaProxy.toString());
            assertEquals("connect", viaProxy.path("failedStage").asText(), viaProxy.toString());

            assertEquals(-32602, byId[2].path("error").path("code").asInt());
            assertEquals(-32602, byId[3].path("error").path("code").asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aTlsProbeCarriesItsCertificateAsJson() throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(TlsFixtures.serverConfigurator(false));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        try {
            List<JsonNode> responses = exchange("""
                    {"jsonrpc":"2.0","id":1,"method":"net.probe","params":{"url":"https://127.0.0.1:%d/","verifyTls":false,"timeoutMs":3000}}"""
                    .formatted(server.getAddress().getPort()));

            JsonNode result = responses.get(0).path("result");
            assertTrue(result.path("tlsMs").isNumber(), result.toString());
            JsonNode certificate = result.path("certificate");
            assertEquals("CN=localhost", certificate.path("subject").asText(), result.toString());
            assertEquals("CN=Ping Test CA", certificate.path("issuer").asText());
            assertTrue(certificate.path("notBefore").isTextual());
            assertTrue(certificate.path("notAfter").isTextual());
            assertTrue(certificate.path("altNames").isArray());
            assertEquals(2, certificate.path("chainLength").asInt());
        } finally {
            server.stop(0);
        }
    }
}
