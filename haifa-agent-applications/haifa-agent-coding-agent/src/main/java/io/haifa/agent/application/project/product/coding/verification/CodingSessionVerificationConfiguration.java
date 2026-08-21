package io.haifa.agent.application.project.product.coding.verification;

import io.haifa.agent.application.project.product.coding.delivery.CodingValidationScope;
import io.haifa.agent.policy.api.PolicyDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Frozen, bounded Coding Session metadata; it selects evidence semantics but never authorizes execution. */
public record CodingSessionVerificationConfiguration(
        String schemaVersion, CodingVerificationProfile profile, String digest) {
    public static final String METADATA_KEY = "codingVerification";
    public static final String SCHEMA_VERSION = "coding-session-verification/1";

    public CodingSessionVerificationConfiguration {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Coding Session verification schemaVersion");
        }
        profile = Objects.requireNonNull(profile, "profile must not be null");
        String expected = digest(profile);
        if (!expected.equals(digest)) throw new IllegalArgumentException("Coding Session verification digest mismatch");
    }

    public static CodingSessionVerificationConfiguration freeze(CodingVerificationProfile profile) {
        CodingVerificationProfile frozen = Objects.requireNonNull(profile, "profile must not be null");
        return new CodingSessionVerificationConfiguration(SCHEMA_VERSION, frozen, digest(frozen));
    }

    public Map<String, Object> sessionMetadata() {
        return Map.of(METADATA_KEY, toStructuredData());
    }

    public Map<String, Object> toStructuredData() {
        return Map.of(
                "schemaVersion",
                schemaVersion,
                "digest",
                digest,
                "candidates",
                encode(profile.candidates()),
                "ignoredCandidates",
                encode(profile.ignoredCandidates()));
    }

    public static Optional<CodingSessionVerificationConfiguration> fromSessionMetadata(Map<String, Object> metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Object value = metadata.get(METADATA_KEY);
        if (!(value instanceof Map<?, ?> map)) return Optional.empty();
        try {
            CodingVerificationProfile profile =
                    new CodingVerificationProfile(decode(map.get("candidates")), decode(map.get("ignoredCandidates")));
            return Optional.of(new CodingSessionVerificationConfiguration(
                    text(map, "schemaVersion"), profile, text(map, "digest")));
        } catch (IllegalArgumentException | ClassCastException ignored) {
            return Optional.empty();
        }
    }

    public String candidateDigest(CodingVerificationCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        return PolicyDigest.sha256Fields(fields(candidate));
    }

    private static String digest(CodingVerificationProfile profile) {
        List<String> fields = new ArrayList<>();
        fields.add(SCHEMA_VERSION);
        profile.candidates().forEach(candidate -> {
            fields.add("selected");
            fields.addAll(fields(candidate));
        });
        profile.ignoredCandidates().forEach(candidate -> {
            fields.add("ignored");
            fields.addAll(fields(candidate));
        });
        return PolicyDigest.sha256Fields(fields);
    }

    private static List<String> fields(CodingVerificationCandidate candidate) {
        return List.of(
                candidate.command(),
                candidate.cost().name(),
                Long.toString(candidate.timeout().toMillis()),
                candidate.trigger().name(),
                candidate.source().name(),
                candidate.sourceReference(),
                candidate.claimedScope().name());
    }

    private static List<Map<String, Object>> encode(List<CodingVerificationCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("command", candidate.command());
                    value.put("cost", candidate.cost().name());
                    value.put("timeoutMillis", candidate.timeout().toMillis());
                    value.put("trigger", candidate.trigger().name());
                    value.put("source", candidate.source().name());
                    value.put("sourceReference", candidate.sourceReference());
                    value.put("claimedScope", candidate.claimedScope().name());
                    return Map.copyOf(value);
                })
                .toList();
    }

    private static List<CodingVerificationCandidate> decode(Object value) {
        if (!(value instanceof List<?> values))
            throw new IllegalArgumentException("verification candidates are invalid");
        if (values.size() > CodingVerificationProfile.MAXIMUM_CANDIDATES) {
            throw new IllegalArgumentException("verification candidates exceed their bound");
        }
        List<CodingVerificationCandidate> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map))
                throw new IllegalArgumentException("verification candidate is invalid");
            Object timeout = map.get("timeoutMillis");
            if (!(timeout instanceof Number number)) throw new IllegalArgumentException("timeoutMillis is invalid");
            result.add(new CodingVerificationCandidate(
                    text(map, "command"),
                    CodingVerificationCost.valueOf(text(map, "cost")),
                    Duration.ofMillis(number.longValue()),
                    CodingVerificationTrigger.valueOf(text(map, "trigger")),
                    CodingVerificationSource.valueOf(text(map, "source")),
                    text(map, "sourceReference"),
                    CodingValidationScope.valueOf(text(map, "claimedScope"))));
        }
        return List.copyOf(result);
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is invalid");
        return text;
    }
}
