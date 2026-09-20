package dev.ping.http;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ping.vars.Interpolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pulls named values out of a response so the next request can use them.
 *
 * <p>Declarative, like assertions: a status, a header or one JSONPath. There is no script
 * engine, and the JSONPath subset is exactly the one {@link Assertions} documents. A capture
 * that finds nothing leaves its variable absent.
 */
public final class Captures {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    private Captures() {
    }

    public static List<CaptureResult> evaluate(
            List<Capture> captures, ResponseData response, Map<String, String> variables) {
        if (captures == null || captures.isEmpty()) {
            return List.of();
        }
        Assertions.Body body = new Assertions.Body(response);
        List<CaptureResult> results = new ArrayList<>();
        for (Capture capture : captures) {
            if (capture != null && capture.isEnabled()) {
                results.add(evaluate(capture, response, body, variables));
            }
        }
        return results;
    }

    private static CaptureResult evaluate(
            Capture capture, ResponseData response, Assertions.Body body, Map<String, String> variables) {
        String name = capture.name() == null ? "" : capture.name().trim();
        String source = capture.source() == null ? "" : capture.source().trim().toLowerCase(Locale.ROOT);
        String target = Interpolation.apply(capture.target(), variables);

        if (!NAME.matcher(name).matches()) {
            return miss(name, source, target, "A capture needs a name made of letters, digits, _ . or -");
        }
        try {
            String value = switch (source) {
                case "status" -> String.valueOf(response.status());
                case "header" -> header(target, response);
                case "jsonpath" -> jsonpath(target, body);
                case "" -> throw new Assertions.BadAssertion("Capture has no source");
                default -> throw new Assertions.BadAssertion("Unknown capture source: " + source);
            };
            return value == null
                    ? miss(name, source, target, target + " matched nothing")
                    : new CaptureResult(name, source, target, true, value, null);
        } catch (Assertions.BadAssertion e) {
            return miss(name, source, target, e.getMessage());
        }
    }

    private static String header(String name, ResponseData response) {
        if (name == null || name.isBlank()) {
            throw new Assertions.BadAssertion("Header capture needs a header name");
        }
        List<String> values = response.headers().stream()
                .filter(h -> h.name().equalsIgnoreCase(name.trim()))
                .map(ResponseData.Header::value)
                .toList();
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private static String jsonpath(String path, Assertions.Body body) {
        if (path == null || path.isBlank()) {
            throw new Assertions.BadAssertion("JSONPath capture needs a path");
        }
        JsonNode node = Assertions.select(body.json(), path.trim());
        return node == null ? null : Assertions.text(node);
    }

    private static CaptureResult miss(String name, String source, String target, String message) {
        return new CaptureResult(name, source, target, false, null, message);
    }
}
