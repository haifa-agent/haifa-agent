package io.haifa.agent.store.jsonl;

import java.util.Objects;

public final class TranscriptProjectionException extends RuntimeException {
    private final TranscriptDiagnosticCode code;

    public TranscriptProjectionException(TranscriptDiagnosticCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public TranscriptProjectionException(TranscriptDiagnosticCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public TranscriptDiagnosticCode code() {
        return code;
    }
}
