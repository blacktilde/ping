package dev.ping.store;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One node of the sidebar tree.
 *
 * <p>Paths are relative to the workspace root and use forward slashes on every platform, so
 * a collection cloned on one OS keeps working on another.
 *
 * @param type     {@code collection}, {@code folder} or {@code request}
 * @param method   the request's method for display; null for collections and folders, and
 *                 for a request file that could not be parsed
 * @param children folders and requests below this node; empty for a request
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CollectionNode(
        String name,
        String path,
        String type,
        String method,
        List<CollectionNode> children) {

    public static final String COLLECTION = "collection";
    public static final String FOLDER = "folder";
    public static final String REQUEST = "request";
}
