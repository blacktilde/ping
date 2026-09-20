package dev.ping.http;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import dev.ping.rpc.RpcException;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

/**
 * A request as the UI describes it, before any of it is turned into wire format.
 *
 * <p>Every boxed field is optional: the UI omits what the user has not set, and
 * {@link HttpEngine} applies the defaults. Keeping the defaults in one place means the
 * renderer and a future CLI cannot disagree about them.
 */
public record RequestSpec(
        String requestId,
        String method,
        String url,
        List<Param> query,
        List<Param> headers,
        Body body,
        Auth auth,
        Integer timeoutMs,
        String redirects,
        Boolean verifyTls,
        Integer maxBodyBytes,
        List<Assertion> asserts,
        List<Capture> capture,
        Boolean cookies,
        String httpVersion) {

    /**
     * A name/value pair the user can disable without deleting.
     *
     * <p>In a multipart body a row with a {@code file} is a file part rather than a text field:
     * {@code name} is the field name, {@code file} the path to read (literal, never
     * interpolated), and {@code filename} and {@code contentType} optionally override what
     * is sent for it. Rows elsewhere never carry those three.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Param(
            String name, String value, Boolean enabled, String file, String filename, String contentType) {

        public Param(String name, String value, Boolean enabled) {
            this(name, value, enabled, null, null, null);
        }

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        /**
         * True for a multipart file part. A part with a content type or a filename is a file part
         * even before a file is chosen (an import can leave the choice to the user), so the row
         * keeps its shape through a YAML file that omits the empty path. Named so Jackson does
         * not read it as a second {@code file} property.
         */
        public boolean usesFile() {
            return file != null || filename != null || contentType != null;
        }
    }

    /**
     * @param type    one of {@code none}, {@code json}, {@code raw}, {@code form},
     *                {@code multipart}, {@code file}
     * @param content the literal payload for {@code json} and {@code raw}
     * @param fields  the field list for {@code form} and {@code multipart}
     * @param file    the path of the file sent as the whole body for {@code file}; literal
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String type, String content, String contentType, List<Param> fields, String file) {

        public Body(String type, String content, String contentType, List<Param> fields) {
            this(type, content, contentType, fields, null);
        }
    }

    /**
     * How to authenticate one request.
     *
     * <p>Field values are interpolated like any other part of the request, so a secret is
     * just a variable the shell resolved from `safeStorage`; nothing here has to know which
     * values are sensitive. Only the fields a type uses are read.
     *
     * @param type         bearer, basic, api-key, oauth2-client-credentials,
     *                     oauth2-authorization-code
     * @param key          api-key: the header or query parameter name
     * @param in           api-key: {@code header} or {@code query}
     * @param tokenUrl     oauth2: where to exchange a grant for a token
     * @param authUrl      oauth2 authorization code: where the browser is sent
     * @param redirectUri  oauth2 authorization code: loopback redirect; the core defaults it
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Auth(
            String type,
            String username,
            String password,
            String token,
            String key,
            String value,
            String in,
            String tokenUrl,
            String authUrl,
            String clientId,
            String clientSecret,
            String scopes,
            String redirectUri,
            String accessToken,
            String refreshToken,
            Long expiresAtMillis) {

        @JsonIgnore
        public boolean isConfigured() {
            return type != null && !type.isBlank() && !type.equalsIgnoreCase("none");
        }

        public static Auth basic(String username, String password) {
            return new Auth("basic", username, password, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
        }

        public static Auth bearer(String token) {
            return new Auth("bearer", null, null, token, null, null, null,
                    null, null, null, null, null, null, null, null, null);
        }

        public static Auth apiKey(String name, String value, String in) {
            return new Auth("api-key", null, null, null, name, value, in,
                    null, null, null, null, null, null, null, null, null);
        }

        public static Auth clientCredentials(
                String tokenUrl, String clientId, String clientSecret, String scopes) {
            return new Auth("oauth2-client-credentials", null, null, null, null, null, null,
                    tokenUrl, null, clientId, clientSecret, scopes, null, null, null, null);
        }

        public static Auth authorizationCode(
                String authUrl, String tokenUrl, String clientId, String clientSecret, String scopes) {
            return new Auth("oauth2-authorization-code", null, null, null, null, null, null,
                    tokenUrl, authUrl, clientId, clientSecret, scopes, null, null, null, null);
        }

        /** A copy carrying tokens the shell restored from `safeStorage`. */
        public Auth withTokens(String accessToken, String refreshToken, Long expiresAtMillis) {
            return new Auth(type, username, password, token, key, value, in, tokenUrl, authUrl,
                    clientId, clientSecret, scopes, redirectUri, accessToken, refreshToken,
                    expiresAtMillis);
        }
    }

    public static final int DEFAULT_TIMEOUT_MS = 30_000;

    /**
     * Bodies above this are counted but not buffered. A REST client must survive being
     * pointed at a download endpoint, and the renderer cannot display 2 GB anyway.
     */
    public static final int DEFAULT_MAX_BODY_BYTES = 10 * 1024 * 1024;

    public int timeoutOrDefault() {
        return timeoutMs == null || timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
    }

    /**
     * Uploads get longer by default: the request timeout runs until response headers arrive,
     * which for a large file includes sending all of it. An explicit {@code timeoutMs} wins.
     */
    public static final int DEFAULT_UPLOAD_TIMEOUT_MS = 10 * 60 * 1000;

    public int requestTimeoutMs() {
        if (timeoutMs != null && timeoutMs > 0) {
            return timeoutMs;
        }
        return hasFile() ? DEFAULT_UPLOAD_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;
    }

    /** True when the body reads a file: a binary body, or an enabled file part. */
    public boolean hasFile() {
        if (body == null || body.type() == null) {
            return false;
        }
        if (body.type().equalsIgnoreCase("file")) {
            return true;
        }
        return body.fields() != null && body.fields().stream().anyMatch(p -> p.isEnabled() && p.usesFile());
    }

    public int maxBodyBytesOrDefault() {
        return maxBodyBytes == null || maxBodyBytes <= 0 ? DEFAULT_MAX_BODY_BYTES : maxBodyBytes;
    }

    /** Whether the cookie jar applies to this request; absent means yes. */
    public boolean cookiesOrDefault() {
        return cookies == null || cookies;
    }

    /**
     * The pinned protocol version, or null for the client's own choice. {@code "2"} means
     * "prefer HTTP/2": ALPN can still settle on 1.1, and a plain-text {@code http://} exchange
     * upgrades or stays on 1.1 regardless.
     */
    public HttpClient.Version httpVersionOrNull() {
        if (httpVersion == null || httpVersion.isBlank()) {
            return null;
        }
        return switch (httpVersion.trim()) {
            case "1.1" -> HttpClient.Version.HTTP_1_1;
            case "2" -> HttpClient.Version.HTTP_2;
            default -> throw RpcException.invalidParams(
                    "Unknown HTTP version: " + httpVersion + " (use \"1.1\" or \"2\")");
        };
    }

    public boolean verifyTlsOrDefault() {
        return verifyTls == null || verifyTls;
    }

    public String methodOrDefault() {
        return method == null || method.isBlank() ? "GET" : method.toUpperCase();
    }

    /**
     * Assembles a spec in code rather than from JSON.
     *
     * <p>The UI always arrives through Jackson, but tests and the future CLI runner need a
     * way to build one directly without naming every optional field.
     */
    public static final class Builder {

        private final String url;
        private final List<Param> query = new ArrayList<>();
        private final List<Param> headers = new ArrayList<>();
        private String requestId;
        private String method = "GET";
        private Body body;
        private Auth auth;
        private Integer timeoutMs;
        private String redirects;
        private Boolean verifyTls;
        private Integer maxBodyBytes;
        private final List<Assertion> asserts = new ArrayList<>();
        private final List<Capture> capture = new ArrayList<>();
        private Boolean cookies;
        private String httpVersion;

        public Builder(String url) {
            this.url = url;
        }

        public Builder requestId(String value) {
            this.requestId = value;
            return this;
        }

        public Builder method(String value) {
            this.method = value;
            return this;
        }

        public Builder query(String name, String value) {
            return query(name, value, true);
        }

        public Builder query(String name, String value, boolean enabled) {
            query.add(new Param(name, value, enabled));
            return this;
        }

        public Builder header(String name, String value) {
            return header(name, value, true);
        }

        public Builder header(String name, String value, boolean enabled) {
            headers.add(new Param(name, value, enabled));
            return this;
        }

        public Builder jsonBody(String content) {
            this.body = new Body("json", content, null, null);
            return this;
        }

        public Builder rawBody(String content, String contentType) {
            this.body = new Body("raw", content, contentType, null);
            return this;
        }

        /** Used to prove an unrecognised body mode is rejected rather than silently ignored. */
        public Builder rawBodyOfType(String type) {
            this.body = new Body(type, "", null, null);
            return this;
        }

        public Builder formBody(String name, String value) {
            List<Param> fields = new ArrayList<>();
            fields.add(new Param(name, value, true));
            this.body = new Body("form", null, null, fields);
            return this;
        }

        /** A binary body read from {@code path}. */
        public Builder fileBody(String path, String contentType) {
            this.body = new Body("file", null, contentType, null, path);
            return this;
        }

        /** Lets tests exercise the multipart framing directly, without going through Jackson. */
        public Builder multipartBody(List<Param> fields) {
            this.body = new Body("multipart", null, null, fields);
            return this;
        }

        public Builder auth(Auth value) {
            this.auth = value;
            return this;
        }

        public Builder timeoutMs(int value) {
            this.timeoutMs = value;
            return this;
        }

        public Builder redirects(String value) {
            this.redirects = value;
            return this;
        }

        public Builder verifyTls(boolean value) {
            this.verifyTls = value;
            return this;
        }

        public Builder maxBodyBytes(int value) {
            this.maxBodyBytes = value;
            return this;
        }

        public Builder assertion(String type, String target, String op, String expected) {
            asserts.add(new Assertion(type, target, op, expected, null));
            return this;
        }

        /** Opts this request out of (or back into) the cookie jar. */
        public Builder cookies(boolean value) {
            this.cookies = value;
            return this;
        }

        /** Pins the protocol version: {@code "1.1"} or {@code "2"} (preferred). */
        public Builder httpVersion(String value) {
            this.httpVersion = value;
            return this;
        }

        public Builder capture(String name, String source, String target) {
            capture.add(new Capture(name, source, target, null));
            return this;
        }

        public RequestSpec build() {
            return new RequestSpec(requestId, method, url, query, headers, body, auth,
                    timeoutMs, redirects, verifyTls, maxBodyBytes, asserts, capture, cookies, httpVersion);
        }
    }
}
