package io.haifa.agent.cli;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

record CliConfiguration(
        Model model,
        Set<String> enabledTools,
        ApprovalMode approval,
        Duration timeout,
        int maxIterations,
        long maxToolCalls) {
    private static final Set<String> DEFAULT_TOOLS = Set.of(
            "file.list",
            "file.stat",
            "file.read",
            "file.search",
            "file.create",
            "file.write",
            "file.delete",
            "file.move");

    CliConfiguration {
        model = Objects.requireNonNull(model, "model must not be null");
        enabledTools =
                Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(enabledTools, "enabledTools must not be null")));
        if (!DEFAULT_TOOLS.containsAll(enabledTools)) {
            throw new IllegalArgumentException("CLI currently supports only local file tools: " + DEFAULT_TOOLS);
        }
        approval = Objects.requireNonNull(approval, "approval must not be null");
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        if (maxIterations < 1 || maxToolCalls < 1)
            throw new IllegalArgumentException("runtime limits must be positive");
    }

    static CliConfiguration defaults() {
        return new CliConfiguration(
                new Model(
                        "deepseek",
                        "deepseek-v4-pro",
                        URI.create("https://api.deepseek.com"),
                        "env://DEEPSEEK_API_KEY"),
                DEFAULT_TOOLS,
                ApprovalMode.ASK,
                Duration.ofMinutes(5),
                50,
                32);
    }

    record Model(String providerId, String modelId, URI endpoint, String credentialRef) {
        Model {
            providerId = text(providerId, "model.providerId");
            modelId = text(modelId, "model.modelId");
            endpoint = Objects.requireNonNull(endpoint, "model.endpoint must not be null");
            credentialRef = text(credentialRef, "model.credentialRef");
            if (!credentialRef.startsWith("env://")) {
                throw new IllegalArgumentException("model.credentialRef must use env://");
            }
        }
    }

    static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    static Set<String> defaultTools() {
        return DEFAULT_TOOLS;
    }
}
