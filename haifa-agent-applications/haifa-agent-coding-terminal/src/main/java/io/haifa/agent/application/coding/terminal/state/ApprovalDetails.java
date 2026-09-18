package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.runtime.api.InteractionView;
import java.util.List;
import java.util.Objects;

/** Safe, structured approval projection. No field is inferred from display text. */
public record ApprovalDetails(
        String action,
        String target,
        String risk,
        String scope,
        String network,
        String reason,
        List<String> allowedActions) {
    public ApprovalDetails {
        action = require(action, "action");
        target = require(target, "target");
        risk = require(risk, "risk");
        scope = require(scope, "scope");
        network = require(network, "network");
        reason = require(reason, "reason");
        allowedActions = List.copyOf(Objects.requireNonNull(allowedActions, "allowedActions must not be null"));
    }

    public static ApprovalDetails from(InteractionView interaction) {
        Objects.requireNonNull(interaction, "interaction must not be null");
        var target = interaction.target();
        String scope = target.type() + " · " + target.reference()
                + target.version().map(value -> " · version " + value).orElse("");
        return new ApprovalDetails(
                interaction.title(),
                target.safeSummary(),
                "On approval: " + interaction.consequences().accepted(),
                scope,
                "Not declared by runtime",
                interaction.safePrompt(),
                interaction.allowedActions().stream()
                        .map(value -> value.value())
                        .toList());
    }

    public String render() {
        return String.join(
                "\n",
                "Action: " + action,
                "Target: " + target,
                "Risk: " + risk,
                "Scope: " + scope,
                "Network: " + network,
                "Reason: " + reason,
                "Allowed: " + String.join(" / ", allowedActions));
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
