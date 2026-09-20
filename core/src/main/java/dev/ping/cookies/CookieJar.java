package dev.ping.cookies;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A cookie jar following RFC 6265, kept in memory and scoped by an opaque key.
 *
 * <p>Hand-rolled rather than {@code java.net.CookieManager} because the engine follows redirects
 * itself and builds a client per request, because an explicit {@code Cookie} header has to be
 * able to replace the jar's, and because the JDK's matching is looser than the RFC's (path
 * matching is a plain prefix test, and there is no host-only flag to show). It also needs no
 * reflection, which suits a native image.
 *
 * <p>Cookie values are credentials. {@link #list} therefore returns metadata only, and nothing
 * here writes to disk: a jar lives as long as the process.
 *
 * <p>{@code SameSite} is stored and reported but not enforced: a REST client has no cross-site
 * context to compare against.
 */
public final class CookieJar {

    static final int MAX_COOKIE_BYTES = 4096;
    static final int MAX_PER_DOMAIN = 50;
    static final int MAX_TOTAL = 3000;

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** The date formats cookies use in the wild, tried in order; all parsed as English GMT. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            format("EEE, dd-MMM-yyyy HH:mm:ss zzz"),
            format("EEEE, dd-MMM-yy HH:mm:ss zzz"),
            format("EEE, dd MMM yyyy HH:mm:ss zzz"),
            format("EEE MMM d HH:mm:ss yyyy"));

    private static DateTimeFormatter format(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(ZoneOffset.UTC);
    }

    /** One stored cookie. The value stays inside the core. */
    record StoredCookie(
            String name, String value, String domain, boolean hostOnly, String path,
            boolean secure, boolean httpOnly, String sameSite, Long expiresAt, long createdAt) {

        boolean expired(long now) {
            return expiresAt != null && expiresAt <= now;
        }

        boolean sameKey(StoredCookie other) {
            return name.equals(other.name) && domain.equals(other.domain) && path.equals(other.path);
        }
    }

    /** What the UI may show about a cookie: everything but its value. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CookieView(
            String name, String domain, String path, boolean hostOnly, boolean secure,
            boolean httpOnly, String sameSite, Long expiresAt) {
    }

    private final Map<String, List<StoredCookie>> scopes = new HashMap<>();

    // --- storing -------------------------------------------------------------------------------

    /** Applies every {@code Set-Cookie} value a response carried. Unusable ones are skipped. */
    public synchronized void store(String scope, URI uri, List<String> setCookies, long now) {
        if (uri == null || uri.getHost() == null || setCookies == null) {
            return;
        }
        List<StoredCookie> jar = scopes.computeIfAbsent(scope, key -> new ArrayList<>());
        for (String header : setCookies) {
            StoredCookie parsed = parse(header, uri, now);
            if (parsed == null) {
                continue;
            }
            StoredCookie previous = jar.stream().filter(existing -> existing.sameKey(parsed)).findFirst().orElse(null);
            jar.removeIf(existing -> existing.sameKey(parsed));
            if (!parsed.expired(now)) {
                // A replaced cookie keeps its original creation time, so ordering stays stable.
                jar.add(previous == null ? parsed : new StoredCookie(parsed.name(), parsed.value(),
                        parsed.domain(), parsed.hostOnly(), parsed.path(), parsed.secure(),
                        parsed.httpOnly(), parsed.sameSite(), parsed.expiresAt(), previous.createdAt()));
            }
        }
        jar.removeIf(cookie -> cookie.expired(now));
        enforceLimits(jar);
    }

    private static StoredCookie parse(String header, URI uri, long now) {
        if (header == null) {
            return null;
        }
        String[] parts = header.split(";");
        int equals = parts[0].indexOf('=');
        if (equals <= 0) {
            return null;
        }
        String name = parts[0].substring(0, equals).trim();
        String value = parts[0].substring(equals + 1).trim();
        if (name.isEmpty() || name.length() + value.length() > MAX_COOKIE_BYTES) {
            return null;
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String domainAttribute = null;
        String pathAttribute = null;
        Long maxAgeExpiry = null;
        Long dateExpiry = null;
        boolean secure = false;
        boolean httpOnly = false;
        String sameSite = null;

        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i].trim();
            int at = attribute.indexOf('=');
            String key = (at < 0 ? attribute : attribute.substring(0, at)).trim().toLowerCase(Locale.ROOT);
            String attributeValue = at < 0 ? "" : attribute.substring(at + 1).trim();
            switch (key) {
                case "domain" -> domainAttribute = attributeValue.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT);
                case "path" -> pathAttribute = attributeValue;
                case "max-age" -> {
                    try {
                        long seconds = Long.parseLong(attributeValue);
                        maxAgeExpiry = seconds <= 0 ? now - 1 : now + seconds * 1000;
                    } catch (NumberFormatException e) {
                        // Ignored, as a browser does.
                    }
                }
                case "expires" -> dateExpiry = parseDate(attributeValue);
                case "secure" -> secure = true;
                case "httponly" -> httpOnly = true;
                case "samesite" -> sameSite = switch (attributeValue.toLowerCase(Locale.ROOT)) {
                    case "lax" -> "Lax";
                    case "strict" -> "Strict";
                    case "none" -> "None";
                    default -> null;
                };
                default -> { }
            }
        }

        // A Secure cookie is only accepted from a secure origin (https, or loopback for local work).
        if (secure && !secureContext(uri)) {
            return null;
        }

        String domain = host;
        boolean hostOnly = true;
        if (domainAttribute != null && !domainAttribute.isEmpty()) {
            if (isIp(host)) {
                if (!domainAttribute.equals(host)) {
                    return null; // A domain cannot widen an IP address.
                }
            } else {
                if (!domainMatches(host, domainAttribute)
                        || (domainAttribute.indexOf('.') < 0 && !domainAttribute.equals(host))) {
                    return null; // Not the request's domain, or a bare top-level label.
                }
                domain = domainAttribute;
                hostOnly = false;
            }
        }

        String path = pathAttribute != null && pathAttribute.startsWith("/") ? pathAttribute : defaultPath(uri);
        Long expiresAt = maxAgeExpiry != null ? maxAgeExpiry : dateExpiry;
        return new StoredCookie(name, value, domain, hostOnly, path, secure, httpOnly, sameSite, expiresAt, now);
    }

    private static Long parseDate(String text) {
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return ZonedDateTime.parse(text, formatter).toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                // Try the next format.
            }
        }
        return null; // An unreadable date makes a session cookie, as browsers do.
    }

    private static String defaultPath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            return "/";
        }
        int last = path.lastIndexOf('/');
        return last <= 0 ? "/" : path.substring(0, last);
    }

    private void enforceLimits(List<StoredCookie> jar) {
        for (String domain : jar.stream().map(StoredCookie::domain).distinct().toList()) {
            List<StoredCookie> ofDomain = jar.stream().filter(c -> c.domain().equals(domain)).toList();
            if (ofDomain.size() > MAX_PER_DOMAIN) {
                evictOldest(jar, ofDomain, ofDomain.size() - MAX_PER_DOMAIN);
            }
        }
        int total = scopes.values().stream().mapToInt(List::size).sum();
        if (total > MAX_TOTAL) {
            evictOldest(jar, new ArrayList<>(jar), Math.min(jar.size(), total - MAX_TOTAL));
        }
    }

    private static void evictOldest(List<StoredCookie> jar, List<StoredCookie> candidates, int count) {
        candidates.stream()
                .sorted(Comparator.comparingLong(StoredCookie::createdAt))
                .limit(count)
                .toList()
                .forEach(jar::remove);
    }

    // --- sending -------------------------------------------------------------------------------

    /**
     * The {@code Cookie} header value for a request, or null when nothing applies. Longer paths
     * come first, then older cookies, as RFC 6265 asks.
     */
    public synchronized String header(String scope, URI uri, long now) {
        List<StoredCookie> jar = scopes.get(scope);
        if (jar == null || uri == null || uri.getHost() == null) {
            return null;
        }
        jar.removeIf(cookie -> cookie.expired(now));

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        boolean secureContext = secureContext(uri);

        List<StoredCookie> matching = jar.stream()
                .filter(cookie -> cookie.hostOnly() ? host.equals(cookie.domain()) : domainMatches(host, cookie.domain()))
                .filter(cookie -> pathMatches(path, cookie.path()))
                .filter(cookie -> !cookie.secure() || secureContext)
                .sorted(Comparator.comparingInt((StoredCookie c) -> c.path().length()).reversed()
                        .thenComparingLong(StoredCookie::createdAt))
                .toList();
        if (matching.isEmpty()) {
            return null;
        }
        return String.join("; ", matching.stream().map(c -> c.name() + "=" + c.value()).toList());
    }

    // --- inspecting and clearing -------------------------------------------------------------------

    /** Every live cookie in a scope, without its value. */
    public synchronized List<CookieView> list(String scope, long now) {
        List<StoredCookie> jar = scopes.get(scope);
        if (jar == null) {
            return List.of();
        }
        jar.removeIf(cookie -> cookie.expired(now));
        return jar.stream()
                .sorted(Comparator.comparing(StoredCookie::domain).thenComparing(StoredCookie::name))
                .map(c -> new CookieView(c.name(), c.domain(), c.path(), c.hostOnly(), c.secure(),
                        c.httpOnly(), c.sameSite(), c.expiresAt()))
                .toList();
    }

    /**
     * Removes cookies from a scope.
     *
     * @param domain only this domain, or null for all
     * @param name   only this name (within the domain), or null for all
     * @return how many were removed
     */
    public synchronized int clear(String scope, String domain, String name) {
        List<StoredCookie> jar = scopes.get(scope);
        if (jar == null) {
            return 0;
        }
        int before = jar.size();
        jar.removeIf(cookie -> (domain == null || cookie.domain().equalsIgnoreCase(domain))
                && (name == null || cookie.name().equals(name)));
        return before - jar.size();
    }

    public synchronized void clearAll() {
        scopes.clear();
    }

    /**
     * Cookie values in a scope, for masking them out of a report. Never returned to a caller
     * outside the core.
     */
    public synchronized List<String> values(String scope) {
        List<StoredCookie> jar = scopes.get(scope);
        return jar == null ? List.of() : jar.stream().map(StoredCookie::value).toList();
    }

    // --- matching rules ------------------------------------------------------------------------------

    static boolean domainMatches(String host, String domain) {
        return host.equals(domain) || (host.endsWith("." + domain) && !isIp(host));
    }

    /** RFC 6265 5.1.4: equal, or a prefix that ends at a path-segment boundary. */
    static boolean pathMatches(String requestPath, String cookiePath) {
        if (requestPath.equals(cookiePath)) {
            return true;
        }
        return requestPath.startsWith(cookiePath)
                && (cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/');
    }

    static boolean isIp(String host) {
        return host.startsWith("[") || host.contains(":") || IPV4.matcher(host).matches();
    }

    /** https, or a loopback host: the same allowance browsers make so local development works. */
    static boolean secureContext(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.endsWith(".localhost") || host.startsWith("127.")
                || host.equals("[::1]") || host.equals("::1");
    }
}
