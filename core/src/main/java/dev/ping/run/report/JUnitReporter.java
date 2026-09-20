package dev.ping.run.report;

import dev.ping.http.AssertionResult;
import dev.ping.run.RequestResult;
import dev.ping.run.RunResult;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/**
 * JUnit XML, the format every CI system already knows how to show.
 *
 * <p>One {@code testcase} per request. A failed assertion is a {@code failure}, a request
 * that got no response is an {@code error}, matching how JUnit itself tells them apart.
 * Built with a string builder because an XML library would be a new dependency in a native
 * image for the sake of a page of output; {@link #escape} is what makes that safe.
 */
public final class JUnitReporter implements Reporter {

    @Override
    public void write(RunResult result, PrintStream out) {
        String suite = escape(result.collection());
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuites name=\"").append(suite)
                .append("\" tests=\"").append(result.total())
                .append("\" failures=\"").append(result.failed())
                .append("\" errors=\"").append(result.errored())
                .append("\" time=\"").append(seconds(result.durationMs())).append("\">\n");
        xml.append("  <testsuite name=\"").append(suite)
                .append("\" tests=\"").append(result.total())
                .append("\" failures=\"").append(result.failed())
                .append("\" errors=\"").append(result.errored())
                .append("\" time=\"").append(seconds(result.durationMs())).append("\">\n");

        for (RequestResult request : result.requests()) {
            long ms = request.durationMs() == null ? 0 : request.durationMs();
            xml.append("    <testcase classname=\"").append(suite)
                    .append("\" name=\"").append(escape(request.path()))
                    .append("\" time=\"").append(seconds(ms)).append('"');
            if (request.passed()) {
                xml.append("/>\n");
                continue;
            }
            xml.append(">\n");
            if (request.errored()) {
                xml.append("      <error message=\"").append(escape(request.error()))
                        .append("\" type=\"RequestError\"/>\n");
            } else {
                List<AssertionResult> failed =
                        request.assertions().stream().filter(a -> !a.passed()).toList();
                String first = failed.isEmpty() ? "failed" : summary(failed.get(0));
                xml.append("      <failure message=\"").append(escape(first))
                        .append("\" type=\"AssertionFailure\">");
                for (AssertionResult assertion : failed) {
                    xml.append(escape(summary(assertion))).append('\n');
                }
                xml.append("</failure>\n");
            }
            xml.append("    </testcase>\n");
        }

        xml.append("  </testsuite>\n</testsuites>\n");
        out.print(xml);
    }

    private static String summary(AssertionResult assertion) {
        String what = HumanReporter.describe(assertion);
        return assertion.message() == null ? what : what + ": " + assertion.message();
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%d.%03d", millis / 1000, millis % 1000);
    }

    /**
     * Escapes the five XML specials and replaces characters XML 1.0 cannot carry at all, which
     * a response body echoed into an assertion message can easily contain.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        value.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> {
                    boolean valid = c == 0x9 || c == 0xA || c == 0xD
                            || (c >= 0x20 && c <= 0xD7FF)
                            || (c >= 0xE000 && c <= 0xFFFD && c != 0xFFFE)
                            || (c >= 0x10000 && c <= 0x10FFFF);
                    escaped.appendCodePoint(valid ? c : '?');
                }
            }
        });
        return escaped.toString();
    }
}
