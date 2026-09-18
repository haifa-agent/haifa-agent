package io.haifa.agent.personalassistant.application.mission;

/** Stable product failure that can be mapped to a safe HTTP error without exposing internals. */
public final class MissionException extends RuntimeException {
    private final String code;

    public MissionException(String code, String message) {
        super(message);
        this.code = MissionValues.text(code, "code", 128);
    }

    public MissionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = MissionValues.text(code, "code", 128);
    }

    public String code() {
        return code;
    }
}
