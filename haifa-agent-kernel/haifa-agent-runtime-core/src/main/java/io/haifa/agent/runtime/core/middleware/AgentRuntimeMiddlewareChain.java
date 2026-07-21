package io.haifa.agent.runtime.core.middleware;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentRuntimeMiddlewareChain {
    private final List<AgentRuntimeMiddleware> middlewares;
    private final Map<RuntimePhase, MiddlewareFailurePolicy> failurePolicies;

    public AgentRuntimeMiddlewareChain(List<AgentRuntimeMiddleware> middlewares) {
        this(middlewares, defaultPolicies());
    }

    public AgentRuntimeMiddlewareChain(
            List<AgentRuntimeMiddleware> middlewares, Map<RuntimePhase, MiddlewareFailurePolicy> failurePolicies) {
        this.middlewares = Objects.requireNonNull(middlewares, "middlewares must not be null").stream()
                .sorted(Comparator.comparing(AgentRuntimeMiddleware::phase)
                        .thenComparingInt(middleware -> middleware.order().value()))
                .toList();
        var configured = new EnumMap<RuntimePhase, MiddlewareFailurePolicy>(RuntimePhase.class);
        configured.putAll(Objects.requireNonNull(failurePolicies, "failurePolicies must not be null"));
        for (RuntimePhase phase : RuntimePhase.values()) {
            if (!configured.containsKey(phase)) {
                throw new IllegalArgumentException("missing middleware failure policy for " + phase);
            }
        }
        this.failurePolicies = Map.copyOf(configured);
    }

    public void apply(RuntimeMiddlewareContext context) {
        middlewares.forEach(middleware -> middleware.apply(context));
    }

    public void apply(RuntimePhase phase, RuntimeMiddlewareContext context) {
        context.enter(phase);
        for (AgentRuntimeMiddleware middleware : middlewares) {
            if (middleware.phase() != phase) continue;
            try {
                middleware.apply(context);
            } catch (RuntimeException error) {
                if (failurePolicies.get(phase) == MiddlewareFailurePolicy.FAIL_RUN) throw error;
                context.put(
                        "middleware.error." + middleware.getClass().getSimpleName(),
                        error.getClass().getSimpleName());
            }
        }
    }

    public List<AgentRuntimeMiddleware> middlewares() {
        return middlewares;
    }

    public Map<RuntimePhase, MiddlewareFailurePolicy> failurePolicies() {
        return failurePolicies;
    }

    private static Map<RuntimePhase, MiddlewareFailurePolicy> defaultPolicies() {
        var policies = new EnumMap<RuntimePhase, MiddlewareFailurePolicy>(RuntimePhase.class);
        for (RuntimePhase phase : RuntimePhase.values()) policies.put(phase, MiddlewareFailurePolicy.FAIL_RUN);
        policies.put(RuntimePhase.ON_ERROR, MiddlewareFailurePolicy.CONTINUE);
        return policies;
    }
}
