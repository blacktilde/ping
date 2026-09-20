package dev.ping.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.vars.Interpolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates declarative {@link Assertion}s against a response.
 *
 * <p>There is deliberately no scripting here. The predicate set is closed; if it proves too
 * narrow the answer is another predicate, not an interpreter. See docs/PLAN.md, phase 15.
 *
 * <pre>
 * type      op (default first)          reads
 * status    equals                      target ignored; expected is the code
 * header    exists, equals, contains    target is the name, case-insensitive
 * jsonpath  exists, equals, contains    target is a path, see below
 * body      contains                    the decoded text
 * duration  lt                          expected is a ceiling in ms against total time
 * </pre>
 *
 * <p>The JSONPath subset is {@code $}, {@code .name}, {@code ['name']} and {@code [n]}.
 * That covers addressing a value, which is all an assertion needs; filters and wildcards
 * would return several nodes, and "equals" over several is a question this tool declines
 * to answer. {@code target} and {@code expected} are interpolated like the rest of a request.
 */
public final class Assertions {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Assertions() {
    }

    public static List<AssertionResult> evaluate(
            List<Assertion> assertions, ResponseData response, Map<String, String> variables) {
        if (assertions == null || assertions.isEmpty()) {
            return List.of();
        }
        Body body = new Body(response);
        List<AssertionResult> results = new ArrayList<>();
        for (Assertion assertion : assertions) {
            if (assertion != null && assertion.isEnabled()) {
                results.add(evaluate(assertion, response, body, variables));
            }
        }
        return results;
    }

    private static AssertionResult evaluate(
            Assertion assertion, ResponseData response, Body body, Map<String, String> variables) {
        String type = normalize(assertion.type());
        String target = Interpolation.apply(assertion.target(), variables);
        String expected = Interpolation.apply(assertion.expected(), variables);
        String op = normalize(assertion.op());

        Outcome outcome;
        try {
            outcome = switch (type) {
                case "status" -> {
                    op = or(op, "equals");
                    yield status(op, expected, response);
                }
                case "header" -> {
                    op = or(op, "exists");
                    yield header(op, target, expected, response);
                }
                case "jsonpath" -> {
                    op = or(op, "exists");
                    yield jsonpath(op, target, expected, body);
                }
                case "body" -> {
                    op = or(op, "contains");
                    yield bodyText(op, expected, body);
                }
                case "duration" -> {
                    op = or(op, "lt");
                    yield duration(op, expected, response);
                }
                case "" -> Outcome.fail(null, "Assertion has no type");
                default -> Outcome.fail(null, "Unknown assertion type: " + type);
            };
        } catch (BadAssertion e) {
            outcome = Outcome.fail(null, e.getMessage());
        }
        return new AssertionResult(type, target, op, expected, outcome.passed(),
                outcome.actual(), outcome.passed() ? null : outcome.message());
    }

    private static Outcome status(String op, String expected, ResponseData response) {
        requireOp(op, "status", "equals");
        int want = integer(expected, "status");
        int got = response.status();
        return got == want
                ? Outcome.pass(String.valueOf(got))
                : Outcome.fail(String.valueOf(got), "Expected status " + want + ", got " + got);
    }

    private static Outcome header(
            String op, String name, String expected, ResponseData response) {
        requireOp(op, "header", "exists", "equals", "contains");
        if (name == null || name.isBlank()) {
            throw new BadAssertion("Header assertion needs a header name");
        }
        List<String> values = response.headers().stream()
                .filter(h -> h.name().equalsIgnoreCase(name.trim()))
                .map(ResponseData.Header::value)
                .toList();
        String actual = values.isEmpty() ? null : String.join(", ", values);
        if (op.equals("exists")) {
            return values.isEmpty()
                    ? Outcome.fail(null, "Header " + name + " is absent")
                    : Outcome.pass(actual);
        }
        requireExpected(expected, "header " + op);
        boolean matched = values.stream().anyMatch(v -> compare(op, v, expected));
        return matched
                ? Outcome.pass(actual)
                : Outcome.fail(actual, values.isEmpty()
                        ? "Header " + name + " is absent"
                        : "Header " + name + " " + describe(op, expected) + ", got " + actual);
    }

    private static Outcome jsonpath(String op, String path, String expected, Body body) {
        requireOp(op, "jsonpath", "exists", "equals", "contains");
        if (path == null || path.isBlank()) {
            throw new BadAssertion("JSONPath assertion needs a path");
        }
        JsonNode root = body.json();
        JsonNode node = select(root, path.trim());
        String actual = node == null ? null : text(node);
        if (op.equals("exists")) {
            return node == null
                    ? Outcome.fail(null, path + " matched nothing")
                    : Outcome.pass(actual);
        }
        requireExpected(expected, "jsonpath " + op);
        if (node == null) {
            return Outcome.fail(null, path + " matched nothing");
        }
        return compare(op, actual, expected)
                ? Outcome.pass(actual)
                : Outcome.fail(actual, path + " " + describe(op, expected) + ", got " + actual);
    }

    private static Outcome bodyText(String op, String expected, Body body) {
        requireOp(op, "body", "contains");
        requireExpected(expected, "body contains");
        String content = body.text();
        return content.contains(expected)
                ? Outcome.pass(null)
                : Outcome.fail(null, "Body does not contain \"" + expected + "\"");
    }

    private static Outcome duration(String op, String expected, ResponseData response) {
        requireOp(op, "duration", "lt");
        long ceiling = integer(expected, "duration");
        long took = response.timing().totalMs();
        return took < ceiling
                ? Outcome.pass(took + " ms")
                : Outcome.fail(took + " ms", "Took " + took + " ms, ceiling is " + ceiling + " ms");
    }

    private static boolean compare(String op, String actual, String expected) {
        return op.equals("equals") ? actual.equals(expected) : actual.contains(expected);
    }

    private static String describe(String op, String expected) {
        return (op.equals("equals") ? "is not \"" : "does not contain \"") + expected + "\"";
    }

    /** {@code null} when the path resolves to nothing. A JSON {@code null} is a value. */
    static JsonNode select(JsonNode root, String path) {
        if (!path.startsWith("$")) {
            throw new BadAssertion("JSONPath must start with $: " + path);
        }
        JsonNode node = root;
        int i = 1;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                int end = i + 1;
                while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                String name = path.substring(i + 1, end);
                if (name.isEmpty() || name.equals("*")) {
                    throw new BadAssertion("Unsupported JSONPath segment in " + path);
                }
                node = node == null ? null : member(node, name);
                i = end;
            } else if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw new BadAssertion("Unclosed [ in JSONPath " + path);
                }
                String inner = path.substring(i + 1, close).trim();
                if (inner.length() >= 2
                        && (inner.charAt(0) == '\'' || inner.charAt(0) == '"')
                        && inner.charAt(inner.length() - 1) == inner.charAt(0)) {
                    node = node == null ? null : member(node, inner.substring(1, inner.length() - 1));
                } else if (!inner.isEmpty() && inner.chars().allMatch(Character::isDigit)) {
                    int index = Integer.parseInt(inner);
                    node = node == null || !node.isArray() || index >= node.size()
                            ? null : node.get(index);
                } else {
                    throw new BadAssertion("Unsupported JSONPath segment [" + inner + "] in " + path);
                }
                i = close + 1;
            } else {
                throw new BadAssertion("Unexpected '" + c + "' in JSONPath " + path);
            }
        }
        return node;
    }

    private static JsonNode member(JsonNode node, String name) {
        return node.isObject() && node.has(name) ? node.get(name) : null;
    }

    static String text(JsonNode node) {
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static int integer(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new BadAssertion("Assertion on " + what + " needs an expected number");
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new BadAssertion("Expected a number for " + what + ", got \"" + value + "\"");
        }
    }

    private static void requireOp(String op, String type, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(op)) {
                return;
            }
        }
        throw new BadAssertion("Unknown op \"" + op + "\" for " + type
                + "; use " + String.join(", ", allowed));
    }

    private static void requireExpected(String expected, String what) {
        if (expected == null) {
            throw new BadAssertion("Assertion " + what + " needs an expected value");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String or(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    private record Outcome(boolean passed, String actual, String message) {
        static Outcome pass(String actual) {
            return new Outcome(true, actual, null);
        }

        static Outcome fail(String actual, String message) {
            return new Outcome(false, actual, message);
        }
    }

    /** Raised for a misconfigured assertion; becomes a failed result, never an RPC error. */
    static final class BadAssertion extends RuntimeException {
        BadAssertion(String message) {
            super(message);
        }
    }

    /** Parses the body at most once, and only if an assertion asks for it. */
    static final class Body {
        private final ResponseData.BodyData data;
        private JsonNode json;

        Body(ResponseData response) {
            this.data = response.body();
        }

        String text() {
            if (data == null || data.content() == null) {
                throw new BadAssertion("Response body is not text");
            }
            return data.content();
        }

        JsonNode json() {
            if (json == null) {
                String content = text();
                if (data.truncated()) {
                    throw new BadAssertion("Response body was truncated, so it cannot be parsed as JSON");
                }
                try {
                    json = JSON.readTree(content);
                } catch (Exception e) {
                    throw new BadAssertion("Response body is not valid JSON");
                }
                if (json == null || json.isMissingNode()) {
                    throw new BadAssertion("Response body is empty");
                }
            }
            return json;
        }
    }
}
