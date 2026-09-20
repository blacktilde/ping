package dev.ping.http;

import dev.ping.rpc.RpcException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyRouterTest {

    private static ProxyRouter manual(String url, String... bypass) {
        return ProxyRouter.of(ProxyConfig.manual(url, null, null, List.of(bypass)));
    }

    private static ProxyRouter system(Map<String, String> env) {
        return ProxyRouter.of(ProxyConfig.system(env));
    }

    private static URI uri(String text) {
        return URI.create(text);
    }

    @Test
    void noneMeansNoRouter() {
        assertNull(ProxyRouter.of(ProxyConfig.NONE));
        assertNull(ProxyRouter.of(new ProxyConfig("none", "proxy:1", null, null, null, null)));
    }

    @Test
    void anUnknownModeIsRejected() {
        assertThrows(RpcException.class, () -> ProxyRouter.of(new ProxyConfig("pac", null, null, null, null, null)));
    }

    @Test
    void aBareHostAndPortIsAnHttpProxy() {
        ProxyRouter.Endpoint endpoint = manual("proxy.corp:3128").proxyFor(uri("http://example.com/"));
        assertEquals("proxy.corp", endpoint.host());
        assertEquals(3128, endpoint.port());
    }

    @Test
    void theAddressMayCarryPercentEncodedCredentials() {
        ProxyRouter.Endpoint endpoint =
                manual("http://al%40ice:p%3Ass@proxy.corp:8080").proxyFor(uri("http://example.com/"));
        assertEquals("al@ice", endpoint.username());
        assertEquals("p:ss", endpoint.password());
    }

    @Test
    void anExplicitUsernameOutranksTheAddress() {
        ProxyRouter router = ProxyRouter.of(ProxyConfig.manual("http://old:oldpw@proxy:80", "new", "newpw", null));
        ProxyRouter.Endpoint endpoint = router.proxyFor(uri("http://example.com/"));
        assertEquals("new", endpoint.username());
        assertEquals("newpw", endpoint.password());
    }

    @Test
    void socksAndHttpsProxiesAreRefusedRatherThanIgnored() {
        RpcException socks = assertThrows(RpcException.class, () -> manual("socks5://proxy:1080"));
        assertTrue(socks.getMessage().contains("SOCKS"), socks.getMessage());
        RpcException https = assertThrows(RpcException.class, () -> manual("https://proxy:443"));
        assertTrue(https.getMessage().contains("https://"), https.getMessage());
        assertThrows(RpcException.class, () -> manual(""));
        assertThrows(RpcException.class, () -> manual("http://"));
    }

    @Test
    void aRejectedAddressDoesNotEchoItsPassword() {
        RpcException e = assertThrows(RpcException.class, () -> manual("socks5://bob:hunter2@proxy:1080"));
        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
    }

    @Test
    void theConfigNeverPrintsItsPassword() {
        String text = ProxyConfig.manual("proxy:1", "bob", "hunter2", null).toString();
        assertFalse(text.contains("hunter2"), text);
    }

    @Test
    void bypassMatchesTheFormsNoProxyUses() {
        ProxyRouter router = manual("proxy:1", "internal.corp", ".legacy.net", "*.dev.io", "10.0.0.5", "api.x:8443");
        assertNull(router.proxyFor(uri("http://internal.corp/")));
        assertNull(router.proxyFor(uri("http://a.internal.corp/")));
        assertNull(router.proxyFor(uri("http://host.legacy.net/")));
        assertNull(router.proxyFor(uri("http://legacy.net/")));
        assertNull(router.proxyFor(uri("http://x.dev.io/")));
        assertNull(router.proxyFor(uri("http://10.0.0.5/")));
        assertNull(router.proxyFor(uri("https://api.x:8443/")));

        assertEquals("proxy", router.proxyFor(uri("http://api.x:9000/")).host());
        assertEquals("proxy", router.proxyFor(uri("http://notinternal.corp/")).host());
        assertEquals("proxy", router.proxyFor(uri("http://example.com/")).host());
    }

    @Test
    void anAsteriskBypassesEverything() {
        assertNull(manual("proxy:1", "*").proxyFor(uri("http://example.com/")));
    }

    @Test
    void loopbackGoesThroughTheProxyUnlessListed() {
        assertEquals("proxy", manual("proxy:1").proxyFor(uri("http://127.0.0.1:9/")).host());
        assertEquals("proxy", manual("proxy:1").proxyFor(uri("http://localhost/")).host());
        assertNull(manual("proxy:1", "localhost", "127.0.0.1").proxyFor(uri("http://localhost/")));
    }

    @Test
    void systemPicksTheVariableForTheScheme() {
        ProxyRouter router = system(Map.of(
                "HTTP_PROXY", "http://plain:1", "HTTPS_PROXY", "http://secure:2"));
        assertEquals("plain", router.proxyFor(uri("http://example.com/")).host());
        assertEquals("secure", router.proxyFor(uri("https://example.com/")).host());
    }

    @Test
    void systemFallsBackToAllProxyAndAcceptsLowerCase() {
        ProxyRouter router = system(Map.of("all_proxy", "fallback:9", "https_proxy", "secure:2"));
        assertEquals("fallback", router.proxyFor(uri("http://example.com/")).host());
        assertEquals("secure", router.proxyFor(uri("https://example.com/")).host());
    }

    @Test
    void systemTreatsAnEmptyVariableAsUnset() {
        ProxyRouter router = system(Map.of("HTTP_PROXY", "", "http_proxy", "lower:1"));
        assertEquals("lower", router.proxyFor(uri("http://example.com/")).host());
        assertNull(system(Map.of()).proxyFor(uri("http://example.com/")), "nothing set means direct");
    }

    @Test
    void systemHonoursNoProxy() {
        ProxyRouter router = system(Map.of("HTTP_PROXY", "proxy:1", "NO_PROXY", "localhost, .corp ,10.1.1.1"));
        assertNull(router.proxyFor(uri("http://localhost/")));
        assertNull(router.proxyFor(uri("http://git.corp/")));
        assertNull(router.proxyFor(uri("http://10.1.1.1/")));
        assertEquals("proxy", router.proxyFor(uri("http://example.com/")).host());
    }

    @Test
    void systemCredentialsComeFromTheUserinfo() {
        ProxyRouter.Endpoint endpoint =
                system(Map.of("HTTP_PROXY", "http://bob:pw@proxy:1")).proxyFor(uri("http://example.com/"));
        assertEquals("bob", endpoint.username());
        assertEquals("pw", endpoint.password());
    }

    @Test
    void aBadVariableFailsOnlyTheSchemeItServesAndNeverFallsBackToDirect() {
        ProxyRouter router = system(Map.of("HTTP_PROXY", "proxy:1", "HTTPS_PROXY", "socks5://s:1080"));
        assertEquals("proxy", router.proxyFor(uri("http://example.com/")).host());
        RpcException e = assertThrows(RpcException.class, () -> router.proxyFor(uri("https://example.com/")));
        assertTrue(e.getMessage().contains("HTTPS_PROXY"), e.getMessage());
    }

    @Test
    void theAuthorizationIsBasicForTheProxyThatWillBeUsed() {
        ProxyRouter router = ProxyRouter.of(ProxyConfig.manual("proxy.corp:8080", "bob", "pw", List.of("internal")));
        assertEquals("Basic Ym9iOnB3", router.authorizationFor(uri("http://example.com/")));
        assertNull(router.authorizationFor(uri("http://internal/")), "a bypassed host is not sent to the proxy");
        assertNull(manual("proxy.corp:8080").authorizationFor(uri("http://example.com/")));
    }
}
