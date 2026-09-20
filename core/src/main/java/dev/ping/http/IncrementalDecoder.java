package dev.ping.http;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * Decodes a byte stream in pieces without splitting a multi-byte character.
 *
 * <p>A read can end in the middle of a character (a UTF-8 {@code é} is two bytes and a network
 * read does not care). The undecoded tail is kept and completed by the next read, so a chunk is
 * always whole text.
 */
final class IncrementalDecoder {

    private final CharsetDecoder decoder;
    private ByteBuffer carry = ByteBuffer.allocate(0);

    IncrementalDecoder(Charset charset) {
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    String decode(byte[] bytes, int offset, int length) {
        ByteBuffer input = ByteBuffer.allocate(carry.remaining() + length);
        input.put(carry).put(bytes, offset, length).flip();
        CharBuffer output = CharBuffer.allocate((int) (input.remaining() * (double) decoder.maxCharsPerByte()) + 2);
        decoder.decode(input, output, false);
        carry = ByteBuffer.allocate(input.remaining()).put(input).flip();
        output.flip();
        return output.toString();
    }

    /** What is left when the stream ends: an unfinished character becomes a replacement. */
    String finish() {
        CharBuffer output = CharBuffer.allocate(carry.remaining() * 2 + 4);
        decoder.decode(carry, output, true);
        decoder.flush(output);
        carry = ByteBuffer.allocate(0);
        output.flip();
        return output.toString();
    }
}
