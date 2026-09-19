package dev.ping.store;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A named environment as the picker needs it: what to show, and the path to load.
 *
 * @param path relative to the workspace root
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record EnvironmentRef(String name, String path) {
}
