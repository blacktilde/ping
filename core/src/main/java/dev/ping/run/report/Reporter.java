package dev.ping.run.report;

import dev.ping.run.RunResult;

import java.io.PrintStream;

/** Renders a finished run. Implementations write only to the stream they are given. */
public interface Reporter {

    void write(RunResult result, PrintStream out);

    /** @return the reporter for {@code human}, {@code json} or {@code junit}; null if unknown */
    static Reporter named(String name) {
        return switch (name) {
            case "human" -> new HumanReporter();
            case "json" -> new JsonReporter();
            case "junit" -> new JUnitReporter();
            default -> null;
        };
    }
}
