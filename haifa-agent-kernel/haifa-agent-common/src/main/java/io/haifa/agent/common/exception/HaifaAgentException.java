package io.haifa.agent.common.exception;

/** Base exception for errors expressed by Haifa Agent APIs. */
public class HaifaAgentException extends RuntimeException {

    public HaifaAgentException(String message) {
        super(message);
    }

    public HaifaAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
