package dev.ping.run.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.ping.run.RunResult;

import java.io.PrintStream;

/** The {@link RunResult} as pretty-printed JSON, for tools that would rather parse than scrape. */
public final class JsonReporter implements Reporter {

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void write(RunResult result, PrintStream out) {
        try {
            out.println(JSON.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the run result", e);
        }
    }
}
