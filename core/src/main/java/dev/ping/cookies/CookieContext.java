package dev.ping.cookies;

/**
 * Which jar and scope a send uses, or none.
 *
 * <p>A send with no context neither reads nor writes cookies, which is how direct callers and
 * tests behave; the desktop shell supplies a scope per collection and environment, and each
 * collection run supplies its own fresh jar.
 */
public record CookieContext(CookieJar jar, String scope) {

    public static final CookieContext NONE = new CookieContext(null, null);

    public boolean active() {
        return jar != null && scope != null;
    }
}
