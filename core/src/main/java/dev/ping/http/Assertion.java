package dev.ping.http;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One declarative check on a response.
 *
 * <p>A single flat shape with a string discriminator rather than a class per predicate: the
 * same record binds from JSON on the RPC boundary and from YAML in a collection file, and
 * there is nothing polymorphic for reachability metadata to describe. Only the fields a
 * type uses are read; see {@link Assertions} for the predicate table.
 *
 * @param type     {@code status}, {@code header}, {@code jsonpath}, {@code body} or {@code duration}
 * @param target   header name for {@code header}, path for {@code jsonpath}
 * @param op       {@code equals}, {@code exists}, {@code contains} or {@code lt}; each type has a default
 * @param expected the comparison value; a status code, or a millisecond ceiling for {@code duration}
 * @param enabled  false to keep the assertion in the file without running it
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Assertion(String type, String target, String op, String expected, Boolean enabled) {

    @JsonIgnore
    public boolean isEnabled() {
        return enabled == null || enabled;
    }
}
