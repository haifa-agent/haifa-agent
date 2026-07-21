package io.haifa.agent.runtime.core.middleware;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RuntimeMiddlewareContext {
    private final AgentRun run;
    private final RuntimeStateRepository state;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private RuntimePhase phase;

    public RuntimeMiddlewareContext(AgentRun run, RuntimeStateRepository state) {
        this.run = Objects.requireNonNull(run, "run must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    public AgentRun run() {
        return run;
    }

    public RuntimeStateRepository state() {
        return state;
    }

    public void put(String key, Object value) {
        attributes.put(Objects.requireNonNull(key, "key must not be null"), Objects.requireNonNull(value));
    }

    void enter(RuntimePhase phase) {
        this.phase = Objects.requireNonNull(phase);
    }

    public RuntimePhase phase() {
        return Objects.requireNonNull(phase, "middleware phase has not started");
    }

    public Map<String, Object> attributes() {
        return Map.copyOf(attributes);
    }
}
