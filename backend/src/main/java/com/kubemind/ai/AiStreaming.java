package com.kubemind.ai;

import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Shared chunk-writing helper for the streaming AI endpoints (chat, draft, analyze, edit). */
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

    /**
     * Writes a user-facing fallback message after the model call failed, distinguishing a
     * request-timeout interruption (a long response got cut off — see
     * spring.mvc.async.request-timeout) from Ollama being genuinely unreachable. Never
     * throws: if the connection is already gone, writing the fallback fails too, and letting
     * that escape would hit Spring's generic exception handler after the response is already
     * committed to a non-JSON content type — producing a second, more confusing error on top
     * of the first.
     */
    static void writeFallbackSafely(OutputStream out, Throwable cause) {
        String message = isInterrupted(cause)
            ? "\n\n[The response took too long and was interrupted. Try a shorter question, or ask again.]"
            : "\n\n[The AI service is unavailable. Is Ollama running?]";
        try {
            writeChunk(out, message);
        } catch (Exception writeFailure) {
            // Nothing more we can do — the client is already gone.
        }
    }

    private static boolean isInterrupted(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) return true;
        }
        return false;
    }
}
