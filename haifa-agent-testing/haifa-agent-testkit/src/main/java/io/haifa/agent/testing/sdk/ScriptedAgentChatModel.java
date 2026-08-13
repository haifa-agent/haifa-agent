package io.haifa.agent.testing.sdk;

import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Deterministic model double that records only content digests and bounded protocol metadata. */
public final class ScriptedAgentChatModel implements AgentChatModel {
    private final ArrayDeque<Step> script;
    private final List<ModelCallTrace> calls = new ArrayList<>();

    private ScriptedAgentChatModel(List<Step> script) {
        this.script = new ArrayDeque<>(script);
        if (script.isEmpty()) throw new IllegalArgumentException("script must not be empty");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public synchronized AgentChatResponse invoke(AgentChatRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        calls.add(new ModelCallTrace(
                request.runId(),
                request.iteration(),
                request.attempt(),
                request.model().modelId().value(),
                request.messages().stream().map(message -> message.role()).toList(),
                request.messages().stream()
                        .map(message -> digest(message.content()))
                        .toList(),
                request.tools().stream().map(tool -> tool.name()).toList()));
        Step step = script.pollFirst();
        if (step == null) throw new AssertionError("scripted model received an unexpected invocation");
        if (step.failure != null) throw step.failure;
        return step.response;
    }

    public synchronized List<ModelCallTrace> calls() {
        return List.copyOf(calls);
    }

    public synchronized void assertExhausted() {
        if (!script.isEmpty()) throw new AssertionError("scripted model has " + script.size() + " unused step(s)");
    }

    private static String digest(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record Step(AgentChatResponse response, RuntimeException failure) {}

    public static final class Builder {
        private final List<Step> steps = new ArrayList<>();

        public Builder thenRespond(AgentChatResponse response) {
            steps.add(new Step(Objects.requireNonNull(response, "response must not be null"), null));
            return this;
        }

        public Builder thenFail(RuntimeException failure) {
            steps.add(new Step(null, Objects.requireNonNull(failure, "failure must not be null")));
            return this;
        }

        public ScriptedAgentChatModel build() {
            return new ScriptedAgentChatModel(List.copyOf(steps));
        }
    }
}
