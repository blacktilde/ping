package dev.ping.imports;

import dev.ping.rpc.RpcException;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a command line into words the way a POSIX shell would, without running anything.
 *
 * <p>Handles single quotes, double quotes, backslash escapes, line continuations and the
 * {@code $'...'} form browsers emit for bodies with special characters. Nothing is ever
 * expanded: a word that contained a variable or substitution outside single quotes is flagged
 * so the caller can tell the user it was left as written.
 */
public final class ShellTokenizer {

    /** @param expands true when the word held an unquoted or double-quoted {@code $var} or backtick */
    public record Word(String text, boolean expands) {
    }

    private ShellTokenizer() {
    }

    public static List<Word> split(String input) {
        List<Word> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inWord = false;
        boolean expands = false;
        int n = input.length();
        int i = 0;

        while (i < n) {
            char c = input.charAt(i);

            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (inWord) {
                    words.add(new Word(current.toString(), expands));
                    current.setLength(0);
                    inWord = false;
                    expands = false;
                }
                i++;
                continue;
            }

            // A backslash-newline is removed entirely and does not start a word.
            int continuation = continuationLength(input, i);
            if (continuation > 0) {
                i += continuation;
                continue;
            }

            inWord = true;
            if (c == '\\') {
                if (i + 1 < n) {
                    current.append(input.charAt(i + 1));
                    i += 2;
                } else {
                    current.append('\\');
                    i++;
                }
            } else if (c == '\'') {
                int end = input.indexOf('\'', i + 1);
                if (end < 0) {
                    throw RpcException.invalidParams("Unterminated single quote in the command");
                }
                current.append(input, i + 1, end);
                i = end + 1;
            } else if (c == '"') {
                Quoted quoted = doubleQuoted(input, i + 1, current);
                expands |= quoted.expands();
                i = quoted.end();
            } else if (c == '$' && i + 1 < n && input.charAt(i + 1) == '\'') {
                i = ansiC(input, i + 2, current);
            } else {
                if ((c == '$' && i + 1 < n && startsExpansion(input.charAt(i + 1))) || c == '`') {
                    expands = true;
                }
                current.append(c);
                i++;
            }
        }
        if (inWord) {
            words.add(new Word(current.toString(), expands));
        }
        return words;
    }

    private static int continuationLength(String input, int i) {
        if (input.charAt(i) != '\\') {
            return 0;
        }
        if (i + 1 < input.length() && input.charAt(i + 1) == '\n') {
            return 2;
        }
        if (i + 2 < input.length() && input.charAt(i + 1) == '\r' && input.charAt(i + 2) == '\n') {
            return 3;
        }
        return 0;
    }

    private static boolean startsExpansion(char next) {
        return Character.isLetter(next) || next == '_' || next == '{' || next == '(';
    }

    /** @param end the index after the closing quote */
    private record Quoted(int end, boolean expands) {
    }

    private static Quoted doubleQuoted(String input, int start, StringBuilder out) {
        boolean expands = false;
        int n = input.length();
        int i = start;
        while (i < n) {
            char c = input.charAt(i);
            if (c == '"') {
                return new Quoted(i + 1, expands);
            }
            if (c == '\\' && i + 1 < n) {
                int continuation = continuationLength(input, i);
                if (continuation > 0) {
                    i += continuation;
                    continue;
                }
                char next = input.charAt(i + 1);
                if (next == '"' || next == '\\' || next == '$' || next == '`') {
                    out.append(next);
                    i += 2;
                    continue;
                }
            }
            if ((c == '$' && i + 1 < n && startsExpansion(input.charAt(i + 1))) || c == '`') {
                expands = true;
            }
            out.append(c);
            i++;
        }
        throw RpcException.invalidParams("Unterminated double quote in the command");
    }

    /** {@code $'...'}: C-style escapes, no expansion. Returns the index after the closing quote. */
    private static int ansiC(String input, int start, StringBuilder out) {
        int n = input.length();
        int i = start;
        while (i < n) {
            char c = input.charAt(i);
            if (c == '\'') {
                return i + 1;
            }
            if (c != '\\' || i + 1 >= n) {
                out.append(c);
                i++;
                continue;
            }
            char e = input.charAt(i + 1);
            i += 2;
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'a' -> out.append((char) 7);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'v' -> out.append((char) 11);
                case 'e', 'E' -> out.append((char) 27);
                case '\\', '\'', '"' -> out.append(e);
                case 'x' -> i = hex(input, i, 2, 'x', out);
                case 'u' -> i = hex(input, i, 4, 'u', out);
                case 'U' -> i = hex(input, i, 8, 'U', out);
                default -> {
                    if (e >= '0' && e <= '7') {
                        int value = e - '0';
                        int digits = 1;
                        while (digits < 3 && i < n && input.charAt(i) >= '0' && input.charAt(i) <= '7') {
                            value = value * 8 + (input.charAt(i) - '0');
                            i++;
                            digits++;
                        }
                        out.append((char) value);
                    } else {
                        out.append('\\').append(e);
                    }
                }
            }
        }
        throw RpcException.invalidParams("Unterminated $'...' quote in the command");
    }

    /** Reads up to {@code max} hex digits; an escape with none is kept literally. */
    private static int hex(String input, int start, int max, char letter, StringBuilder out) {
        int i = start;
        int value = 0;
        int digits = 0;
        while (digits < max && i < input.length() && Character.digit(input.charAt(i), 16) >= 0) {
            value = value * 16 + Character.digit(input.charAt(i), 16);
            i++;
            digits++;
        }
        if (digits == 0) {
            out.append('\\').append(letter);
        } else if (Character.isValidCodePoint(value)) {
            out.appendCodePoint(value);
        }
        return i;
    }
}
