package dev.ping.http;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One value to keep from a response, under a variable name the next request can use.
 *
 * <p>Same flat shape as {@link Assertion}: it binds from JSON on the RPC boundary and from YAML
 * in a collection file with nothing polymorphic for reachability metadata to describe.
 *
 * @param name    the runtime variable to set; letters, digits, {@code _}, {@code .} and {@code -}
 * @param source  {@code jsonpath}, {@code header} or {@code status}
 * @param target  the path for {@code jsonpath}, the header name for {@code header}; unused for status
 * @param enabled false to keep the capture in the file without running it
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Capture(String name, String source, String target, Boolean enabled) {

    @JsonIgnore
    public boolean isEnabled() {
        return enabled == null || enabled;
    }
}
