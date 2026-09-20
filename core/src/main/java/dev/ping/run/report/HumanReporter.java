package dev.ping.run.report;

import dev.ping.http.AssertionResult;
import dev.ping.http.CaptureResult;
import dev.ping.run.RequestResult;
import dev.ping.run.RunResult;

import java.io.PrintStream;

/** Plain text for a terminal or a CI log. ASCII markers, so no log viewer mangles them. */
public final class HumanReporter implements Reporter {

    @Override
    public void write(RunResult result, PrintStream out) {
        String environment = result.environment() == null ? "" : " (" + result.environment() + ")";
        out.println(result.collection() + environment);

        for (RequestResult request : result.requests()) {
            String marker = request.errored() ? "ERR " : request.passed() ? "PASS" : "FAIL";
            StringBuilder line = new StringBuilder(marker).append("  ")
                    .append(request.method() == null ? "?" : request.method()).append(' ')
                    .append(request.name());
            if (request.errored()) {
                line.append("  ").append(request.error());
            } else {
                line.append("  ").append(request.status()).append("  ")
                        .append(request.durationMs()).append(" ms");
            }
            out.println(line);
            if (request.captures() != null) {
                for (CaptureResult capture : request.captures()) {
                    if (!capture.found()) {
                        out.println("      ! capture " + capture.name() + ": " + capture.message());
                    }
                }
            }
            for (AssertionResult assertion : request.assertions()) {
                if (!assertion.passed()) {
                    out.println("      x " + describe(assertion)
                            + (assertion.message() == null ? "" : ": " + assertion.message()));
                }
            }
        }

        out.println();
        out.println(result.total() + " requests: " + result.passed() + " passed, "
                + result.failed() + " failed, " + result.errored() + " errored ("
                + result.durationMs() + " ms)");
    }

    /** "jsonpath $.id equals 7", the same shape the response pane shows. */
    static String describe(AssertionResult assertion) {
        StringBuilder text = new StringBuilder(assertion.type());
        if (assertion.target() != null && !assertion.target().isEmpty()) {
            text.append(' ').append(assertion.target());
        }
        text.append(' ').append(assertion.op());
        if (assertion.expected() != null && !assertion.expected().isEmpty()) {
            text.append(' ').append(assertion.expected());
        }
        return text.toString();
    }
}
