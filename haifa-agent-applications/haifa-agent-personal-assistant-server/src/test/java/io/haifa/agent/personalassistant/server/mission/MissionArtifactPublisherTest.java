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
        assertThat(ResearchSourceLocator.normalize("http://www.news.cn:80/policy?utm_source=x").locator())
                .isEqualTo("http://www.news.cn/policy");
        assertThatThrownBy(() -> ResearchSourceLocator.normalize("http://127.0.0.1/private"))
                .isInstanceOf(MissionException.class);
        assertThatThrownBy(() -> ResearchSourceLocator.normalize("https://user@example.com/private"))
                .isInstanceOf(MissionException.class);
    }

    @Test
    void publishesPublicHttpEvidenceAcceptedByTheResearchTaskBoundary() throws Exception {
        ObjectNode task = validTask();
        ObjectNode source = (ObjectNode) task.path("sources").get(0);
        source.put("locator", "http://www.news.cn/policy?utm_source=model");
        source.put("normalizedLocator", "http://untrusted.invalid/model-value");
        source.put("locatorDigest", "sha256:" + "0".repeat(64));

        AtomicInteger ids = new AtomicInteger();
        var published = publisher(new InMemoryArtifactStore(), () -> "artifact-" + ids.incrementAndGet())
                .publish(intent(task), synthesis(validFinal()));

        assertThat(published.sources()).contains("http://www.news.cn/policy");
    }

    @Test
    void canonicalizesADateOnlyPublicationAtTheFinalTrustBoundary() throws Exception {
        ObjectNode task = validTask();
        ((ObjectNode) task.path("sources").get(0)).put("publishedAt", "2020-08-30");

        AtomicInteger ids = new AtomicInteger();
        var published = publisher(new InMemoryArtifactStore(), () -> "artifact-" + ids.incrementAndGet())
                .publish(intent(task), synthesis(validFinal()));

        assertThat(published.artifactIds()).hasSize(5);
    }

    @Test
    void mergesTheSameCrossTaskLocatorUnderAServerOwnedCanonicalSourceId() throws Exception {
        ObjectNode first = validTask();
        ObjectNode second = validTask();
        ArrayNode secondSources = (ArrayNode) second.path("sources");
        secondSources.remove(1);
        ((ObjectNode) secondSources.get(0)).put("sourceId", "another-task-source");
        ((ArrayNode) second.path("claims")).removeAll();
        ((ObjectNode) second.path("limitsUsed")).put("fetchCalls", 1).put("sources", 1).put("contentBytes", 64);
        ObjectNode result = validFinal();
        ((ArrayNode) result.path("sourceRefs"))
                .removeAll()
                .add("another-task-source")
                .add("source-2");

        AtomicInteger ids = new AtomicInteger();
        var published = publisher(new InMemoryArtifactStore(), () -> "artifact-" + ids.incrementAndGet())
                .publish(intent(first, second), synthesis(result));
        JsonNode stored = MAPPER.readTree(published.structuredResult());

        assertThat(published.sources()).containsExactly("https://research.stub/source-1", "https://research.stub/source-2");
        assertThat(stored.path("sourceRefs")).hasSize(2).allSatisfy(value -> assertThat(value.asText()).startsWith("source-"));
    }

    @Test
    void downgradesCrossTaskFetchDigestDriftWithoutDiscardingTheResearchReport() throws Exception {
        ObjectNode first = validTask();
        ObjectNode second = validTask();
        ArrayNode secondSources = (ArrayNode) second.path("sources");
        secondSources.remove(1);
        ObjectNode repeated = (ObjectNode) secondSources.get(0);
        repeated.put("sourceId", "same-page-shorter-fetch");
        repeated.put("excerpt", "A differently truncated fetch of the same public page.");
        repeated.put("contentDigest", "sha256:" + "c".repeat(64));
        ((ArrayNode) second.path("claims")).removeAll();
        ((ObjectNode) second.path("limitsUsed")).put("fetchCalls", 1).put("sources", 1).put("contentBytes", 64);

        ObjectNode result = validFinal();
        AtomicInteger ids = new AtomicInteger();
        var published = publisher(new InMemoryArtifactStore(), () -> "artifact-" + ids.incrementAndGet())
                .publish(intent(first, second), synthesis(result));
        JsonNode stored = MAPPER.readTree(published.structuredResult());

        assertThat(stored.path("unverifiedClaims")).anySatisfy(
                value -> assertThat(value.asText()).isEqualTo("claim-1"));
        assertThat(published.artifactIds()).hasSize(5);
    }

    @Test
    void deduplicatesTaskLocalSourceAliasesBeforeApplyingMissionLimitsAndPreservesAllUnverifiedClaims()
            throws Exception {
        List<ObjectNode> tasks = new java.util.ArrayList<>();
        ObjectNode result = validFinal();
        ((ArrayNode) result.path("sourceRefs")).removeAll();
        ((ArrayNode) result.path("unverifiedClaims")).removeAll();
        for (int taskNo = 1; taskNo <= 5; taskNo++) {
            ObjectNode task = validTask();
            ArrayNode sources = (ArrayNode) task.path("sources");
            sources.removeAll();
            for (int sourceNo = 1; sourceNo <= 6; sourceNo++) {
                ObjectNode source = MAPPER.createObjectNode();
                String sourceId = "task-" + taskNo + "--source-" + sourceNo;
                String locator = "https://research.stub/shared-" + sourceNo;
                source.put("sourceId", sourceId);
                source.put("locator", locator);
                source.put("normalizedLocator", locator);
                source.put("locatorDigest", "sha256:" + "a".repeat(64));
                source.put("title", "Shared source " + sourceNo);
                source.put("safetyType", "DEVELOPMENT_STUB");
                source.put("fetchedAt", "2026-08-08T00:00:00Z");
                source.put("publishedAt", "2026-01-15T00:00:00Z");
                source.put("status", "FETCHED");
                source.put("excerpt", "Shared evidence.");
                source.put("contentDigest", "sha256:" + "b".repeat(64));
                sources.add(source);
                ((ArrayNode) result.path("sourceRefs")).add(sourceId);
            }
            ArrayNode claims = (ArrayNode) task.path("claims");
            claims.removeAll();
            for (int claimNo = 1; claimNo <= 10; claimNo++) {
                String claimId = "task-" + taskNo + "--claim-" + claimNo;
                ObjectNode claim = MAPPER.createObjectNode();
                claim.put("claimId", claimId);
                claim.put("claim", "Unverified claim " + claimNo);
                claim.putArray("supportingSourceIds").add("task-" + taskNo + "--source-1");
                claim.putArray("opposingSourceIds");
                claim.put("limitations", "Fixture intentionally remains unverified");
                claim.put("unverified", true);
                claim.putArray("quotedSpans");
                claims.add(claim);
                ((ArrayNode) result.path("unverifiedClaims")).add(claimId);
            }
            ((ObjectNode) task.path("limitsUsed"))
                    .put("fetchCalls", 6)
                    .put("sources", 6)
                    .put("contentBytes", 384);
            tasks.add(task);
        }

        AtomicInteger ids = new AtomicInteger();
        var published = publisher(new InMemoryArtifactStore(), () -> "artifact-" + ids.incrementAndGet())
                .publish(intent(tasks.toArray(JsonNode[]::new)), synthesis(result));
        JsonNode stored = MAPPER.readTree(published.structuredResult());

        assertThat(stored.path("sourceRefs")).hasSize(6);
        assertThat(stored.path("unverifiedClaims")).hasSize(50);
        assertThat(published.sources()).hasSize(6);
        assertThat(published.artifactIds()).hasSize(5);
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

    private static MissionSynthesisIntent intent(JsonNode... tasks) {
        return new MissionSynthesisIntent(
                "mission-1",
                "conversation-1",
                "local/public-user",
                MissionMode.DEEP_RESEARCH,
                "Research objective",
                java.util.Arrays.stream(tasks).map(JsonNode::toString).toList());
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
