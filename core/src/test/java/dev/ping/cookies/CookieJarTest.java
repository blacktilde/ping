package dev.ping.cookies;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieJarTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String S = "scope";

    private final CookieJar jar = new CookieJar();

    private static URI uri(String text) {
        return URI.create(text);
    }

    private void set(String url, String... setCookies) {
        jar.store(S, uri(url), List.of(setCookies), NOW);
    }

    private String cookie(String url) {
        return jar.header(S, uri(url), NOW);
    }

    @Test
    void aCookieIsSentBackToTheHostThatSetIt() {
        set("http://example.com/login", "sid=abc; Path=/");
        assertEquals("sid=abc", cookie("http://example.com/anything"));
        assertNull(cookie("http://other.com/anything"));
    }

    @Test
    void aCookieWithNoDomainIsHostOnly() {
        set("http://www.example.com/", "a=1");
        assertEquals("a=1", cookie("http://www.example.com/"));
        assertNull(cookie("http://api.example.com/"));
        assertNull(cookie("http://example.com/"));
    }

    @Test
    void aDomainAttributeSharesTheCookieWithSubdomainsButNotLookalikes() {
        set("http://www.example.com/", "a=1; Domain=.example.com");
        assertEquals("a=1", cookie("http://www.example.com/"));
        assertEquals("a=1", cookie("http://api.example.com/"));
        assertEquals("a=1", cookie("http://example.com/"));
        assertNull(cookie("http://notexample.com/"));
    }

    @Test
    void aDomainThatIsNotTheRequestsOwnIsRejected() {
        set("http://example.com/", "a=1; Domain=other.com");
        set("http://example.com/", "b=1; Domain=com");
        set("http://example.com/", "c=1; Domain=sub.example.com");
        assertEquals(List.of(), jar.list(S, NOW));
    }

    @Test
    void aDomainCannotWidenAnIpAddress() {
        set("http://127.0.0.1:8080/", "a=1; Domain=0.0.1");
        assertEquals(List.of(), jar.list(S, NOW));
        set("http://127.0.0.1:8080/", "b=2; Domain=127.0.0.1");
        assertEquals("b=2", cookie("http://127.0.0.1:8080/x"));
    }

    @Test
    void theDefaultPathIsTheDirectoryOfTheRequest() {
        set("http://example.com/a/b/login", "sid=1");
        assertEquals("sid=1", cookie("http://example.com/a/b/other"));
        assertEquals("sid=1", cookie("http://example.com/a/b"));
        assertNull(cookie("http://example.com/a"));
        assertNull(cookie("http://example.com/a/bc"));

        set("http://example.com/root", "top=1");
        assertEquals("top=1", cookie("http://example.com/elsewhere"), "a top-level file defaults to /");
    }

    @Test
    void pathMatchingStopsAtASegmentBoundary() {
        set("http://example.com/", "p=1; Path=/foo");
        assertEquals("p=1", cookie("http://example.com/foo"));
        assertEquals("p=1", cookie("http://example.com/foo/"));
        assertEquals("p=1", cookie("http://example.com/foo/bar"));
        assertNull(cookie("http://example.com/foobar"), "/foo must not match /foobar");
        assertNull(cookie("http://example.com/"));
    }

    @Test
    void secureCookiesTravelOnlyOverHttpsOrLoopback() {
        set("https://example.com/", "s=1; Secure");
        assertEquals("s=1", cookie("https://example.com/"));
        assertNull(cookie("http://example.com/"), "never over plain http");

        set("http://localhost:3000/", "dev=1; Secure");
        assertEquals("dev=1", cookie("http://localhost:3000/"), "loopback counts as secure for local work");
        set("http://127.0.0.1:3000/", "ip=1; Secure");
        assertEquals("ip=1", cookie("http://127.0.0.1:3000/"));
    }

    @Test
    void aSecureCookieIsNotAcceptedFromAnInsecureRemoteOrigin() {
        set("http://example.com/", "s=1; Secure");
        assertEquals(List.of(), jar.list(S, NOW));
    }

    @Test
    void maxAgeExpiresACookieAndZeroDeletesIt() {
        set("http://example.com/", "a=1; Max-Age=60");
        assertEquals("a=1", jar.header(S, uri("http://example.com/"), NOW + 59_000));
        assertNull(jar.header(S, uri("http://example.com/"), NOW + 61_000));

        set("http://example.com/", "b=1");
        assertEquals("b=1", cookie("http://example.com/"));
        set("http://example.com/", "b=; Max-Age=0");
        assertNull(cookie("http://example.com/"), "Max-Age=0 deletes the cookie");
    }

    @Test
    void maxAgeWinsOverExpires() {
        set("http://example.com/", "a=1; Expires=Wed, 21 Oct 1999 07:28:00 GMT; Max-Age=3600");
        assertEquals("a=1", cookie("http://example.com/"));
    }

    @Test
    void everyCommonExpiresFormatIsUnderstood() {
        for (String date : List.of(
                "Wed, 21 Oct 2099 07:28:00 GMT",
                "Wed, 21-Oct-2099 07:28:00 GMT",
                "Wednesday, 21-Oct-99 07:28:00 GMT",
                "Wed Oct 21 07:28:00 2099")) {
            CookieJar local = new CookieJar();
            local.store(S, uri("http://example.com/"), List.of("a=1; Expires=" + date), NOW);
            List<CookieJar.CookieView> listed = local.list(S, NOW);
            assertEquals(1, listed.size(), date);
            assertTrue(listed.get(0).expiresAt() != null, "a persistent cookie: " + date);
        }
    }

    @Test
    void aPastExpiresDeletesAndAnUnreadableOneMakesASessionCookie() {
        set("http://example.com/", "a=1");
        set("http://example.com/", "a=1; Expires=Wed, 21 Oct 2015 07:28:00 GMT");
        assertNull(cookie("http://example.com/"));

        set("http://example.com/", "b=1; Expires=not a date");
        assertEquals("b=1", cookie("http://example.com/"));
        assertNull(jar.list(S, NOW).get(0).expiresAt());
    }

    @Test
    void dateParsingDoesNotDependOnTheDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            set("http://example.com/", "a=1; Expires=Wed, 21-Oct-2099 07:28:00 GMT");
            assertTrue(jar.list(S, NOW).get(0).expiresAt() != null);
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void theSameNameDomainAndPathIsReplacedKeepingItsPlace() {
        jar.store(S, uri("http://example.com/"), List.of("a=1"), NOW);
        jar.store(S, uri("http://example.com/"), List.of("b=1"), NOW + 1000);
        jar.store(S, uri("http://example.com/"), List.of("a=2"), NOW + 2000);
        assertEquals("a=2; b=1", jar.header(S, uri("http://example.com/"), NOW + 3000));
    }

    @Test
    void longerPathsComeFirst() {
        set("http://example.com/", "root=1; Path=/", "deep=1; Path=/a/b", "mid=1; Path=/a");
        assertEquals("deep=1; mid=1; root=1", cookie("http://example.com/a/b/c"));
    }

    @Test
    void scopesDoNotSeeEachOthersCookies() {
        jar.store("dev", uri("http://example.com/"), List.of("sid=dev"), NOW);
        jar.store("prod", uri("http://example.com/"), List.of("sid=prod"), NOW);
        assertEquals("sid=dev", jar.header("dev", uri("http://example.com/"), NOW));
        assertEquals("sid=prod", jar.header("prod", uri("http://example.com/"), NOW));
        assertNull(jar.header("staging", uri("http://example.com/"), NOW));
    }

    @Test
    void listDescribesCookiesWithoutTheirValues() {
        set("https://example.com/app/x", "sid=super-secret-value; HttpOnly; Secure; SameSite=Lax; Max-Age=60");
        List<CookieJar.CookieView> listed = jar.list(S, NOW);
        assertEquals(1, listed.size());
        CookieJar.CookieView view = listed.get(0);
        assertEquals("sid", view.name());
        assertEquals("example.com", view.domain());
        assertEquals("/app", view.path());
        assertTrue(view.hostOnly() && view.secure() && view.httpOnly());
        assertEquals("Lax", view.sameSite());
        assertEquals(NOW + 60_000, view.expiresAt());
        assertFalse(view.toString().contains("super-secret-value"), "a value must never appear in a view");
        assertEquals(List.of("super-secret-value"), jar.values(S));
    }

    @Test
    void cookiesCanBeClearedByScopeDomainOrName() {
        set("http://a.example.com/", "x=1", "y=1");
        set("http://b.example.com/", "x=2");
        assertEquals(1, jar.clear(S, "a.example.com", "x"));
        assertEquals(2, jar.list(S, NOW).size());
        assertEquals(1, jar.clear(S, "a.example.com", null));
        assertEquals(1, jar.clear(S, null, "x"));
        assertEquals(0, jar.list(S, NOW).size());

        set("http://a.example.com/", "x=1");
        jar.store("other", uri("http://a.example.com/"), List.of("x=1"), NOW);
        jar.clearAll();
        assertEquals(0, jar.list(S, NOW).size() + jar.list("other", NOW).size());
    }

    @Test
    void malformedAndOversizedCookiesAreSkipped() {
        set("http://example.com/", "noequals", "=novalue-name", "  =x", "ok=1");
        assertEquals("ok=1", cookie("http://example.com/"));
        set("http://example.com/", "big=" + "x".repeat(CookieJar.MAX_COOKIE_BYTES));
        assertEquals("ok=1", cookie("http://example.com/"));
    }

    @Test
    void aDomainIsCappedAndTheOldestCookiesGoFirst() {
        for (int i = 0; i < CookieJar.MAX_PER_DOMAIN + 5; i++) {
            jar.store(S, uri("http://example.com/"), List.of("c" + i + "=1"), NOW + i);
        }
        List<CookieJar.CookieView> listed = jar.list(S, NOW + 1000);
        assertEquals(CookieJar.MAX_PER_DOMAIN, listed.size());
        assertTrue(listed.stream().noneMatch(c -> c.name().equals("c0")), "the oldest was evicted");
        assertTrue(listed.stream().anyMatch(c -> c.name().equals("c" + (CookieJar.MAX_PER_DOMAIN + 4))));
    }

    @Test
    void sameSiteIsParsedNotEnforced() {
        set("http://example.com/", "a=1; SameSite=Strict", "b=1; SameSite=None; Secure", "c=1; SameSite=bogus");
        assertEquals("a=1; c=1", cookie("http://example.com/"), "a Strict cookie is still sent: there is no cross-site context");
        assertEquals("Strict", jar.list(S, NOW).stream().filter(c -> c.name().equals("a")).findFirst().orElseThrow().sameSite());
        assertNull(jar.list(S, NOW).stream().filter(c -> c.name().equals("c")).findFirst().orElseThrow().sameSite());
    }
}
