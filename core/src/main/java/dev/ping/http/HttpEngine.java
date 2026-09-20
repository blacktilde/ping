package dev.ping.http;

import dev.ping.auth.Authenticator;
import dev.ping.auth.TokenCache;
import dev.ping.auth.TokenClient;
import dev.ping.rpc.RpcException;
import dev.ping.vars.Interpolation;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes one request and reports what happened, including how long each observable
 * stage took.
 *
 * <p>Requests run on virtual threads, so a collection run can hold hundreds in flight
 * without a thread pool to size. Each in-flight exchange is registered under its
 * {@code requestId} so {@link #cancel(String)} can abort it.
 */
public final class HttpEngine {

    /** Headers the JDK client sets itself; letting a user override them throws. */
    private static final List<String> RESTRICTED_HEADERS =
            List.of("connection", "content-length", "expect", "host", "upgrade");

    /**
     * RFC 7230 token: the only characters legal in a header field name. The JDK enforces
     * this by throwing {@link IllegalArgumentException}, which would surface as raw Java;
     * checking first lets us report bad input as bad input.
     */
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

    private final Map<String, Exchange> inFlight = new ConcurrentHashMap<>();
    private final TokenCache tokenCache;
    private final Authenticator authenticator;

    public HttpEngine() {
        this(new TokenCache());
    }

    /** Shares the token cache with the interactive OAuth2 flow, so a send can reuse a token. */
    public HttpEngine(TokenCache tokenCache) {
        this.tokenCache = tokenCache;
        this.authenticator = new Authenticator(new TokenClient(), tokenCache);
    }

    /**
     * An in-flight request and whether we asked it to stop.
     *
     * <p>Registered before dispatch and filled in afterwards: the renderer shows Cancel as
     * soon as it sends, so a cancel can arrive while the request is still being handed to
     * the client. The intent is applied to the future whenever it turns up.
     *
     * <p>The intent is tracked explicitly because the JDK does not report it reliably: a
     * cancelled exchange may surface as {@code CancellationException} or as a wrapped I/O
     * failure depending on how far it had progressed. Only the caller knows the difference
     * between "the network broke" and "the user pressed stop".
     */
    private static final class Exchange {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile CompletableFuture<?> future;

        AtomicBoolean cancelled() {
            return cancelled;
        }

        CompletableFuture<?> future() {
            return future;
        }

        void future(CompletableFuture<?> value) {
            this.future = value;
        }
    }

    /** Built per request: redirect policy and TLS trust are per-request settings. */
    private HttpClient clientFor(RequestSpec spec) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofMillis(spec.timeoutOrDefault()))
                // Redirects are followed by {@link #send}, which rebuilds each hop so
                // credentials can be dropped when a redirect changes host. The JDK client
                // must not follow them itself: {@code Redirect.NORMAL} replays every
                // header, including Authorization, to the redirect target.
                .followRedirects(HttpClient.Redirect.NEVER);

        if (!spec.verifyTlsOrDefault()) {
            builder.sslContext(trustAllContext()).sslParameters(noHostnameVerification());
        }
        return builder.build();
    }

    /**
     * Parses the redirect policy, rejecting an unknown value as bad input at dispatch time.
     */
    private static HttpClient.Redirect redirectPolicy(String value) {
        if (value == null) {
            return HttpClient.Redirect.NORMAL;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "never", "off", "false" -> HttpClient.Redirect.NEVER;
            case "always" -> HttpClient.Redirect.ALWAYS;
            case "normal" -> HttpClient.Redirect.NORMAL;
            default -> throw RpcException.invalidParams("Unknown redirect policy: " + value);
        };
    }

    /** Convenience for callers with no variables: tests, and the CLI before phase 7 lands. */
    public ResponseData send(RequestSpec spec) {
        return send(spec, Map.of());
    }

    public ResponseData send(RequestSpec spec, Map<String, String> variables) {
        String requestId = spec.requestId() == null ? UUID.randomUUID().toString() : spec.requestId();

        // Register before doing any work at all. The renderer offers Cancel the moment it
        // sends, so the intent has to be recorded before the request can be built, let alone
        // dispatched; otherwise an early Cancel is a no-op and the request runs to completion.
        Exchange exchange = new Exchange();
        inFlight.put(requestId, exchange);

        try {
            if (exchange.cancelled().get()) {
                throw cancelledException();
            }

            Authenticator.Applied auth = authenticator.apply(spec.auth(), variables);
            URI uri = buildUri(spec, variables, auth.query());
            HttpRequest request = buildRequest(spec, uri, variables, auth.headers());

            // Resolved before dispatch so the cost is attributable; the client's own lookup
            // then hits the JDK cache. Failure is not fatal: report the timing as unknown.
            Long dnsMs = measureDns(uri.getHost());

            HttpClient client = clientFor(spec);
            long startedAt = System.nanoTime();

            CompletableFuture<HttpResponse<InputStream>> future =
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            exchange.future(future);
            if (exchange.cancelled().get()) {
                future.cancel(true);
            }

            HttpResponse<InputStream> response = future.join();
            long ttfbMs = millisSince(startedAt);

            redirectPolicy(spec.redirects()); // validates unknown policies
            List<ResponseData.Redirect> redirects = new ArrayList<>();
            int hops = 0;
            while (follows(spec.redirects()) && isRedirect(response.statusCode())
                    && hops < MAX_REDIRECTS) {
                URI location = redirectLocation(response, request.uri());
                if (location == null) {
                    break;
                }
                hops++;
                redirects.add(new ResponseData.Redirect(
                        response.statusCode(),
                        request.uri().toString(),
                        response.headers().firstValue("location").orElse(null)));
                closeQuietly(response.body());
                boolean crossHost = !sameOrigin(location, request.uri());
                // "always" is the escape hatch that follows without filtering; every other
                // policy drops credentials cross-host.
                boolean dropCredentials =
                        crossHost && !"always".equals(policyName(spec.redirects()));
                request = redirectRequest(
                        request, location, response.statusCode(), spec, variables,
                        dropCredentials);
                future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
                exchange.future(future);
                if (exchange.cancelled().get()) {
                    future.cancel(true);
                }
                response = future.join();
            }

            long bodyStartedAt = System.nanoTime();
            Payload payload = readBody(response.body(), spec.maxBodyBytesOrDefault(), exchange);
            long downloadMs = millisSince(bodyStartedAt);

            ResponseData data = assemble(response, payload, new ResponseData.Timing(
                    dnsMs, ttfbMs, downloadMs, millisSince(startedAt)), redirects);
            return data.withAssertions(Assertions.evaluate(spec.asserts(), data, variables))
                    .withCaptured(Captures.evaluate(spec.capture(), data, variables));
        } catch (CancellationException e) {
            throw cancelledException();
        } catch (CompletionException e) {
            if (exchange.cancelled().get()) {
                throw cancelledException();
            }
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RpcException(RpcException.REQUEST_FAILED, describe(cause), cause);
        } catch (IOException e) {
            if (exchange.cancelled().get()) {
                throw cancelledException();
            }
            throw new RpcException(RpcException.REQUEST_FAILED, describe(e), e);
        } finally {
            inFlight.remove(requestId);
        }
    }

    /** @return true when a request was in flight under this id and has been asked to stop */
    public boolean cancel(String requestId) {
        Exchange exchange = inFlight.remove(requestId);
        if (exchange == null) {
            return false;
        }
        exchange.cancelled().set(true);
        CompletableFuture<?> future = exchange.future();
        if (future != null) {
            // Null when dispatch has not returned yet; send() applies the intent once it does.
            future.cancel(true);
        }
        return true;
    }

    private static RpcException cancelledException() {
        return new RpcException(RpcException.REQUEST_CANCELLED, "Request cancelled");
    }

    // --- redirect following ---------------------------------------------------------------

    /** A chain this long is a loop, not a redirect. */
    private static final int MAX_REDIRECTS = 20;

    /** Credential headers are never replayed to a different host than the one that asked. */
    private static final List<String> CREDENTIAL_HEADERS =
            List.of("authorization", "proxy-authorization", "cookie");

    private static boolean follows(String policy) {
        return !"never".equalsIgnoreCase(policyName(policy));
    }

    private static String policyName(String policy) {
        return policy == null ? "normal" : policy.toLowerCase(Locale.ROOT);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static URI redirectLocation(HttpResponse<?> response, URI requested) {
        String location = response.headers().firstValue("location").orElse(null);
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            return requested.resolve(location);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Scheme, host and effective port decide "a different host", not the path. */
    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * Rebuilds the exchange for one redirect hop.
     *
     * <p>301, 302 and 303 downgrade any method that carried a body to GET, the way every
     * major client does; 307 and 308 keep the method and re-send the body. Headers are
     * copied from the outgoing request, minus the credential headers when the hop changes
     * host — an open redirect must not become an exfiltration channel for a bearer token.
     */
    private static HttpRequest redirectRequest(
            HttpRequest original, URI location, int status, RequestSpec spec,
            Map<String, String> variables, boolean dropCredentials) {

        String method = original.method();
        boolean keepMethod = status == 307 || status == 308
                || method.equalsIgnoreCase("GET") || method.equalsIgnoreCase("HEAD");
        if (!keepMethod) {
            method = "GET";
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(location)
                .timeout(original.timeout().orElse(null));
        for (Map.Entry<String, List<String>> header : original.headers().map().entrySet()) {
            if (dropCredentials && CREDENTIAL_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String value : header.getValue()) {
                builder.header(header.getKey(), value);
            }
        }
        // The original publisher has already been subscribed to by the client, so the body
        // is rebuilt rather than replayed; a method that dropped its body sends none.
        HttpRequest.BodyPublisher body = keepMethod
                ? buildBody(spec.body(), variables).publisher()
                : HttpRequest.BodyPublishers.noBody();
        builder.method(method, body);
        return builder.build();
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // The hop is over; a close failure on a discard body is not worth reporting.
        }
    }

    // --- request construction ------------------------------------------------------------

    private static URI buildUri(
            RequestSpec spec, Map<String, String> variables, List<RequestSpec.Param> authQuery) {
        if (spec.url() == null || spec.url().isBlank()) {
            throw RpcException.invalidParams("A url is required");
        }

        String url = interpolate(spec.url().trim(), variables);
        String encodedQuery = encodeQuery(spec.query(), variables);
        String encodedAuth = encodeQuery(authQuery, variables);
        if (!encodedAuth.isEmpty()) {
            encodedQuery = encodedQuery.isEmpty() ? encodedAuth : encodedQuery + "&" + encodedAuth;
        }
        if (!encodedQuery.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + encodedQuery;
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw RpcException.invalidParams("Malformed url: " + e.getMessage());
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw RpcException.invalidParams("Url must be absolute, with a scheme and host: " + url);
        }
        return uri;
    }

    private static String encodeQuery(List<RequestSpec.Param> query, Map<String, String> variables) {
        if (query == null) {
            return "";
        }
        StringBuilder encoded = new StringBuilder();
        for (RequestSpec.Param param : query) {
            if (!param.isEnabled() || param.name() == null || param.name().isBlank()) {
                continue;
            }
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(urlEncode(interpolate(param.name(), variables)))
                    .append('=')
                    .append(urlEncode(interpolate(param.value(), variables)));
        }
        return encoded.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** Header injection is bad input, not an internal fault: report it as such. */
    private static void validateHeader(String name, String value) {
        if (name.isEmpty() || !HEADER_NAME.matcher(name).matches()) {
            throw RpcException.invalidParams(
                    "Header name contains characters that are not allowed: " + printable(name));
        }
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw RpcException.invalidParams("Header value must not contain line breaks: " + name);
        }
    }

    private static String printable(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private HttpRequest buildRequest(
            RequestSpec spec, URI uri, Map<String, String> variables, Map<String, String> authHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(spec.timeoutOrDefault()));

        BodyPayload body = buildBody(spec.body(), variables);
        builder.method(spec.methodOrDefault(), body.publisher());

        boolean contentTypeSet = false;
        if (spec.headers() != null) {
            for (RequestSpec.Param header : spec.headers()) {
                if (!header.isEnabled() || header.name() == null || header.name().isBlank()) {
                    continue;
                }
                String name = interpolate(header.name().trim(), variables);
                if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    // The JDK owns these; setting one throws IllegalArgumentException.
                    continue;
                }
                String value = interpolate(header.value(), variables);
                validateHeader(name, value);
                builder.header(name, value);
                contentTypeSet |= name.equalsIgnoreCase("content-type");
            }
        }

        // Auth is applied last and replaces any header of the same name: an explicit auth
        // config wins over a stale Authorization the user left in the table.
        for (Map.Entry<String, String> header : authHeaders.entrySet()) {
            builder.setHeader(header.getKey(), header.getValue());
        }

        // An explicit header always wins over the type implied by the body mode.
        if (!contentTypeSet && body.contentType() != null) {
            builder.setHeader("Content-Type", body.contentType());
        }
        return builder.build();
    }

    private record BodyPayload(HttpRequest.BodyPublisher publisher, String contentType) {
    }

    private static BodyPayload buildBody(RequestSpec.Body body, Map<String, String> variables) {
        if (body == null || body.type() == null || body.type().equalsIgnoreCase("none")) {
            return new BodyPayload(HttpRequest.BodyPublishers.noBody(), null);
        }

        String explicit = interpolate(body.contentType(), variables);
        return switch (body.type().toLowerCase(Locale.ROOT)) {
            case "json" -> new BodyPayload(
                    ofString(interpolate(body.content(), variables)),
                    explicit == null ? "application/json" : explicit);
            case "raw" -> new BodyPayload(
                    ofString(interpolate(body.content(), variables)),
                    explicit == null ? "text/plain" : explicit);
            case "form" -> new BodyPayload(
                    ofString(encodeQuery(body.fields(), variables)),
                    explicit == null ? "application/x-www-form-urlencoded" : explicit);
            case "multipart" -> multipart(body.fields(), variables);
            default -> throw RpcException.invalidParams("Unknown body type: " + body.type());
        };
    }

    private static HttpRequest.BodyPublisher ofString(String content) {
        return HttpRequest.BodyPublishers.ofString(
                content == null ? "" : content, StandardCharsets.UTF_8);
    }

    private static BodyPayload multipart(List<RequestSpec.Param> fields, Map<String, String> variables) {
        String boundary = "PingBoundary" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder payload = new StringBuilder();

        if (fields != null) {
            for (RequestSpec.Param field : fields) {
                if (!field.isEnabled() || field.name() == null || field.name().isBlank()) {
                    continue;
                }
                String name = interpolate(field.name(), variables);
                if (name.indexOf('"') >= 0 || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
                    throw RpcException.invalidParams(
                            "Multipart field name must not contain quotes or line breaks: "
                                    + printable(name));
                }
                payload.append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"")
                        .append(name).append("\"\r\n\r\n")
                        .append(interpolate(field.value(), variables)).append("\r\n");
            }
        }
        payload.append("--").append(boundary).append("--\r\n");

        return new BodyPayload(ofString(payload.toString()), "multipart/form-data; boundary=" + boundary);
    }

    // --- response handling ---------------------------------------------------------------

    private record Payload(byte[] kept, long total, boolean truncated) {
    }

    /**
     * Buffers up to {@code cap} bytes and keeps counting past it, so the UI can report the
     * true size of a response it is not going to display.
     *
     * <p>Cancelling the exchange's future only aborts connect and headers: by the time
     * {@code join()} returns, the body is a plain blocking stream. The read loop therefore
     * watches the cancelled intent itself and closes the stream mid-download.
     */
    private Payload readBody(InputStream stream, int cap, Exchange exchange) throws IOException {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;

        try (stream) {
            int read;
            while ((read = stream.read(chunk)) != -1) {
                if (exchange.cancelled().get()) {
                    throw cancelledException();
                }
                total += read;
                int room = cap - kept.size();
                if (room > 0) {
                    kept.write(chunk, 0, Math.min(read, room));
                }
            }
        }
        return new Payload(kept.toByteArray(), total, total > cap);
    }

    private static ResponseData assemble(
            HttpResponse<InputStream> response, Payload payload, ResponseData.Timing timing,
            List<ResponseData.Redirect> redirects) {

        List<ResponseData.Header> headers = new ArrayList<>();
        response.headers().map().forEach((name, values) ->
                values.forEach(value -> headers.add(new ResponseData.Header(name, value))));
        headers.sort(Comparator.comparing(ResponseData.Header::name));

        String contentType = response.headers().firstValue("content-type").orElse(null);
        Charset charset = charsetOf(contentType);
        boolean textual = isTextual(contentType);

        // Truncated text is still worth showing; the flag tells the UI it is partial.
        String content = textual ? new String(payload.kept(), charset) : null;
        String base64 = textual ? null : Base64.getEncoder().encodeToString(payload.kept());

        ResponseData.BodyData body = new ResponseData.BodyData(
                content, base64, payload.truncated(), payload.total(), textual,
                contentType, charset.name());

        return new ResponseData(
                response.statusCode(),
                response.version().name(),
                headers,
                body,
                timing,
                redirects,
                List.of(),
                List.of());
    }

    private static boolean isTextual(String contentType) {
        if (contentType == null) {
            return true; // No declared type: assume text and let the user see it.
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/")
                || type.contains("json")
                || type.contains("xml")
                || type.contains("javascript")
                || type.contains("x-www-form-urlencoded");
    }

    private static Charset charsetOf(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
                String name = trimmed.substring(8).replace("\"", "").trim();
                try {
                    return Charset.forName(name);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    // --- helpers -------------------------------------------------------------------------

    private static Long measureDns(String host) {
        if (host == null) {
            return null;
        }
        long startedAt = System.nanoTime();
        try {
            InetAddress.getByName(host);
            return millisSince(startedAt);
        } catch (Exception e) {
            return null;
        }
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /**
     * Substitutes {@code {{name}}} from the resolved map. An unknown name is left exactly as
     * written, so a half-configured request shows what is missing on the wire.
     */
    static String interpolate(String value, Map<String, String> variables) {
        return Interpolation.apply(value, variables);
    }

    /**
     * Turns a transport failure into something worth showing a user.
     *
     * <p>Exception class names are a debugging aid, not a product surface: "Connection
     * refused" is the same information as "ConnectException: Connection refused" without
     * the Java. Messages that are useless on their own (a bare hostname, a missing
     * timeout detail) are rewritten; the rest pass through untouched.
     *
     * <p>Package-private so the branch table can be asserted directly, without having to
     * provoke each failure over a real socket.
     */
    static String describe(Throwable cause) {
        if (cause instanceof UnknownHostException) {
            return "Unknown host: " + cause.getMessage();
        }
        if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
            return "Request timed out";
        }
        if (cause instanceof SSLException) {
            String message = cause.getMessage();
            return message == null || message.isBlank()
                    ? "TLS handshake failed"
                    : "TLS handshake failed: " + message;
        }

        String message = cause.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return cause instanceof ConnectException ? "Connection refused" : "The request failed";
    }

    private static SSLContext trustAllContext() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            return context;
        } catch (Exception e) {
            throw new RpcException(
                    RpcException.INTERNAL_ERROR, "Could not disable TLS verification", e);
        }
    }

    private static SSLParameters noHostnameVerification() {
        SSLParameters parameters = new SSLParameters();
        parameters.setEndpointIdentificationAlgorithm(null);
        return parameters;
    }
}
