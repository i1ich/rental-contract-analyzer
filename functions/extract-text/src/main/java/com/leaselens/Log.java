package com.leaselens;

/**
 * Minimal structured logging: one JSON object per line, on stdout for normal events and on
 * stderr for warnings and errors. No logging framework and no dependencies — a Lambda already
 * ships every line of stdout/stderr to CloudWatch Logs, and adding slf4j/log4j here would buy
 * configuration this project does not need while enlarging every cold start.
 *
 * <p>Why JSON rather than the prose-with-brackets lines this code used before: CloudWatch Logs
 * Insights parses a JSON line into queryable fields automatically, so
 * {@code stats avg(durationMs) by event} works without a regex per message. That is also what
 * lets an operator (or an automated triage agent) reconstruct one request across the three
 * Lambdas of this system instead of reading them one log group at a time.
 *
 * <p><b>Privacy.</b> This class only formats what it is handed; the caller's obligation not to
 * log contract text is unchanged and is the reason every call site here logs a <em>length</em>
 * rather than content. {@link #MAX_VALUE_LENGTH} is a second line of defence, not the first.
 *
 * <p><b>Correlation.</b> {@link #setCorrelationId(String)} stamps every later line of the same
 * invocation with the same id — {@code jobId} in analyze-contract, the S3 object key in
 * extract-text (which has no jobId, since it is invoked with only an object key). Lambda reuses
 * threads across invocations, so a handler sets it at the top of every invocation and never
 * relies on it being empty.
 */
public final class Log {

    /** Stack traces are compacted onto one line, to this many frames. */
    private static final int MAX_STACK_FRAMES = 6;

    /** Defensive cap: no single logged value may become a wall of text in the log group. */
    private static final int MAX_VALUE_LENGTH = 500;

    /** Stacks are allowed to be longer than an ordinary value, but still bounded. */
    private static final int MAX_STACK_LENGTH = 1200;

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private Log() {
    }

    /** Stamps every subsequent line of this invocation with {@code id}. Blank clears it. */
    public static void setCorrelationId(String id) {
        if (id == null || id.isBlank()) {
            CORRELATION_ID.remove();
        } else {
            CORRELATION_ID.set(id);
        }
    }

    /** Drops the correlation id, so a reused thread cannot carry it into the next invocation. */
    public static void clearCorrelationId() {
        CORRELATION_ID.remove();
    }

    /** A normal event: {@code Log.event("analysis_completed", "durationMs", 812, "findings", 7)}. */
    public static void event(String event, Object... keyValues) {
        System.out.println(line("info", event, null, keyValues));
    }

    /** An expected failure or a rejected request — something a human may want to see, not a bug. */
    public static void warn(String event, Object... keyValues) {
        System.err.println(line("warn", event, null, keyValues));
    }

    /** An unexpected failure, with the exception class, message and a compacted stack. */
    public static void error(String event, Throwable thrown, Object... keyValues) {
        System.err.println(line("error", event, thrown, keyValues));
    }

    /** Package-private so a test can assert on the rendered line without capturing stdout. */
    static String line(String level, String event, Throwable thrown, Object... keyValues) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"level\":\"").append(escape(level))
                .append("\",\"event\":\"").append(escape(event)).append('"');

        String correlationId = CORRELATION_ID.get();
        if (correlationId != null) {
            sb.append(",\"correlationId\":\"").append(escape(correlationId)).append('"');
        }

        if (keyValues != null) {
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                Object key = keyValues[i];
                if (key == null) {
                    continue;
                }
                sb.append(",\"").append(escape(String.valueOf(key))).append("\":");
                appendValue(sb, keyValues[i + 1], MAX_VALUE_LENGTH);
            }
        }

        if (thrown != null) {
            sb.append(",\"exceptionClass\":\"").append(escape(thrown.getClass().getName())).append('"');
            sb.append(",\"exceptionMessage\":");
            appendValue(sb, thrown.getMessage(), MAX_VALUE_LENGTH);
            sb.append(",\"stack\":");
            appendValue(sb, compactStack(thrown), MAX_STACK_LENGTH);
        }

        return sb.append('}').toString();
    }

    private static void appendValue(StringBuilder sb, Object value, int maxLength) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            String text = String.valueOf(value);
            boolean truncated = text.length() > maxLength;
            if (truncated) {
                text = text.substring(0, maxLength);
            }
            sb.append('"').append(escape(text));
            if (truncated) {
                sb.append("...[truncated]");
            }
            sb.append('"');
        }
    }

    /**
     * The whole stack on one line: {@code Class.method(File:12) | Class.method(File:34) | ...},
     * plus the root cause. A multi-line {@code printStackTrace} becomes one CloudWatch event per
     * line, which makes the trace impossible to correlate with the message that preceded it.
     */
    private static String compactStack(Throwable thrown) {
        StringBuilder sb = new StringBuilder(256);
        StackTraceElement[] frames = thrown.getStackTrace();
        int shown = Math.min(frames.length, MAX_STACK_FRAMES);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(frames[i]);
        }
        if (frames.length > shown) {
            sb.append(" | ...").append(frames.length - shown).append(" more");
        }
        Throwable cause = thrown.getCause();
        if (cause != null && cause != thrown) {
            sb.append(" <- caused by ").append(cause.getClass().getName())
                    .append(": ").append(cause.getMessage());
        }
        return sb.toString();
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
