package io.haifa.agent.contract.error;

import io.haifa.agent.contract.common.CorrelationId;

/** Forward-compatible machine-readable error code. */
public record ErrorCode(String value) {
    public static final ErrorCode RUN_NOT_FOUND = new ErrorCode("RUN_NOT_FOUND");
    public static final ErrorCode RUN_STATE_CONFLICT = new ErrorCode("RUN_STATE_CONFLICT");
    public static final ErrorCode RUN_VERSION_CONFLICT = new ErrorCode("RUN_VERSION_CONFLICT");
    public static final ErrorCode IDEMPOTENCY_CONFLICT = new ErrorCode("IDEMPOTENCY_CONFLICT");
    public static final ErrorCode INTERACTION_NOT_FOUND = new ErrorCode("INTERACTION_NOT_FOUND");
    public static final ErrorCode INTERACTION_ALREADY_RESOLVED = new ErrorCode("INTERACTION_ALREADY_RESOLVED");
    public static final ErrorCode INTERACTION_EXPIRED = new ErrorCode("INTERACTION_EXPIRED");
    public static final ErrorCode INTERACTION_ACTION_NOT_ALLOWED = new ErrorCode("INTERACTION_ACTION_NOT_ALLOWED");
    public static final ErrorCode INTERACTION_REVISION_CONFLICT = new ErrorCode("INTERACTION_REVISION_CONFLICT");
    public static final ErrorCode APPROVAL_AUTHORITY_DENIED = new ErrorCode("APPROVAL_AUTHORITY_DENIED");
    public static final ErrorCode APPROVAL_TARGET_STALE = new ErrorCode("APPROVAL_TARGET_STALE");
    public static final ErrorCode CURSOR_INVALID = new ErrorCode("CURSOR_INVALID");
    public static final ErrorCode CURSOR_EXPIRED = new ErrorCode("CURSOR_EXPIRED");
    public static final ErrorCode CONTRACT_VERSION_UNSUPPORTED = new ErrorCode("CONTRACT_VERSION_UNSUPPORTED");
    public static final ErrorCode PAYLOAD_TOO_LARGE = new ErrorCode("PAYLOAD_TOO_LARGE");
    public static final ErrorCode RATE_LIMITED = new ErrorCode("RATE_LIMITED");
    public static final ErrorCode INTERNAL_ERROR = new ErrorCode("INTERNAL_ERROR");

    public ErrorCode {
        value = CorrelationId.requireText(value, "value", 128);
        if (!value.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("error code must be an upper-snake token");
        }
    }
}
