package dev.ping.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapturesTest {

    private static final String JSON = """
            {"token": "abc.def", "id": 7, "gone": null, "user": {"email": "x@y.z"}, "tags": ["a", "b"]}""";

    private static ResponseData response(int status, String body) {
        return new ResponseData(status, "HTTP_1_1",
                List.of(new ResponseData.Header("Content-Type", "application/json"),
                        new ResponseData.Header("Set-Cookie", "a=1"),
                        new ResponseData.Header("Set-Cookie", "b=2")),
                new ResponseData.BodyData(body, null, false, body == null ? 0 : body.length(),
                        body != null, "application/json", "UTF-8"),
                new ResponseData.Timing(null, 1, 1, 5),
                List.of(), List.of(), List.of());
    }

    private static CaptureResult one(Capture capture, ResponseData response) {
        List<CaptureResult> results = Captures.evaluate(List.of(capture), response, Map.of());
        assertEquals(1, results.size());
        return results.get(0);
    }

    private static CaptureResult one(String name, String source, String target) {
        return one(new Capture(name, source, target, null), response(200, JSON));
    }

    @Test
    void capturesAJsonPathValueAsText() {
        CaptureResult result = one("token", "jsonpath", "$.token");
        assertTrue(result.found());
        assertEquals("abc.def", result.value());
        assertNull(result.message());
        assertEquals("7", one("id", "jsonpath", "$.id").value());
        assertEquals("x@y.z", one("mail", "jsonpath", "$.user.email").value());
        assertEquals("b", one("second", "jsonpath", "$.tags[1]").value());
    }

    @Test
    void aContainerIsCapturedAsItsJsonAndAJsonNullIsAValue() {
        assertEquals("{\"email\":\"x@y.z\"}", one("user", "jsonpath", "$.user").value());
        CaptureResult nothing = one("gone", "jsonpath", "$.gone");
        assertTrue(nothing.found());
        assertEquals("null", nothing.value());
    }

    @Test
    void capturesAHeaderCaseInsensitivelyJoiningRepeats() {
        assertEquals("application/json", one("ct", "header", "CONTENT-TYPE").value());
        assertEquals("a=1, b=2", one("cookies", "header", "set-cookie").value());
    }

    @Test
    void capturesTheStatus() {
        CaptureResult result = one(new Capture("code", "status", null, null), response(404, "{}"));
        assertTrue(result.found());
        assertEquals("404", result.value());
    }

    @Test
    void aMissIsNotFoundAndCarriesNoValue() {
        CaptureResult path = one("x", "jsonpath", "$.nope.deeper");
        assertFalse(path.found());
        assertNull(path.value());
        assertEquals("$.nope.deeper matched nothing", path.message());

        CaptureResult header = one("x", "header", "X-Absent");
        assertFalse(header.found());
        assertNull(header.value());
    }

    @Test
    void misconfigurationIsAMissWithAMessageNeverAnException() {
        assertTrue(one("bad name", "status", null).message().contains("needs a name"));
        assertTrue(one("", "status", null).message().contains("needs a name"));
        assertTrue(one("x", "cookie", null).message().contains("Unknown capture source"));
        assertTrue(one("x", null, null).message().contains("no source"));
        assertTrue(one("x", "jsonpath", "id").message().contains("must start with $"));
        assertTrue(one("x", "jsonpath", "").message().contains("needs a path"));
        assertTrue(one("x", "header", "").message().contains("needs a header name"));
        assertFalse(one("x", "jsonpath", "$.tags[*]").found());
    }

    @Test
    void aBodyThatCannotBeReadAsJsonIsAMiss() {
        assertEquals("Response body is not valid JSON",
                one(new Capture("x", "jsonpath", "$.a", null), response(200, "<html>")).message());
        assertEquals("Response body is not text",
                one(new Capture("x", "jsonpath", "$.a", null), response(200, null)).message());
        // A status or header capture does not need a body at all.
        assertTrue(one(new Capture("c", "status", null, null), response(200, null)).found());
    }

    @Test
    void disabledCapturesAreSkippedNotReported() {
        List<CaptureResult> results = Captures.evaluate(
                List.of(new Capture("a", "status", null, false), new Capture("b", "status", null, true)),
                response(200, JSON), Map.of());
        assertEquals(1, results.size());
        assertEquals("b", results.get(0).name());
    }

    @Test
    void noCapturesMeansNoResults() {
        assertTrue(Captures.evaluate(null, response(200, JSON), Map.of()).isEmpty());
        assertTrue(Captures.evaluate(List.of(), response(200, JSON), Map.of()).isEmpty());
    }

    @Test
    void theTargetIsInterpolated() {
        CaptureResult result = Captures.evaluate(
                List.of(new Capture("t", "jsonpath", "{{field}}", null)),
                response(200, JSON), Map.of("field", "$.token")).get(0);
        assertEquals("abc.def", result.value());
        assertEquals("$.token", result.target());
    }

    @Test
    void withoutValueDropsOnlyTheValue() {
        CaptureResult result = one("token", "jsonpath", "$.token").withoutValue();
        assertNull(result.value());
        assertTrue(result.found());
        assertEquals("token", result.name());
    }
}
