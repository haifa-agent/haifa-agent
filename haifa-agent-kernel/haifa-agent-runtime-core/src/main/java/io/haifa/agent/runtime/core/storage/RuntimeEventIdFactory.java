package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Injectable committed Event ID boundary. */
@FunctionalInterface
public interface RuntimeEventIdFactory {
    String create(AgentRunId runId, long sequence);

    static RuntimeEventIdFactory deterministic() {
        return (runId, sequence) -> {
            Objects.requireNonNull(runId, "runId must not be null");
            if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
            String encoded = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(runId.value().getBytes(StandardCharsets.UTF_8));
            return "runtime-event:v1:" + encoded + ":" + sequence;
        };
    }
}
