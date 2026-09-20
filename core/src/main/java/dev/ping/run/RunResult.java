package dev.ping.run;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * @param failed  requests that got a response but failed an assertion
 * @param errored requests that got no response
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunResult(
        String collection,
        String environment,
        long durationMs,
        int total,
        int passed,
        int failed,
        int errored,
        List<RequestResult> requests) {

    /** True when nothing failed and nothing errored. */
    public boolean succeeded() {
        return failed == 0 && errored == 0;
    }
}
