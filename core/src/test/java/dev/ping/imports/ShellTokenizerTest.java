package dev.ping.imports;

import dev.ping.rpc.RpcException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellTokenizerTest {

    private static List<String> split(String input) {
        return ShellTokenizer.split(input).stream().map(ShellTokenizer.Word::text).toList();
    }

    @Test
    void splitsOnWhitespace() {
        assertEquals(List.of("curl", "-X", "GET", "url"), split("  curl\t-X  GET\nurl "));
        assertEquals(List.of(), split("   "));
    }

    @Test
    void singleQuotesAreLiteral() {
        assertEquals(List.of("a b", "$HOME", "c\\d"), split("'a b' '$HOME' 'c\\d'"));
    }

    @Test
    void doubleQuotesKeepSpacesAndUnescapeOnlyTheFourSpecials() {
        assertEquals(List.of("say \"hi\" $x `y` \\ \\n"), split("\"say \\\"hi\\\" \\$x \\`y\\` \\\\ \\n\""));
    }

    @Test
    void adjacentQuotedPartsJoinIntoOneWord() {
        assertEquals(List.of("it's"), split("it\"'\"s"));
        assertEquals(List.of("ab cd"), split("a'b c'd"));
    }

    @Test
    void anEmptyQuotedStringIsAWord() {
        assertEquals(List.of("a", "", "b"), split("a '' b"));
    }

    @Test
    void backslashEscapesOutsideQuotes() {
        assertEquals(List.of("a b", "c\"d"), split("a\\ b c\\\"d"));
    }

    @Test
    void lineContinuationsJoinLinesWithoutMakingEmptyWords() {
        assertEquals(List.of("curl", "-H", "x", "url"), split("curl \\\n  -H x \\\n  url"));
        assertEquals(List.of("curl", "url"), split("curl \\\r\n url"));
        assertEquals(List.of("ab"), split("a\\\nb"));
    }

    @Test
    void ansiCQuotingDecodesEscapes() {
        assertEquals(List.of("a\nb\tc"), split("$'a\\nb\\tc'"));
        assertEquals(List.of("it's \"q\" \\"), split("$'it\\'s \\\"q\\\" \\\\'"));
        assertEquals(List.of("A&\u00e9\ud83d\ude00"), split("$'\\x41\\u0026\\u00e9\\U0001F600'"));
        assertEquals(List.of("'"), split("$'\\047'"));
        assertEquals(List.of("\\q"), split("$'\\q'"), "unknown escapes stay literal");
    }

    @Test
    void flagsWordsWithExpansionsButNotQuotedLiterals() {
        List<ShellTokenizer.Word> words = ShellTokenizer.split(
                "a $TOKEN \"b ${X}\" 'c $D' \"d $(cmd)\" `e` \"price $5\" f$");
        assertFalse(words.get(0).expands());
        assertTrue(words.get(1).expands());
        assertTrue(words.get(2).expands());
        assertFalse(words.get(3).expands(), "single quotes never expand");
        assertTrue(words.get(4).expands());
        assertTrue(words.get(5).expands());
        assertFalse(words.get(6).expands(), "$5 is a positional parameter, not something to warn about");
        assertFalse(words.get(7).expands(), "a trailing $ is literal");
    }

    @Test
    void unterminatedQuotesAreInvalidParams() {
        for (String input : new String[] {"'abc", "\"abc", "$'abc"}) {
            RpcException error = assertThrows(RpcException.class, () -> ShellTokenizer.split(input), input);
            assertEquals(RpcException.INVALID_PARAMS, error.code());
        }
    }
}
