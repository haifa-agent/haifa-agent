package io.haifa.agent.testing.harness;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.haifa.agent.testing.evidence.Sha256Digests;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The single canonical, digest-bound plan representation used by every harness suite. */
public record ResolvedTestPlan(int schemaVersion, Map<String, Object> content, String sha256) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public ResolvedTestPlan {
        if (schemaVersion != 1) throw new IllegalArgumentException("resolved plan schemaVersion must be 1");
        content = Map.copyOf(Objects.requireNonNull(content, "content must not be null"));
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("plan sha256 must be lowercase SHA-256");
        }
    }

    public static ResolvedTestPlan freeze(Map<String, ?> reviewedInputs) {
        Objects.requireNonNull(reviewedInputs, "reviewedInputs must not be null");
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("schemaVersion", 1);
        reviewedInputs.forEach(content::put);
        try {
            return new ResolvedTestPlan(1, content, Sha256Digests.bytes(JSON.writeValueAsBytes(content)));
        } catch (IOException exception) {
            throw new IllegalStateException("resolved plan could not be serialized", exception);
        }
    }

    public void requireApproved(String approvedSha256) {
        if (approvedSha256 == null || !approvedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("approved execution plan SHA-256 is required");
        }
        if (!sha256.equals(approvedSha256)) {
            throw new IllegalArgumentException("approved execution plan SHA-256 does not match the current plan");
        }
    }

    public void verifyIntegrity() {
        LinkedHashMap<String, Object> reviewedInputs = new LinkedHashMap<>(content);
        reviewedInputs.remove("schemaVersion");
        if (!freeze(reviewedInputs).sha256().equals(sha256)) {
            throw new IllegalArgumentException("resolved execution plan content does not match its SHA-256");
        }
    }

    public Map<String, Object> artifact() {
        LinkedHashMap<String, Object> artifact = new LinkedHashMap<>(content);
        artifact.put("planSha256", sha256);
        return Map.copyOf(artifact);
    }
}
