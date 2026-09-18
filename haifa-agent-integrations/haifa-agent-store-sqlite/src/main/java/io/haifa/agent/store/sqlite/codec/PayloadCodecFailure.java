package io.haifa.agent.store.sqlite.codec;

public enum PayloadCodecFailure {
    UNKNOWN_TYPE,
    UNKNOWN_VERSION,
    UNKNOWN_FIELD,
    TYPE_MISMATCH,
    PAYLOAD_TOO_LARGE,
    HASH_MISMATCH,
    ENCODE_FAILED,
    DECODE_FAILED
}
