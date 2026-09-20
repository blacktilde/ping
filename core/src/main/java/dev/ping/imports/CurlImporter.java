package dev.ping.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.http.RequestSpec;
import dev.ping.imports.ShellTokenizer.Word;
import dev.ping.rpc.RpcException;
import dev.ping.store.StoredRequest;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a pasted {@code curl} command into a request.
 *
 * <p>The result is a draft for the editor, not a file, so nothing here touches the store.
 * Credentials land in the auth fields, which the shell already moves into {@code safeStorage}
 * on save. Everything curl can express that a request cannot is reported as a warning.
 */
public final class CurlImporter {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Short options that take a value and shape the request. */
    private static final String SHORT_MAPPED_ARG = "XHduFmAeb";

    /** Short options that take a value we have no use for. */
    private static final String SHORT_IGNORED_ARG = "oxETwKcCDryYzUPQt";

    /** Options with no effect on the request itself; ignored without comment. */
    private static final Set<String> QUIET = Set.of(
            "-s", "-S", "-v", "-i", "-f", "-N", "-g", "-4", "-6", "-O", "-#",
            "--silent", "--show-error", "--verbose", "--include", "--fail", "--fail-with-body",
            "--compressed", "--globoff", "--no-buffer", "--progress-bar", "--no-progress-meter",
            "--ipv4", "--ipv6", "--remote-name", "--no-keepalive", "--tcp-nodelay", "--path-as-is");

    private static final Set<String> LONG_MAPPED_ARG = Set.of(
            "--request", "--header", "--data", "--data-raw", "--data-binary", "--data-ascii",
            "--data-urlencode", "--user", "--form", "--max-time", "--user-agent", "--referer",
            "--cookie", "--url", "--json", "--oauth2-bearer");

    private static final Set<String> LONG_IGNORED_ARG = Set.of(
            "--output", "--proxy", "--cert", "--key", "--cacert", "--capath", "--resolve",
            "--connect-timeout", "--retry", "--write-out", "--upload-file", "--config",
            "--cookie-jar", "--dump-header", "--interface", "--limit-rate", "--proxy-user",
            "--proxy-header", "--pinnedpubkey", "--keepalive-time", "--max-redirs", "--range",
            "--aws-sigv4", "--connect-to", "--cert-type", "--key-type", "--pass", "--retry-delay",
            "--retry-max-time", "--time-cond", "--user-cert");

    private static final Set<String> LONG_UNSUPPORTED = Set.of(
            "--http2", "--http1.1", "--http1.0", "--http2-prior-knowledge", "--http3",
            "--digest", "--ntlm", "--negotiate", "--anyauth", "--proxy-insecure");

    private static final Pattern SENSITIVE_HEADER = Pattern.compile(
            "(?i).*(api[-_]?key|token|secret|password|passwd|cookie|authorization).*");

    private CurlImporter() {
    }

    public static ImportResult parse(String command) {
        if (command == null || command.isBlank()) {
            throw RpcException.invalidParams("import.curl requires a command");
        }
        List<Word> words = ShellTokenizer.split(command);
        int start = 0;
        if (!words.isEmpty() && words.get(0).text().equals("$")) {
            start = 1;
        }
        if (start >= words.size() || !isCurl(words.get(start).text())) {
            throw RpcException.invalidParams("Not a curl command");
        }
        return new Parse(words, start + 1).run();
    }

    /** True for text that starts like a curl invocation; the renderer uses the same test. */
    public static boolean isCurl(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return lower.equals("curl") || lower.equals("curl.exe");
    }

    private record Piece(String text) {
    }

    private static final class Parse {
        private final List<Word> words;
        private final List<String> warnings = new ArrayList<>();
        private final List<RequestSpec.Param> headers = new ArrayList<>();
        private final List<Piece> data = new ArrayList<>();
        private final List<RequestSpec.Param> form = new ArrayList<>();
        private int index;

        private String url;
        private String method;
        private boolean head;
        private boolean get;
        private boolean follow;
        private boolean insecure;
        private boolean json;
        private Integer timeoutMs;
        private RequestSpec.Auth auth;
        private boolean sawExpansion;

        Parse(List<Word> words, int index) {
            this.words = words;
            this.index = index;
        }

        ImportResult run() {
            boolean literal = false;
            while (index < words.size()) {
                Word word = words.get(index++);
                String token = word.text();
                note(word);
                if (literal || !token.startsWith("-") || token.equals("-")) {
                    positional(token);
                } else if (token.equals("--")) {
                    literal = true;
                } else if (token.startsWith("--")) {
                    longOption(token);
                } else {
                    shortOptions(token);
                }
            }
            return build();
        }

        private void note(Word word) {
            sawExpansion |= word.expands();
        }

        private String value(String option) {
            if (index >= words.size()) {
                throw RpcException.invalidParams("The curl option " + option + " needs a value");
            }
            Word word = words.get(index++);
            note(word);
            return word.text();
        }

        private void positional(String token) {
            if (url == null) {
                url = token;
            } else {
                warnings.add("Only the first URL is imported; ignored " + token);
            }
        }

        private void shortOptions(String token) {
            for (int i = 1; i < token.length(); i++) {
                char option = token.charAt(i);
                String name = "-" + option;
                if (SHORT_MAPPED_ARG.indexOf(option) >= 0 || SHORT_IGNORED_ARG.indexOf(option) >= 0) {
                    String rest = token.substring(i + 1);
                    String argument = rest.isEmpty() ? value(name) : rest;
                    if (SHORT_MAPPED_ARG.indexOf(option) >= 0) {
                        mapped(name, argument);
                    } else {
                        warnings.add("Ignored " + name + " " + argument
                                + ": it has no equivalent in a request");
                    }
                    return;
                }
                switch (option) {
                    case 'L' -> follow = true;
                    case 'k' -> insecure = true;
                    case 'G' -> get = true;
                    case 'I' -> head = true;
                    default -> {
                        if (!QUIET.contains(name)) {
                            warnings.add("Ignored unknown curl option " + name);
                        }
                    }
                }
            }
        }

        private void longOption(String token) {
            int eq = token.indexOf('=');
            String name = eq < 0 ? token : token.substring(0, eq);
            String inline = eq < 0 ? null : token.substring(eq + 1);

            if (LONG_MAPPED_ARG.contains(name)) {
                mapped(name, inline != null ? inline : value(name));
            } else if (LONG_IGNORED_ARG.contains(name)) {
                String argument = inline != null ? inline : value(name);
                warnings.add("Ignored " + name + " " + argument + ": it has no equivalent in a request");
            } else if (LONG_UNSUPPORTED.contains(name)) {
                warnings.add("Ignored " + name + ": not supported yet");
            } else if (name.equals("--location") || name.equals("--location-trusted")) {
                follow = true;
            } else if (name.equals("--insecure")) {
                insecure = true;
            } else if (name.equals("--get")) {
                get = true;
            } else if (name.equals("--head")) {
                head = true;
            } else if (!QUIET.contains(name)) {
                warnings.add("Ignored unknown curl option " + name);
            }
        }

        private void mapped(String option, String argument) {
            switch (option) {
                case "-X", "--request" -> method = argument.toUpperCase(Locale.ROOT);
                case "--url" -> positional(argument);
                case "-H", "--header" -> header(argument);
                case "-d", "--data", "--data-ascii", "--data-binary" -> fileOr(argument, "--data", () ->
                        data.add(new Piece(argument)));
                case "--data-raw" -> data.add(new Piece(argument));
                case "--data-urlencode" -> urlencoded(argument);
                case "--json" -> {
                    json = true;
                    fileOr(argument, "--json", () -> data.add(new Piece(argument)));
                }
                case "-F", "--form" -> formPart(argument);
                case "-u", "--user" -> user(argument);
                case "--oauth2-bearer" -> auth = RequestSpec.Auth.bearer(argument);
                case "-m", "--max-time" -> maxTime(argument);
                case "-A", "--user-agent" -> headers.add(new RequestSpec.Param("User-Agent", argument, true));
                case "-e", "--referer" -> headers.add(new RequestSpec.Param("Referer", argument, true));
                case "-b", "--cookie" -> cookie(argument);
                default -> warnings.add("Ignored " + option);
            }
        }

        /** {@code @file} reads from disk, which a request cannot yet; say so instead of sending "@file". */
        private void fileOr(String argument, String option, Runnable literal) {
            if (argument.startsWith("@")) {
                warnings.add(option + " " + argument + " reads a file; file bodies are not supported yet, "
                        + "so the body was left out");
            } else {
                literal.run();
            }
        }

        private void urlencoded(String argument) {
            if (argument.startsWith("@") || (argument.contains("@") && !argument.contains("="))) {
                warnings.add("--data-urlencode " + argument + " reads a file; file bodies are not "
                        + "supported yet, so it was left out");
                return;
            }
            int eq = argument.indexOf('=');
            String piece;
            if (eq < 0) {
                piece = encode(argument);
            } else if (eq == 0) {
                piece = encode(argument.substring(1));
            } else {
                piece = argument.substring(0, eq) + "=" + encode(argument.substring(eq + 1));
            }
            data.add(new Piece(piece));
        }

        private void formPart(String argument) {
            int eq = argument.indexOf('=');
            if (eq <= 0) {
                warnings.add("Ignored malformed form field " + argument);
                return;
            }
            String name = argument.substring(0, eq);
            String content = argument.substring(eq + 1);
            if (content.startsWith("@") || content.startsWith("<")) {
                warnings.add("Form field " + name + " uploads a file; file parts are not supported yet, "
                        + "so it was left out");
                return;
            }
            int type = content.lastIndexOf(";type=");
            if (type >= 0) {
                content = content.substring(0, type);
            }
            form.add(new RequestSpec.Param(name, content, true));
        }

        private void user(String argument) {
            int colon = argument.indexOf(':');
            String username = colon < 0 ? argument : argument.substring(0, colon);
            String password = colon < 0 ? "" : argument.substring(colon + 1);
            // An empty password is stored as absent, which is how it round-trips through YAML.
            auth = RequestSpec.Auth.basic(username, password.isEmpty() ? null : password);
        }

        private void maxTime(String argument) {
            try {
                timeoutMs = (int) Math.round(Double.parseDouble(argument) * 1000);
            } catch (NumberFormatException e) {
                warnings.add("Ignored --max-time " + argument + ": not a number");
            }
        }

        private void cookie(String argument) {
            if (argument.contains("=")) {
                headers.add(new RequestSpec.Param("Cookie", argument, true));
            } else {
                warnings.add("Ignored cookie file " + argument + ": cookie files are not supported");
            }
        }

        private void header(String argument) {
            int colon = argument.indexOf(':');
            if (colon <= 0) {
                warnings.add("Ignored malformed header \"" + argument + "\"");
                return;
            }
            String name = argument.substring(0, colon).trim();
            String value = argument.substring(colon + 1).trim();
            if (value.isEmpty()) {
                warnings.add("Ignored header " + name + ": an empty value removes a header in curl, "
                        + "which a request cannot express");
                return;
            }
            if (name.equalsIgnoreCase("authorization") && authorization(value)) {
                return;
            }
            headers.add(new RequestSpec.Param(name, value, true));
        }

        /** @return true when the header became the auth config and should not also be a header */
        private boolean authorization(String value) {
            int space = value.indexOf(' ');
            String scheme = space < 0 ? value : value.substring(0, space);
            String credentials = space < 0 ? "" : value.substring(space + 1).trim();
            if (scheme.equalsIgnoreCase("bearer") && !credentials.isEmpty()) {
                if (auth == null) {
                    auth = RequestSpec.Auth.bearer(credentials);
                }
                return true;
            }
            if (scheme.equalsIgnoreCase("basic") && !credentials.isEmpty()) {
                try {
                    String decoded = new String(Base64.getDecoder().decode(credentials), StandardCharsets.UTF_8);
                    if (auth == null) {
                        int colon = decoded.indexOf(':');
                        String password = colon < 0 ? "" : decoded.substring(colon + 1);
                        auth = RequestSpec.Auth.basic(
                                colon < 0 ? decoded : decoded.substring(0, colon),
                                password.isEmpty() ? null : password);
                    }
                    return true;
                } catch (IllegalArgumentException e) {
                    return false; // Not base64: keep it as a header rather than lose it.
                }
            }
            return false;
        }

        private ImportResult build() {
            if (url == null) {
                throw RpcException.invalidParams("No URL found in the curl command");
            }

            List<RequestSpec.Param> query = new ArrayList<>();
            String target = splitUrl(url, query);

            String joined = String.join("&", data.stream().map(Piece::text).toList());
            boolean hasData = !data.isEmpty();
            if (get && hasData) {
                query.addAll(pairs(joined));
                hasData = false;
            }
            if (hasData && !form.isEmpty()) {
                warnings.add("The command has both -d and -F; only the form fields were imported");
                hasData = false;
            }

            String contentType = takeHeader("content-type");
            RequestSpec.Body body = bodyOf(hasData, joined, contentType);

            String resolvedMethod = method != null ? method
                    : head ? "HEAD"
                    : (body != null && !"none".equals(body.type())) ? "POST" : "GET";

            if (sawExpansion) {
                warnings.add("The command uses shell variables or substitutions such as $TOKEN; "
                        + "they were left as written. Replace them with {{variables}}.");
            }
            for (RequestSpec.Param header : headers) {
                if (SENSITIVE_HEADER.matcher(header.name()).matches()) {
                    warnings.add("Header " + header.name() + " may hold a credential; it is kept "
                            + "as written in the request file");
                }
            }

            StoredRequest request = new StoredRequest(
                    nameOf(target), resolvedMethod, target, query, headers, body, auth,
                    timeoutMs, follow ? "normal" : null, insecure ? Boolean.FALSE : null, null, null);
            return new ImportResult(request, warnings);
        }

        /** Removes and returns a header's value, so a mode that implies it does not repeat it. */
        private String takeHeader(String name) {
            for (int i = 0; i < headers.size(); i++) {
                if (headers.get(i).name().equalsIgnoreCase(name)) {
                    return headers.remove(i).value();
                }
            }
            return null;
        }

        private RequestSpec.Body bodyOf(boolean hasData, String joined, String contentType) {
            if (!form.isEmpty()) {
                if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
                    headers.add(new RequestSpec.Param("Content-Type", contentType, true));
                }
                return new RequestSpec.Body("multipart", null, null, form);
            }
            if (!hasData) {
                if (contentType != null) {
                    headers.add(new RequestSpec.Param("Content-Type", contentType, true));
                }
                return null;
            }

            String bare = contentType == null ? null : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (json || (bare != null && bare.contains("json"))) {
                boolean plain = contentType == null || contentType.trim().equalsIgnoreCase("application/json");
                return new RequestSpec.Body("json", joined, plain ? null : contentType, null);
            }
            if ("application/x-www-form-urlencoded".equals(bare)) {
                if (!contentType.trim().equalsIgnoreCase("application/x-www-form-urlencoded")) {
                    headers.add(new RequestSpec.Param("Content-Type", contentType, true));
                }
                return new RequestSpec.Body("form", null, null, pairs(joined));
            }
            if (contentType != null) {
                return new RequestSpec.Body("raw", joined, contentType, null);
            }
            // curl sends -d as form data unless told otherwise, but JSON is what people mean.
            if (looksLikeJson(joined)) {
                return new RequestSpec.Body("json", joined, null, null);
            }
            if (isPairs(joined)) {
                return new RequestSpec.Body("form", null, null, pairs(joined));
            }
            return new RequestSpec.Body("raw", joined, null, null);
        }
    }

    private static boolean looksLikeJson(String text) {
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return false;
        }
        try {
            JSON.readTree(trimmed);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPairs(String text) {
        if (text.isBlank() || text.contains("\n")) {
            return false;
        }
        for (String segment : text.split("&", -1)) {
            if (segment.indexOf('=') <= 0) {
                return false;
            }
        }
        return true;
    }

    /** Splits {@code a=1&b=2} into decoded params, so the engine's own encoding is not applied twice. */
    private static List<RequestSpec.Param> pairs(String text) {
        List<RequestSpec.Param> params = new ArrayList<>();
        for (String segment : text.split("&")) {
            if (segment.isEmpty()) {
                continue;
            }
            int eq = segment.indexOf('=');
            String name = eq < 0 ? segment : segment.substring(0, eq);
            String value = eq < 0 ? "" : segment.substring(eq + 1);
            params.add(new RequestSpec.Param(decode(name), decode(value), true));
        }
        return params;
    }

    /**
     * Drops the fragment, moves the query into params and adds a scheme when there is none,
     * as curl does. Returns the bare address.
     */
    private static String splitUrl(String raw, List<RequestSpec.Param> query) {
        String address = raw.trim();
        int hash = address.indexOf('#');
        if (hash >= 0) {
            address = address.substring(0, hash);
        }
        int question = address.indexOf('?');
        if (question >= 0) {
            query.addAll(pairs(address.substring(question + 1)));
            address = address.substring(0, question);
        }
        if (!address.contains("://") && !address.startsWith("{{")) {
            address = "http://" + address;
        }
        return address;
    }

    private static String nameOf(String address) {
        String name = address.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty()) {
            return "Imported request";
        }
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value; // A stray % is a literal, not an error worth failing the import over.
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
