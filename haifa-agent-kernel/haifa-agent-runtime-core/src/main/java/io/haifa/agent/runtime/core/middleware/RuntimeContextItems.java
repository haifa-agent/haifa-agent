package io.haifa.agent.runtime.core.middleware;

import io.haifa.agent.context.budget.HeuristicTokenEstimator;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextProvenance;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextRole;
import io.haifa.agent.context.item.ContextSecurity;
import io.haifa.agent.context.item.TextContextContent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

final class RuntimeContextItems {
    private RuntimeContextItems() {}

    static ContextItem text(
            String id,
            ContextItemType type,
            ContextRole role,
            String text,
            ContextPriority priority,
            ContextRetention retention,
            String sourceType,
            String sourceId,
            String sourceVersion,
            Set<String> labels) {
        return new ContextItem(
                new ContextItemId(id),
                type,
                new TextContextContent(role, text),
                HeuristicTokenEstimator.tokens(text) + 4,
                priority,
                retention,
                new ContextSecurity(labels, true),
                new ContextProvenance(sourceType, sourceId, sourceVersion, hash(text)),
                Map.of());
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
