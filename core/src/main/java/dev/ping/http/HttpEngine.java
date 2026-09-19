package dev.ping.http;

import dev.ping.rpc.RpcException;

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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    private final Map<String, Exchange> inFlight = new ConcurrentHashMap<>();

    /**
     * An in-flight request and whether we asked it to stop.
     *
     * <p>The intent is tracked explicitly because the JDK does not report it reliably: a
     * cancelled exchange may surface as {@code CancellationException} or as a wrapped I/O
     * failure depending on how far it had progressed. Only the caller knows the difference
     * between "the network broke" and "the user pressed stop".
     */
    private record Exchange(CompletableFuture<?> future, AtomicBoolean cancelled) {
    }

    /** Built per request: redirect policy and TLS trust are per-request settings. */
    private HttpClient clientFor(RequestSpec spec) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofMillis(spec.timeoutOrDefault()))
                .followRedirects(redirectPolicy(spec.redirects()));

        if (!spec.verifyTlsOrDefault()) {
            builder.sslContext(trustAllContext()).sslParameters(noHostnameVerification());
        }
        return builder.build();
    }

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

    public ResponseData send(RequestSpec spec) {
        URI uri = buildUri(spec);
        HttpRequest request = buildRequest(spec, uri);
        String requestId = spec.requestId() == null ? UUID.randomUUID().toString() : spec.requestId();

        // Resolved before dispatch so the cost is attributable; the client's own lookup then
        // hits the JDK cache. Failure is not fatal: report the timing as unknown, not zero.
        Long dnsMs = measureDns(uri.getHost());

        HttpClient client = clientFor(spec);
        long startedAt = System.nanoTime();

        CompletableFuture<HttpResponse<InputStream>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        AtomicBoolean cancelled = new AtomicBoolean();
        inFlight.put(requestId, new Exchange(future, cancelled));

        try {
            HttpResponse<InputStream> response = future.join();
            long ttfbMs = millisSince(startedAt);

            long bodyStartedAt = System.nanoTime();
            Payload payload = readBody(response.body(), spec.maxBodyBytesOrDefault());
            long downloadMs = millisSince(bodyStartedAt);

            return assemble(response, payload, new ResponseData.Timing(
                    dnsMs, ttfbMs, downloadMs, millisSince(startedAt)));
        } catch (CancellationException e) {
            throw cancelledException();
        } catch (CompletionException e) {
            if (cancelled.get()) {
                throw cancelledException();
            }
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RpcException(RpcException.REQUEST_FAILED, describe(cause), cause);
        } catch (IOException e) {
            if (cancelled.get()) {
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
        exchange.future().cancel(true);
        return true;
    }

    private static RpcException cancelledException() {
        return new RpcException(RpcException.REQUEST_CANCELLED, "Request cancelled");
    }

    // --- request construction ------------------------------------------------------------

    private static URI buildUri(RequestSpec spec) {
        if (spec.url() == null || spec.url().isBlank()) {
            throw RpcException.invalidParams("A url is required");
        }

        String url = spec.url().trim();
        String encodedQuery = encodeQuery(spec.query());
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

    private static String encodeQuery(List<RequestSpec.Param> query) {
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
            encoded.append(urlEncode(param.name())).append('=').append(urlEncode(param.value()));
        }
        return encoded.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private HttpRequest buildRequest(RequestSpec spec, URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(spec.timeoutOrDefault()));

        BodyPayload body = buildBody(spec.body());
        builder.method(spec.methodOrDefault(), body.publisher());

        boolean contentTypeSet = false;
        if (spec.headers() != null) {
            for (RequestSpec.Param header : spec.headers()) {
                if (!header.isEnabled() || header.name() == null || header.name().isBlank()) {
                    continue;
                }
                String name = header.name().trim();
                if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    // The JDK owns these; setting one throws IllegalArgumentException.
                    continue;
                }
                builder.header(name, header.value() == null ? "" : header.value());
                contentTypeSet |= name.equalsIgnoreCase("content-type");
            }
        }

        // An explicit header always wins over the type implied by the body mode.
        if (!contentTypeSet && body.contentType() != null) {
            builder.header("Content-Type", body.contentType());
        }
        return builder.build();
    }

    private record BodyPayload(HttpRequest.BodyPublisher publisher, String contentType) {
    }

    private static BodyPayload buildBody(RequestSpec.Body body) {
        if (body == null || body.type() == null || body.type().equalsIgnoreCase("none")) {
            return new BodyPayload(HttpRequest.BodyPublishers.noBody(), null);
        }

        String explicit = body.contentType();
        return switch (body.type().toLowerCase(Locale.ROOT)) {
            case "json" -> new BodyPayload(
                    ofString(body.content()),
                    explicit == null ? "application/json" : explicit);
            case "raw" -> new BodyPayload(
                    ofString(body.content()),
                    explicit == null ? "text/plain" : explicit);
            case "form" -> new BodyPayload(
                    ofString(encodeQuery(body.fields())),
                    explicit == null ? "application/x-www-form-urlencoded" : explicit);
            case "multipart" -> multipart(body.fields());
            default -> throw RpcException.invalidParams("Unknown body type: " + body.type());
        };
    }

    private static HttpRequest.BodyPublisher ofString(String content) {
        return HttpRequest.BodyPublishers.ofString(
                content == null ? "" : content, StandardCharsets.UTF_8);
    }

    private static BodyPayload multipart(List<RequestSpec.Param> fields) {
        String boundary = "PingBoundary" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder payload = new StringBuilder();

        if (fields != null) {
            for (RequestSpec.Param field : fields) {
                if (!field.isEnabled() || field.name() == null || field.name().isBlank()) {
                    continue;
                }
                payload.append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"")
                        .append(field.name()).append("\"\r\n\r\n")
                        .append(field.value() == null ? "" : field.value()).append("\r\n");
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
     */
    private static Payload readBody(InputStream stream, int cap) throws IOException {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;

        try (stream) {
            int read;
            while ((read = stream.read(chunk)) != -1) {
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
            HttpResponse<InputStream> response, Payload payload, ResponseData.Timing timing) {

        List<ResponseData.Header> headers = new ArrayList<>();
        response.headers().map().forEach((name, values) ->
                values.forEach(value -> headers.add(new ResponseData.Header(name, value))));
        headers.sort(Comparator.comparing(ResponseData.Header::name));

        String contentType = response.headers().firstValue("content-type").orElse(null);
        Charset charset = charsetOf(contentType);
        boolean textual = isTextual(contentType);

        // Truncated text is still worth showing; the flag tells the UI it is partial.
        String content = textual ? new String(payload.kept(), charset) : null;

        ResponseData.BodyData body = new ResponseData.BodyData(
                content, payload.truncated(), payload.total(), textual,
                contentType, charset.name());

        return new ResponseData(
                response.statusCode(),
                response.version().name(),
                headers,
                body,
                timing,
                redirectChain(response));
    }

    /** {@code previousResponse} walks backwards, so the collected chain is reversed to oldest-first. */
    private static List<ResponseData.Redirect> redirectChain(HttpResponse<?> response) {
        List<ResponseData.Redirect> chain = new ArrayList<>();
        Optional<? extends HttpResponse<?>> previous = response.previousResponse();

        while (previous.isPresent()) {
            HttpResponse<?> hop = previous.get();
            chain.add(new ResponseData.Redirect(
                    hop.statusCode(),
                    hop.uri().toString(),
                    hop.headers().firstValue("location").orElse(null)));
            previous = hop.previousResponse();
        }
        return chain.reversed();
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
