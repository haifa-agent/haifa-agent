package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves only caller-trusted task mode; ordinary user text remains {@link CodingTaskIntent#UNKNOWN}. */
public final class CodingTaskModeResolver {
    private final RuntimeStateRepository state;

    public CodingTaskModeResolver(RuntimeStateRepository state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    public CodingTaskIntent resolve(AgentRun run) {
        return state.messages(run.id()).stream()
                .filter(message -> message.role() == MessageRole.USER)
                .min(java.util.Comparator.comparingLong(AgentMessage::sequence))
                .map(AgentMessage::metadata)
                .map(CodingTaskModeResolver::trustedIntent)
                .orElse(CodingTaskIntent.UNKNOWN);
    }

    private static CodingTaskIntent trustedIntent(Map<String, Object> metadata) {
        if (!Boolean.TRUE.equals(metadata.get("codingTaskIntentTrusted"))) {
            return CodingTaskIntent.UNKNOWN;
        }
        Object raw = metadata.get("codingTaskIntent");
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException("trusted coding task intent is missing");
        }
        try {
            return CodingTaskIntent.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("trusted coding task intent is invalid", invalid);
        }
    }
}
