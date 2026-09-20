package dev.ping.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ping.http.AssertionResult;
import dev.ping.http.CaptureResult;

import java.util.List;

/**
 * One request's outcome within a run.
 *
 * <p>{@code url} is the spec's URL as written, placeholders unresolved, so a secret carried in
 * a query string never reaches a report. Variables are never part of a result.
 *
 * @param status     the response status; null when the exchange never completed
 * @param durationMs total exchange time; null when it never completed
 * @param error      why no response was obtained (transport or configuration); null otherwise
 * @param passed     true when a response arrived and every assertion passed
 * @param captures   what each capture did, by name; never the captured values, which stay in the run
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequestResult(
        String path,
        String name,
        String method,
        String url,
        Integer status,
        Long durationMs,
        String error,
        boolean passed,
        List<AssertionResult> assertions,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<CaptureResult> captures) {

    public boolean errored() {
        return error != null;
    }
}
