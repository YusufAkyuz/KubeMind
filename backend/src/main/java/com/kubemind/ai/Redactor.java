package com.kubemind.ai;

import java.util.regex.Pattern;

/**
 * Strips secrets and credential-like content before anything reaches the model.
 * Applied to BOTH env var values and log lines — even for the local Ollama model
 * (see CLAUDE.md "AI feature rules").
 *
 * Deliberately aggressive: a false positive costs a little context quality,
 * a false negative leaks a credential.
 */
public final class Redactor {

    public static final String MASK = "[REDACTED]";

    /** Env var NAMES that indicate the value is a credential. */
    private static final Pattern SENSITIVE_ENV_NAME = Pattern.compile(
        "(?i).*(password|passwd|secret|token|api[_-]?key|apikey|credential|auth|private[_-]?key|cert|access[_-]?key|client[_-]?secret|connection[_-]?string|dsn).*");

    /** Inline "key=value" / "key: value" credential assignments in free text (logs). */
    private static final Pattern INLINE_CREDENTIAL = Pattern.compile(
        "(?i)\\b(password|passwd|secret|token|api[_-]?key|apikey|credential|authorization|auth|access[_-]?key|client[_-]?secret)\\b\\s*[:=]\\s*\\S+");

    /** Bearer / Basic authorization header values. */
    private static final Pattern AUTH_HEADER = Pattern.compile(
        "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9+/._~=-]{8,}");

    /** JWTs: three dot-separated base64url segments. */
    private static final Pattern JWT = Pattern.compile(
        "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}\\b");

    /** URLs with embedded userinfo credentials: scheme://user:pass@host */
    private static final Pattern URL_USERINFO = Pattern.compile(
        "(\\w+://)[^/@\\s:]+:[^/@\\s]+@");

    /** Long standalone base64-ish blobs (likely encoded secrets/certs). */
    private static final Pattern LONG_BASE64 = Pattern.compile(
        "\\b[A-Za-z0-9+/]{64,}={0,2}\\b");

    /** PEM blocks. */
    private static final Pattern PEM_BLOCK = Pattern.compile(
        "-----BEGIN [A-Z ]+-----.*?-----END [A-Z ]+-----", Pattern.DOTALL);

    private Redactor() {}

    /** Returns the env value, or the mask if the env NAME looks credential-like. */
    public static String redactEnvValue(String name, String value) {
        if (name == null || value == null || value.isBlank()) return value;
        return SENSITIVE_ENV_NAME.matcher(name).matches() ? MASK : redactText(value);
    }

    /** Scrubs credential-looking content out of free text (log lines, messages). */
    public static String redactText(String text) {
        if (text == null || text.isBlank()) return text;
        String out = text;
        out = PEM_BLOCK.matcher(out).replaceAll(MASK);
        out = JWT.matcher(out).replaceAll(MASK);
        out = AUTH_HEADER.matcher(out).replaceAll(MASK);
        out = INLINE_CREDENTIAL.matcher(out).replaceAll(m -> m.group(1) + "=" + MASK);
        out = URL_USERINFO.matcher(out).replaceAll("$1" + MASK + "@");
        out = LONG_BASE64.matcher(out).replaceAll(MASK);
        return out;
    }
}
