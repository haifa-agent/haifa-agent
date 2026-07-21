package io.haifa.agent.runtime.core.middleware;

import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RuntimeMiddlewareContext {
    private final AgentRun run;
    private final RuntimeStateRepository state;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private final List<PromptComponent> prompts = new ArrayList<>();
    private final List<ContextItem> contextItems = new ArrayList<>();
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

    public void addPrompt(PromptComponent prompt) {
        prompts.add(Objects.requireNonNull(prompt, "prompt must not be null"));
    }

    public void addContextItem(ContextItem item) {
        contextItems.add(Objects.requireNonNull(item, "item must not be null"));
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

    public List<PromptComponent> prompts() {
        return List.copyOf(prompts);
    }

    public List<ContextItem> contextItems() {
        return List.copyOf(contextItems);
    }
}
