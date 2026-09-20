package dev.ping.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Host patterns in the form {@code NO_PROXY} uses, shared by the proxy bypass list and the hosts a
 * client certificate is offered to.
 *
 * <p>{@code *} matches everything; {@code example.com}, {@code .example.com} and
 * {@code *.example.com} match the host and its subdomains; {@code host:port} matches only that
 * port. Loopback matches only when listed, as {@code localhost} or an address. CIDR ranges are not
 * understood.
 */
final class HostPattern {

    private HostPattern() {
    }

    /** Trimmed, lower-cased, non-empty patterns. */
    static List<String> tokens(List<String> raw) {
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

    static boolean matchesAny(List<String> tokens, String rawHost, int port) {
        String host = plain(rawHost);
        if (host == null) {
            return false;
        }
        for (String token : tokens) {
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
