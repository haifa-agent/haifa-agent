package io.haifa.agent.application.coding.terminal.application;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;
import java.util.Optional;

/** Immutable startup intent supplied by the highest-layer Coding Agent assembly. */
public record CodingTerminalStartup(Mode mode, Optional<AgentSessionId> sessionId, Optional<String> prompt) {
    public CodingTerminalStartup {
        mode = Objects.requireNonNull(mode, "mode must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        prompt = Objects.requireNonNull(prompt, "prompt must not be null")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        if ((mode == Mode.SESSION) != sessionId.isPresent()) {
            throw new IllegalArgumentException("SESSION startup requires exactly one session id");
        }
        if ((mode == Mode.EMPTY || mode == Mode.SELECTOR) && prompt.isPresent()) {
            throw new IllegalArgumentException("startup prompt requires LAST or SESSION mode");
        }
    }

    public static CodingTerminalStartup empty() {
        return new CodingTerminalStartup(Mode.EMPTY, Optional.empty(), Optional.empty());
    }

    public static CodingTerminalStartup selectSession() {
        return new CodingTerminalStartup(Mode.SELECTOR, Optional.empty(), Optional.empty());
    }

    public static CodingTerminalStartup lastSession(Optional<String> prompt) {
        return new CodingTerminalStartup(Mode.LAST, Optional.empty(), prompt);
    }

    public static CodingTerminalStartup session(AgentSessionId sessionId, Optional<String> prompt) {
        return new CodingTerminalStartup(Mode.SESSION, Optional.of(sessionId), prompt);
    }

    public enum Mode {
        EMPTY,
        SELECTOR,
        LAST,
        SESSION
    }
}
