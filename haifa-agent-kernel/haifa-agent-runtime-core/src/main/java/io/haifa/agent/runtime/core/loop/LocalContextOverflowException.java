package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.core.message.AgentMessageId;
import java.util.Map;

/**
 * Signals that optional context has already been removed but the assembled local window still
 * exceeds the conservative budget.
 *
 * <p>The loop owns the single forced-rebuild policy. This internal signal lets it apply that
 * policy before any model call is made.
 */
final class LocalContextOverflowException extends RuntimeException {
    private final Map<AgentMessageId, Long> continuationReserves;

    LocalContextOverflowException() {
        this(Map.of());
    }

    LocalContextOverflowException(Map<AgentMessageId, Long> continuationReserves) {
        super("assembled context exceeds the local model input budget");
        this.continuationReserves = Map.copyOf(continuationReserves);
    }

    Map<AgentMessageId, Long> continuationReserves() {
        return continuationReserves;
    }
}
