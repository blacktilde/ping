package dev.ping.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import dev.ping.http.RequestSpec;

import java.util.List;

/**
 * One environment, stored as a file under a collection's {@code environments/} folder.
 *
 * <p>Values here override the collection's variables of the same name. A value that is
 * really a secret will eventually hold only a reference; the resolver and the shell stay
 * the same either way, which is why phase 8 builds on this rather than beside it.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"name", "variables"})
public record EnvironmentDoc(String name, List<RequestSpec.Param> variables) {
}
