package dev.ping.imports;

import dev.ping.http.RequestSpec;
import dev.ping.store.StoredRequest;

import java.util.List;

/**
 * What every collection importer produces, whatever the source format.
 *
 * <p>Values are literal here, secrets included. {@link CollectionWriter} is the one place
 * that lifts credentials out before anything reaches disk, so a new importer cannot forget to.
 */
/**
 * @param docs the source's own description of the collection, kept as the collection's notes; may be null
 */
public record ImportedCollection(
        String name,
        List<RequestSpec.Param> variables,
        List<Environment> environments,
        Folder root,
        String docs) {

    public record Environment(String name, List<RequestSpec.Param> variables) {
    }

    /** @param name ignored for the root folder, which is the collection itself */
    public record Folder(String name, List<Folder> folders, List<StoredRequest> requests) {
    }
}
