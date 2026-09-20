package dev.ping.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import dev.ping.http.Assertion;
import dev.ping.http.Capture;
import dev.ping.http.RequestSpec;

import java.util.List;

/**
 * A request as it lives in a collection file.
 *
 * <p>Field names match the {@code http.send} params on purpose: a file and an RPC call
 * describe the same thing, so whoever can read one can read the other. The one addition is
 * {@code name}, which is what the sidebar shows and the filename only approximates.
 *
 * <p>{@code docs} is free-form markdown notes about the request. It is not part of
 * {@link RequestSpec}: nothing on the wire depends on it. Importers use it for what a request
 * cannot express, such as a Postman script that was not carried over.
 *
 * <p>Empty and null fields are omitted, so a freshly created request is a handful of lines
 * rather than a wall of nulls. The engine applies defaults for anything absent.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"name", "method", "url", "query", "headers", "body", "auth",
        "timeoutMs", "redirects", "verifyTls", "cookies", "httpVersion", "maxBodyBytes", "asserts", "capture", "docs"})
public record StoredRequest(
        String name,
        String method,
        String url,
        List<RequestSpec.Param> query,
        List<RequestSpec.Param> headers,
        RequestSpec.Body body,
        RequestSpec.Auth auth,
        Integer timeoutMs,
        String redirects,
        Boolean verifyTls,
        Integer maxBodyBytes,
        List<Assertion> asserts,
        List<Capture> capture,
        String docs,
        Boolean cookies,
        String httpVersion) {

    public RequestSpec toSpec() {
        return new RequestSpec(null, method, url, query, headers, body, auth,
                timeoutMs, redirects, verifyTls, maxBodyBytes, asserts, capture, cookies, httpVersion);
    }

    public static StoredRequest fromSpec(String name, RequestSpec spec) {
        return new StoredRequest(name, spec.method(), spec.url(), spec.query(), spec.headers(),
                spec.body(), spec.auth(), spec.timeoutMs(), spec.redirects(), spec.verifyTls(),
                spec.maxBodyBytes(), spec.asserts(), spec.capture(), null, spec.cookies(), spec.httpVersion());
    }
}
