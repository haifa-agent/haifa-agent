package io.haifa.agent.cli;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;
import java.util.Optional;

/** Parsed top-level Session Resume intent. */
record CliResumeRequest(Target target, Optional<AgentSessionId> sessionId, Optional<String> prompt) {
    CliResumeRequest {
        target = Objects.requireNonNull(target, "target must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        prompt = Objects.requireNonNull(prompt, "prompt must not be null")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        if ((target == Target.SESSION) != sessionId.isPresent()) {
            throw new IllegalArgumentException("SESSION target requires exactly one session id");
        }
        if (target == Target.SELECTOR && prompt.isPresent()) {
            throw new IllegalArgumentException("resume selector cannot include a prompt without a session target");
        }
    }

    CodingTerminalStartup startup() {
        return switch (target) {
            case SELECTOR -> CodingTerminalStartup.selectSession();
            case LAST -> CodingTerminalStartup.lastSession(prompt);
            case SESSION -> CodingTerminalStartup.session(sessionId.orElseThrow(), prompt);
        };
    }

    enum Target {
        SELECTOR,
        LAST,
        SESSION
    }
}
