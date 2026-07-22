package io.haifa.agent.cli;

enum ApprovalMode {
    ASK,
    AUTO,
    DENY;

    static ApprovalMode parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("approval must be one of: ask, auto, deny");
        }
    }
}
