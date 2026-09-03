package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodingChangeReviewArtifactTest {
    private static final String ZERO = "sha256:" + "0".repeat(64);
    private static final String ONE = "sha256:" + "1".repeat(64);

    @Test
    void partialAttributionIsMachineReadableAndContentAddressed() {
        var complete = CodingChangeReviewArtifact.create(
                List.of("change-1"), ZERO, ONE, List.of(), 0, false, counts(), AttributionStatus.COMPLETE);
        var partial = CodingChangeReviewArtifact.create(
                List.of("change-1"), ZERO, ONE, List.of(), 0, false, counts(), AttributionStatus.ATTRIBUTION_PARTIAL);

        assertThat(partial.schemaVersion()).isEqualTo("coding-change-review/2");
        assertThat(partial.toStructuredData()).containsEntry("attributionStatus", "ATTRIBUTION_PARTIAL");
        assertThat(partial.complete()).isFalse();
        assertThat(partial.artifactRef()).isNotEqualTo(complete.artifactRef());
        assertThat(CodingChangeReviewArtifact.fromStructuredData(partial.toStructuredData()))
                .contains(partial);
    }

    @Test
    void readsLegacyV1ArtifactDeterministically() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("schemaVersion", "coding-change-review/1");
        legacy.put("artifactRef", "sha256:75da0e5f7183132373b2a3a46db81200e26f063064df9c3d3f938af4b69e066c");
        legacy.put("changeSetIds", List.of("legacy"));
        legacy.put("baseWorkspaceDigest", ZERO);
        legacy.put("resultWorkspaceDigest", ONE);
        legacy.put("fileSummaries", List.of());
        legacy.put("totalFileCount", 0);
        legacy.put("summariesTruncated", false);
        legacy.put("counts", counts());
        legacy.put("complete", true);

        var parsed = CodingChangeReviewArtifact.fromStructuredData(legacy).orElseThrow();

        assertThat(parsed.schemaVersion()).isEqualTo("coding-change-review/1");
        assertThat(parsed.attributionStatus()).isEqualTo(AttributionStatus.COMPLETE);
        assertThat(parsed.complete()).isTrue();
        assertThat(parsed.toStructuredData()).isEqualTo(legacy);
    }

    private static Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : List.of("created", "replaced", "deleted", "moved", "binary", "oversize", "opaque")) {
            counts.put(key, 0);
        }
        return counts;
    }
}
