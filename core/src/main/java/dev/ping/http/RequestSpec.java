package dev.ping.http;

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
        Integer timeoutMs,
        String redirects,
        Boolean verifyTls,
        Integer maxBodyBytes) {

    /** A name/value pair the user can disable without deleting. */
    public record Param(String name, String value, Boolean enabled) {
        public boolean isEnabled() {
            return enabled == null || enabled;
        }
    }

    /**
     * @param type    one of {@code none}, {@code json}, {@code raw}, {@code form}, {@code multipart}
     * @param content the literal payload for {@code json} and {@code raw}
     * @param fields  the field list for {@code form} and {@code multipart}
     */
    public record Body(String type, String content, String contentType, List<Param> fields) {
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

    public int maxBodyBytesOrDefault() {
        return maxBodyBytes == null || maxBodyBytes <= 0 ? DEFAULT_MAX_BODY_BYTES : maxBodyBytes;
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
        private Integer timeoutMs;
        private String redirects;
        private Boolean verifyTls;
        private Integer maxBodyBytes;

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

        /** Lets tests exercise the multipart framing directly, without going through Jackson. */
        public Builder multipartBody(List<Param> fields) {
            this.body = new Body("multipart", null, null, fields);
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

        public RequestSpec build() {
            return new RequestSpec(requestId, method, url, query, headers, body,
                    timeoutMs, redirects, verifyTls, maxBodyBytes);
        }
    }
}
