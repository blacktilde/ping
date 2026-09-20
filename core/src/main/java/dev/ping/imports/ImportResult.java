package dev.ping.imports;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ping.store.StoredRequest;

import java.util.List;

/**
 * What an importer produced, and what it could not carry over.
 *
 * <p>Anything unmappable is reported in {@code warnings} rather than dropped silently: an
 * import that quietly loses a client certificate or a file upload sends a different request
 * than the one the user copied.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ImportResult(StoredRequest request, List<String> warnings) {
}
