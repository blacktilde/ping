package dev.ping.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import dev.ping.http.RequestSpec;

import java.util.List;

/**
 * A collection's own metadata, stored in {@code collection.yaml} at the collection root.
 *
 * <p>{@code docs} is free-form markdown notes about the collection, kept beside the name and
 * variables so they travel with the collection in Git.
 *
 * <p>Only present once a collection has a name or variables to keep; without the file the
 * folder name is the collection name and there are no collection-level variables.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"name", "variables", "docs"})
public record CollectionDoc(String name, List<RequestSpec.Param> variables, String docs) {
}
