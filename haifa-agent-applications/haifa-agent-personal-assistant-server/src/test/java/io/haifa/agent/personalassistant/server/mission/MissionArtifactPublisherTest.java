package io.haifa.agent.personalassistant.server.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.InMemoryArtifactPayloadStore;
import io.haifa.agent.artifact.InMemoryArtifactStore;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionMode;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.mission.MissionSynthesisIntent;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MissionArtifactPublisherTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validatesCitationClosureAndPublishesExactlyFiveRecoverableResearchArtifacts() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        var metadata = new InMemoryArtifactStore();
        var publisher = publisher(metadata, () -> "artifact-" + ids.incrementAndGet());

        var published = publisher.publish(intent(validTask()), synthesis(validFinal()));
        var replayed = publisher.publish(intent(validTask()), synthesis(validFinal()));
        JsonNode stored = MAPPER.readTree(published.structuredResult());

        assertThat(published.artifactIds()).hasSize(5);
        assertThat(replayed.artifactIds()).isEqualTo(published.artifactIds());
        assertThat(metadata.findByProject("mission-mission-1")).hasSize(5);
        assertThat(published.sources())
                .containsExactly("https://research.stub/source-1", "https://research.stub/source-2");
        assertThat(stored.path("artifactRefs")).hasSize(5);
        assertThat(stored.path("resultArtifactRef").path("sha256").asText()).startsWith("sha256:");
        assertThat(stored.path("sourcesArtifactRef").path("version").asLong()).isEqualTo(1);
    }

    @Test
    void rejectsMissingCitationDuplicateLocatorInsufficientEvidenceAndOversizedQuote() throws Exception {
        assertInvalid(task -> ((ArrayNode) task.path("claims").get(0).path("supportingSourceIds"))
                .set(0, MAPPER.getNodeFactory().textNode("missing")));
        assertInvalid(task -> {
            ObjectNode duplicate = task.path("sources").get(1).deepCopy();
            duplicate.put("sourceId", "source-duplicate");
            duplicate.put("locator", "https://RESEARCH.stub:443/a/../source-1?utm_source=x#fragment");
            duplicate.put("normalizedLocator", "https://research.stub/source-1");
            duplicate.put("locatorDigest", "sha256:1d0076d5314fa605319d168505842186fb1f6d3f534ee25bc2a9fc79a8b97980");
            ((ArrayNode) task.path("sources")).add(duplicate);
        });
        assertInvalid(task -> {
            ObjectNode source = (ObjectNode) task.path("sources").get(1);
            source.put("status", "INACCESSIBLE");
            source.putNull("fetchedAt");
            source.putNull("contentDigest");
            source.put("excerpt", "");
            ArrayNode supporting = (ArrayNode) task.path("claims").get(0).path("supportingSourceIds");
            supporting.removeAll().add("source-2");
        });
        assertInvalid(task -> {
            ObjectNode quote = MAPPER.createObjectNode();
            quote.put("sourceId", "source-1");
            quote.put(
                    "text",
                    "one two three four five six seven eight nine ten eleven twelve thirteen fourteen "
                            + "fifteen sixteen seventeen eighteen nineteen twenty twenty-one twenty-two twenty-three "
                            + "twenty-four twenty-five twenty-six");
            ((ArrayNode) task.path("claims").get(0).path("quotedSpans")).add(quote);
        });
    }

    @Test
    void canonicalizesSafeLocatorsAndRemovesOnlyFrozenTrackingParameters() {
        var normalized = ResearchSourceLocator.normalize(
                "https://RESEARCH.stub:443/a/../source-1?b=2&utm_source=x&a=1#fragment");

        assertThat(normalized.locator()).isEqualTo("https://research.stub/source-1?b=2&a=1");
        assertThat(normalized.digest()).matches("sha256:[a-f0-9]{64}");
        assertThatThrownBy(() -> ResearchSourceLocator.normalize("http://127.0.0.1/private"))
                .isInstanceOf(MissionException.class);
        assertThatThrownBy(() -> ResearchSourceLocator.normalize("https://user@example.com/private"))
                .isInstanceOf(MissionException.class);
    }

    @Test
    void serverOwnsCanonicalSourceIdentityInsteadOfTrustingModelHashes() throws Exception {
        ObjectNode task = validTask();
        ObjectNode source = (ObjectNode) task.path("sources").get(0);
        source.put("locator", "https://RESEARCH.stub:443/a/../source-1?utm_source=model#fragment");
        source.put("normalizedLocator", "https://untrusted.invalid/model-value");
        source.put("locatorDigest", "sha256:" + "0".repeat(64));

        AtomicInteger ids = new AtomicInteger();
        var published = publisher(new InMemoryArtifactStore(), () -> "artifact-" + ids.incrementAndGet())
                .publish(intent(task), synthesis(validFinal()));

        assertThat(published.sources()).contains("https://research.stub/source-1");
    }

    @Test
    void rejectsSynthesisThatOmitsUnverifiedClaimOrInventsArtifactReference() throws Exception {
        ObjectNode task = validTask();
        ((ObjectNode) task.path("claims").get(0)).put("unverified", true);
        assertThatThrownBy(() -> publisher(new InMemoryArtifactStore(), () -> "artifact-1")
                        .publish(intent(task), synthesis(validFinal())))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_RESULT_SCHEMA_INVALID");

        ObjectNode result = validFinal();
        ObjectNode invented = MAPPER.createObjectNode();
        invented.put("artifactId", "invented");
        ((ArrayNode) result.path("artifactRefs")).add(invented);
        assertThatThrownBy(() -> publisher(new InMemoryArtifactStore(), () -> "artifact-1")
                        .publish(intent(validTask()), synthesis(result)))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_RESULT_SCHEMA_INVALID");
    }

    @Test
    void preservesExplicitNonVerifiedSourceStatesWithoutPromotingThemToEvidence() throws Exception {
        for (String status : List.of("INACCESSIBLE", "STALE", "UNKNOWN", "CONFLICT", "UNDATED", "UNSAFE")) {
            ObjectNode task = validTask();
            ObjectNode source = (ObjectNode) task.path("sources").get(1);
            source.put("status", status);
            source.putNull("fetchedAt");
            source.putNull("contentDigest");
            source.put("excerpt", "");
            ObjectNode claim = (ObjectNode) task.path("claims").get(0);
            claim.put("unverified", true);
            ObjectNode result = validFinal();
            ((ArrayNode) result.path("unverifiedClaims")).add("claim-1");

            AtomicInteger ids = new AtomicInteger();
            var published = publisher(new InMemoryArtifactStore(), () -> "artifact-" + ids.incrementAndGet())
                    .publish(intent(task), synthesis(result));

            assertThat(published.structuredResult()).contains("\"completionKind\":\"COMPLETE\"");
        }
    }

    private static void assertInvalid(ThrowingMutation mutation) throws Exception {
        ObjectNode task = validTask();
        mutation.apply(task);
        assertThatThrownBy(() -> publisher(new InMemoryArtifactStore(), () -> "artifact-1")
                        .publish(intent(task), synthesis(validFinal())))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_RESULT_SCHEMA_INVALID");
    }

    private static MissionArtifactPublisher publisher(InMemoryArtifactStore metadata, Ids ids) {
        return new MissionArtifactPublisher(
                new ArtifactService(
                        metadata,
                        new InMemoryArtifactPayloadStore(),
                        ids::next,
                        () -> Instant.parse("2026-08-08T00:00:00Z")),
                MAPPER);
    }

    private static MissionSynthesisIntent intent(JsonNode task) {
        return new MissionSynthesisIntent(
                "mission-1",
                "conversation-1",
                "local/public-user",
                MissionMode.DEEP_RESEARCH,
                "Research objective",
                List.of(task.toString()));
    }

    private static MissionRuntimeAccess.SynthesisRunResult synthesis(JsonNode result) {
        return new MissionRuntimeAccess.SynthesisRunResult("session-synthesis", "run-synthesis", result.toString());
    }

    private static ObjectNode validTask() throws Exception {
        return (ObjectNode)
                MAPPER.readTree(
                        """
                {"schemaVersion":"pa.research-task-result/v1","brief":"Bounded research",
                "queries":[{"query":"primary evidence","phase":"DISCOVER"},
                {"query":"independent evidence","phase":"CROSS_CHECK"}],
                "sources":[
                {"sourceId":"source-1","locator":"https://research.stub/source-1",
                "normalizedLocator":"https://research.stub/source-1",
                "locatorDigest":"sha256:1d0076d5314fa605319d168505842186fb1f6d3f534ee25bc2a9fc79a8b97980",
                "title":"Primary","safetyType":"DEVELOPMENT_STUB","fetchedAt":"2026-08-08T00:00:00Z",
                "publishedAt":"2026-01-15T00:00:00Z","status":"FETCHED","excerpt":"Primary evidence.",
                "contentDigest":"sha256:9f00cea97901fba126e5aecc2f4a33adb3763cbdef57aa21ebf816f94198437b"},
                {"sourceId":"source-2","locator":"https://research.stub/source-2",
                "normalizedLocator":"https://research.stub/source-2",
                "locatorDigest":"sha256:abe06c90ad15ca62760beee68928ade4e5ff04b28d3077a63dccbe599e2d7da5",
                "title":"Independent","safetyType":"DEVELOPMENT_STUB","fetchedAt":"2026-08-08T00:00:00Z",
                "publishedAt":"2026-02-01T00:00:00Z","status":"FETCHED","excerpt":"Independent evidence.",
                "contentDigest":"sha256:2badb1b783b31c475f4112dba70fd85edbd4721e5c0b326ab83cb292a36be30a"}],
                "claims":[{"claimId":"claim-1","claim":"Supported claim",
                "supportingSourceIds":["source-1","source-2"],"opposingSourceIds":[],
                "limitations":"Offline fixture","unverified":false,"quotedSpans":[]}],
                "artifactRefs":[],"unresolvedQuestions":["External freshness"],
                "stopReason":"SUFFICIENT_EVIDENCE",
                "limitsUsed":{"searchCalls":1,"fetchCalls":2,"sources":2,"contentBytes":128}}
                """);
    }

    private static ObjectNode validFinal() throws Exception {
        return (ObjectNode)
                MAPPER.readTree(
                        """
                {"schemaVersion":"pa.research-final-result/v1","reportArtifactRef":null,
                "sourcesArtifactRef":null,"claimEvidenceArtifactRef":null,"resultArtifactRef":null,
                "unresolvedArtifactRef":null,"directAnswer":"Supported answer",
                "completedItems":["Research completed"],"failedItems":[],"artifactRefs":[],
                "sourceRefs":["source-1","source-2"],"unverifiedClaims":[],
                "unresolvedQuestions":["External freshness"],"residualRisks":["Offline evidence"],
                "completionKind":"COMPLETE"}
                """);
    }

    @FunctionalInterface
    private interface Ids {
        String next();
    }

    @FunctionalInterface
    private interface ThrowingMutation {
        void apply(ObjectNode task) throws Exception;
    }
}
