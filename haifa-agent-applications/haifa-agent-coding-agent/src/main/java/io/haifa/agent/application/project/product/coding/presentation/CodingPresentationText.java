package io.haifa.agent.application.project.product.coding.presentation;

import io.haifa.agent.credential.api.SecretRedactor;

/** Product text normalization shared by whole snapshots and transient native observations. */
public final class CodingPresentationText {
    private CodingPresentationText() {}

    static String safe(String value, int maximum, SecretRedactor redactor) {
        String normalized;
        try {
            normalized = new Stream().accept(value).replace("\r", "\n");
        } catch (IllegalStateException malformed) {
            return "[Display content unavailable]";
        }
        String redacted = redactor.redact(normalized);
        return redacted.substring(
                0, redacted.offsetByCodePoints(0, Math.min(maximum, redacted.codePointCount(0, redacted.length()))));
    }

    public static final class Stream {
        private int escape;
        private int escapeLength;
        private char highSurrogate;

        public boolean pending() {
            return escape != 0 || highSurrogate != 0;
        }

        public String accept(String text) {
            StringBuilder plain = new StringBuilder();
            String incoming = highSurrogate == 0 ? text : highSurrogate + text;
            highSurrogate = 0;
            if (!incoming.isEmpty() && Character.isHighSurrogate(incoming.charAt(incoming.length() - 1))) {
                highSurrogate = incoming.charAt(incoming.length() - 1);
                incoming = incoming.substring(0, incoming.length() - 1);
            }
            for (int cp : incoming.codePoints().toArray()) {
                if (cp >= 0xD800 && cp <= 0xDFFF) continue;
                if (escape != 0 && ++escapeLength > 512)
                    throw new IllegalStateException("Display control limit exceeded");
                switch (escape) {
                    case 0 -> {
                        if (cp == 27) {
                            escape = 1;
                            escapeLength = 0;
                        } else if (cp == '\n' || cp == '\r' || cp == '\t' || !Character.isISOControl(cp))
                            plain.appendCodePoint(cp);
                    }
                    case 1 -> escape = cp == '[' ? 2 : cp == ']' ? 3 : cp >= 0x20 && cp <= 0x2f ? 5 : 0;
                    case 2 -> {
                        if (cp >= 0x40 && cp <= 0x7e) escape = 0;
                    }
                    case 3 -> {
                        if (cp == 7) escape = 0;
                        else if (cp == 27) escape = 4;
                    }
                    case 4 -> escape = cp == '\\' ? 0 : cp == 27 ? 4 : 3;
                    case 5 -> {
                        if (cp >= 0x30 && cp <= 0x7e) escape = 0;
                    }
                    default -> throw new IllegalStateException("Invalid display control state");
                }
            }
            return plain.toString();
        }
    }
}
