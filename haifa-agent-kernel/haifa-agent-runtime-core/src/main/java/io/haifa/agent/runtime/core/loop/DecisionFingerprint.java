package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.runtime.core.decision.AgentDecision;
import io.haifa.agent.runtime.core.decision.ContinueDecision;
import io.haifa.agent.runtime.core.decision.DelegationDecision;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.decision.InteractionDecision;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded persisted identity of a model action; raw decision content never leaves this calculation. */
final class DecisionFingerprint {
    static final String VERSION = "action/1";

    private DecisionFingerprint() {}

    static String of(AgentDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        MessageDigest digest = sha256();
        String type;
        if (decision instanceof ToolCallDecision tools) {
            type = "TOOL_CALL";
            tools.requests().forEach(request -> {
                update(digest, request.toolName());
                update(digest, request.toolVersion());
                updateValue(digest, request.arguments().values(), 0);
            });
        } else if (decision instanceof InteractionDecision interaction) {
            type = "INTERACTION";
            update(digest, interaction.interactionType());
            update(digest, Boolean.toString(interaction.approval()));
        } else if (decision instanceof FinalAnswerDecision answer) {
            type = "FINAL_ANSWER";
            update(digest, answer.outcome().name());
            update(digest, answer.outputSchemaId());
            update(digest, answer.outputSchemaVersion());
        } else if (decision instanceof DelegationDecision delegation) {
            type = "DELEGATION";
            update(digest, delegation.childDefinitionId().value());
            update(digest, delegation.objective());
        } else if (decision instanceof ContinueDecision) {
            type = "CONTINUE";
        } else {
            throw new IllegalArgumentException(
                    "unsupported decision type: " + decision.getClass().getName());
        }
        return VERSION + ":" + type + ":" + HexFormat.of().formatHex(digest.digest());
    }

    private static void updateValue(MessageDigest digest, Object value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("decision arguments exceed maximum nesting depth");
        if (value == null) {
            update(digest, "null");
        } else if (value instanceof Map<?, ?> map) {
            update(digest, "map");
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        update(digest, String.valueOf(entry.getKey()));
                        updateValue(digest, entry.getValue(), depth + 1);
                    });
        } else if (value instanceof List<?> list) {
            update(digest, "list");
            list.forEach(item -> updateValue(digest, item, depth + 1));
        } else if (value instanceof Number number) {
            update(digest, "number:" + number);
        } else if (value instanceof Boolean bool) {
            update(digest, "boolean:" + bool);
        } else {
            update(digest, "text:" + value);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
