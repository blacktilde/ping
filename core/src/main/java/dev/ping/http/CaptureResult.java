package dev.ping.http;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The outcome of one {@link Capture}.
 *
 * <p>A miss (nothing matched, a bad name, a body that is not JSON) is {@code found == false}
 * with a {@code message}, never an RPC error, and it carries no value: the variable stays
 * absent, so a later {@code {{name}}} is sent as written rather than as an empty string.
 *
 * <p>{@code value} is present only on the hop from the core to the shell. The shell stores it
 * and strips it, so the renderer, history and reports only ever see that a capture happened.
 *
 * @param value the captured text; null on a miss
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaptureResult(
        String name,
        String source,
        String target,
        boolean found,
        String value,
        String message) {

    /** The same result without its value, for anything that must not hold one. */
    public CaptureResult withoutValue() {
        return new CaptureResult(name, source, target, found, null, message);
    }
}
