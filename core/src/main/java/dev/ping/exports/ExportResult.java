package dev.ping.exports;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * An exported collection, as text for the shell to write where the user chooses.
 *
 * @param name     the collection's name, which the shell suggests as the file name
 * @param content  the document itself
 * @param requests how many requests it holds
 * @param warnings what the format could not carry; omitted when empty
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ExportResult(String name, String content, int requests, List<String> warnings) {
}
