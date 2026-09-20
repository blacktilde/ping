package dev.ping.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssertionsTest {

    private static final String JSON = """
            {"id": 7, "name": "ping", "ok": true, "gone": null,
             "tags": ["a", "b"], "user": {"email": "x@y.z"}, "a.b": 1}""";

    private static ResponseData response(int status, String body, long totalMs) {
        return new ResponseData(status, "HTTP_1_1",
                List.of(new ResponseData.Header("Content-Type", "application/json"),
                        new ResponseData.Header("Set-Cookie", "a=1"),
                        new ResponseData.Header("Set-Cookie", "b=2")),
                new ResponseData.BodyData(body, null, false, body == null ? 0 : body.length(),
                        body != null, "application/json", "UTF-8"),
                new ResponseData.Timing(null, 1, 1, totalMs),
                List.of(), List.of());
    }

    private static AssertionResult run(Assertion assertion, ResponseData response) {
        List<AssertionResult> results =
                Assertions.evaluate(List.of(assertion), response, Map.of());
        assertEquals(1, results.size());
        return results.get(0);
    }

    private static AssertionResult run(String type, String target, String op, String expected) {
        return run(new Assertion(type, target, op, expected, null), response(200, JSON, 40));
    }

    @Test
    void statusEqualsIsTheDefaultOp() {
        AssertionResult pass = run("status", null, null, "200");
        assertTrue(pass.passed());
        assertEquals("equals", pass.op());
        assertNull(pass.message());

        AssertionResult fail = run(new Assertion("status", null, null, "404", null),
                response(200, JSON, 40));
        assertFalse(fail.passed());
        assertEquals("200", fail.actual());
        assertEquals("Expected status 404, got 200", fail.message());
    }

    @Test
    void headerExistsEqualsAndContainsAreCaseInsensitiveOnName() {
        assertTrue(run("header", "content-type", null, null).passed());
        assertFalse(run("header", "X-Missing", "exists", null).passed());
        assertTrue(run("header", "CONTENT-TYPE", "equals", "application/json").passed());
        assertFalse(run("header", "Content-Type", "equals", "json").passed());
        assertTrue(run("header", "Content-Type", "contains", "json").passed());
    }

    @Test
    void headerMatchesAnyOfARepeatedHeader() {
        AssertionResult result = run("header", "set-cookie", "equals", "b=2");
        assertTrue(result.passed());
        assertEquals("a=1, b=2", result.actual());
    }

    @Test
    void jsonpathExistence() {
        assertTrue(run("jsonpath", "$.id", null, null).passed());
        assertTrue(run("jsonpath", "$.user.email", "exists", null).passed());
        assertTrue(run("jsonpath", "$.tags[1]", "exists", null).passed());
        assertFalse(run("jsonpath", "$.tags[2]", "exists", null).passed());
        assertFalse(run("jsonpath", "$.nope.deeper", "exists", null).passed());
    }

    @Test
    void jsonNullIsAValueNotAMiss() {
        AssertionResult result = run("jsonpath", "$.gone", "exists", null);
        assertTrue(result.passed());
        assertEquals("null", result.actual());
    }

    @Test
    void jsonpathEqualsComparesTheTextOfAScalar() {
        assertTrue(run("jsonpath", "$.id", "equals", "7").passed());
        assertTrue(run("jsonpath", "$.ok", "equals", "true").passed());
        assertTrue(run("jsonpath", "$['user']['email']", "equals", "x@y.z").passed());
        assertTrue(run("jsonpath", "$['a.b']", "equals", "1").passed());
        assertTrue(run("jsonpath", "$.tags", "contains", "\"b\"").passed());

        AssertionResult fail = run("jsonpath", "$.name", "equals", "pong");
        assertFalse(fail.passed());
        assertEquals("ping", fail.actual());
        assertEquals("$.name is not \"pong\", got ping", fail.message());
    }

    @Test
    void jsonpathEqualsOnAMissPathFailsRatherThanComparingNull() {
        AssertionResult result = run("jsonpath", "$.nope", "equals", "x");
        assertFalse(result.passed());
        assertNull(result.actual());
    }

    @Test
    void unsupportedJsonpathIsAFailedResultWithAMessage() {
        AssertionResult result = run("jsonpath", "$.tags[*]", "exists", null);
        assertFalse(result.passed());
        assertTrue(result.message().startsWith("Unsupported JSONPath segment"));
        assertFalse(run("jsonpath", "id", "exists", null).passed());
    }

    @Test
    void bodyContains() {
        assertTrue(run("body", null, null, "\"name\": \"ping\"").passed());
        assertFalse(run("body", null, "contains", "absent").passed());
    }

    @Test
    void durationCeiling() {
        assertTrue(run(new Assertion("duration", null, null, "100", null),
                response(200, JSON, 40)).passed());
        AssertionResult fail = run(new Assertion("duration", null, "lt", "40", null),
                response(200, JSON, 40));
        assertFalse(fail.passed());
        assertEquals("40 ms", fail.actual());
    }

    @Test
    void bodyAndJsonpathFailOnANonTextBody() {
        ResponseData binary = response(200, null, 1);
        assertEquals("Response body is not text",
                run(new Assertion("body", null, null, "x", null), binary).message());
        assertFalse(run(new Assertion("jsonpath", "$.id", null, null, null), binary).passed());
    }

    @Test
    void jsonpathFailsOnInvalidOrTruncatedJson() {
        assertEquals("Response body is not valid JSON",
                run(new Assertion("jsonpath", "$.id", null, null, null),
                        response(200, "<html>", 1)).message());

        ResponseData truncated = new ResponseData(200, "HTTP_1_1", List.of(),
                new ResponseData.BodyData("{\"id\":", null, true, 99, true, null, "UTF-8"),
                new ResponseData.Timing(null, 1, 1, 1), List.of(), List.of());
        assertTrue(run(new Assertion("jsonpath", "$.id", null, null, null), truncated)
                .message().contains("truncated"));
    }

    @Test
    void misconfigurationIsAFailedResultNotAnException() {
        assertEquals("Unknown assertion type: cookie", run("cookie", null, null, null).message());
        assertEquals("Assertion has no type", run(null, null, null, null).message());
        assertTrue(run("status", null, "lt", "200").message().startsWith("Unknown op"));
        assertTrue(run("status", null, null, "two hundred").message()
                .startsWith("Expected a number"));
        assertFalse(run("header", null, "exists", null).passed());
        assertFalse(run("body", null, "contains", null).passed());
    }

    @Test
    void disabledAssertionsAreSkippedNotReported() {
        List<AssertionResult> results = Assertions.evaluate(
                List.of(new Assertion("status", null, null, "500", false),
                        new Assertion("status", null, null, "200", true)),
                response(200, JSON, 1), Map.of());
        assertEquals(1, results.size());
        assertTrue(results.get(0).passed());
    }

    @Test
    void noAssertionsMeansNoResults() {
        assertTrue(Assertions.evaluate(null, response(200, JSON, 1), Map.of()).isEmpty());
        assertTrue(Assertions.evaluate(List.of(), response(200, JSON, 1), Map.of()).isEmpty());
    }

    @Test
    void targetAndExpectedAreInterpolated() {
        Map<String, String> vars = Map.of("field", "$.name", "want", "ping");
        AssertionResult result = Assertions.evaluate(
                List.of(new Assertion("jsonpath", "{{field}}", "equals", "{{want}}", null)),
                response(200, JSON, 1), vars).get(0);
        assertTrue(result.passed());
        assertEquals("$.name", result.target());
        assertEquals("ping", result.expected());
    }
}
