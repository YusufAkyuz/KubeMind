package com.kubemind.ai;

import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Shared chunk-writing helper for the streaming AI endpoints (chat, draft, analyze). */
final class AiStreaming {

    private AiStreaming() {}

    static void writeChunk(OutputStream out, String token) {
        if (token == null || token.isEmpty()) return;
        try {
            out.write(token.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            throw new UncheckedIOException(new java.io.IOException(e));
        }
    }
}
