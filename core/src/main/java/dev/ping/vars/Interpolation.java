package dev.ping.vars;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code {{name}}} placeholders.
 *
 * <p>An unknown name is left exactly as written, so a half-configured request shows what is
 * missing on the wire rather than silently sending an empty value. This sits in {@code vars}
 * because both the request builder and the auth applier use it, and they must agree.
 */
public final class Interpolation {

    /** {@code {{ name }}}, tolerating whitespace inside the braces. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");

    private Interpolation() {
    }

    public static String apply(String value, Map<String, String> variables) {
        if (value == null || variables == null || variables.isEmpty() || value.indexOf("{{") < 0) {
            return value;
        }
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String replacement = variables.get(matcher.group(1).trim());
            matcher.appendReplacement(resolved,
                    Matcher.quoteReplacement(replacement == null ? matcher.group(0) : replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }
}
