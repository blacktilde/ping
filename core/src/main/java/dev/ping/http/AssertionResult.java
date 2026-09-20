package dev.ping.http;

/**
 * The outcome of one {@link Assertion}, echoing it with variables resolved.
 *
 * <p>A misconfigured assertion (unknown type, unparseable path) is a failed result with a
 * {@code message}, not an RPC error: a typo in a collection file must not stop the request
 * from being sent and its response shown.
 *
 * @param actual  what the response held, or null when nothing matched
 * @param message why it failed; null when it passed
 */
public record AssertionResult(
        String type,
        String target,
        String op,
        String expected,
        boolean passed,
        String actual,
        String message) {
}
