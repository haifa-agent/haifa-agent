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
import io.haifa.agent.personalassistant.application.mission.ResearchBrief;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MissionArtifactPublisherTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void publishesMarkdownAndExactlyFiveRecoverableV2ArtifactsWithConsistentReferences() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        var metadata = new InMemoryArtifactStore();
        var publisher = publisher(metadata, () -> "artifact-" + ids.incrementAndGet());

        var published = publisher.publish(intent(validTask()), synthesis(validReport()));
        var replayed = publisher.publish(intent(validTask()), synthesis(validReport()));
        JsonNode manifest = MAPPER.readTree(published.structuredResult());

        assertThat(published.artifactIds()).hasSize(5).isEqualTo(replayed.artifactIds());
        assertThat(metadata.findByProject("mission-mission-1")).hasSize(5);
        assertThat(manifest.path("schemaVersion").asText()).isEqualTo("pa.research-delivery/v2");
        assertThat(manifest.path("completionKind").asText()).isEqualTo("COMPLETE");
        assertThat(manifest.path("degraded").asBoolean()).isFalse();
        assertThat(manifest.path("qualityGate").path("passed").asBoolean()).isTrue();
        assertThat(manifest.path("coveredTaskIds")).extracting(JsonNode::asText).containsExactly("task-1");
        for (String field : List.of(
                "reportArtifactRef", "sourcesArtifactRef", "claimEvidenceArtifactRef", "unresolvedArtifactRef")) {
            assertThat(manifest.path(field).path("sha256").asText()).matches("sha256:[a-f0-9]{64}");
            assertThat(manifest.path(field).path("version").asLong()).isEqualTo(1);
            assertThat(manifest.path(field).path("byteCount").asLong()).isPositive();
        }
        assertThat(published.finalMessage())
                .contains("<!-- haifa-mission-delivery:mission-1 -->", "完整报告与证据已保存在 Mission 中")
                .doesNotContain("中文“引号”", "| 主张 | 证据 |", "```text");
        assertThat(manifest.path("evidenceSummary").path("totalClaimCount").asInt())
                .isEqualTo(1);
        assertThat(manifest.path("evidenceSummary")
                        .path("singleSourceClaimCount")
                        .asInt())
                .isEqualTo(0);
        assertThat(manifest.path("efficiencyMetrics")
                        .path("qualityGateRevisionCount")
                        .asInt())
                .isZero();
        assertThat(manifest.path("efficiencyMetrics")
                        .path("evidencePerMaterialClaim")
                        .asDouble())
                .isEqualTo(2.0d);
        assertThat(published.structuredResult()).doesNotContain("中文“引号”", "```text");
    }

    @Test
    void resumesAfterInterruptionBeforeTheDeliveryManifestWithoutDuplicatingArtifacts() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        var metadata = new InMemoryArtifactStore();
        var payloads = new InMemoryArtifactPayloadStore();
        Ids interruptedIds = () -> {
            int value = ids.incrementAndGet();
            if (value == 5) {
                throw new IllegalStateException("injected interruption before manifest publication");
            }
            return "artifact-" + value;
        };
        var interrupted = new MissionArtifactPublisher(artifactService(metadata, payloads, interruptedIds), MAPPER);

        assertThatThrownBy(() -> interrupted.publish(intent(validTask()), synthesis(validReport())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected interruption");
        assertThat(metadata.findByProject("mission-mission-1"))
                .extracting(value -> value.title())
                .containsExactlyInAnyOrder(
                        "research-report.md", "sources.json", "claim-evidence.json", "unresolved-questions.json");

        var resumed = new MissionArtifactPublisher(
                        artifactService(metadata, payloads, () -> "artifact-" + ids.incrementAndGet()), MAPPER)
                .publish(intent(validTask()), synthesis(validReport()));
        var replayed = new MissionArtifactPublisher(
                        artifactService(metadata, payloads, () -> "artifact-" + ids.incrementAndGet()), MAPPER)
                .publish(intent(validTask()), synthesis(validReport()));

        assertThat(resumed.artifactIds()).hasSize(5).isEqualTo(replayed.artifactIds());
        assertThat(metadata.findByProject("mission-mission-1"))
                .extracting(value -> value.title())
                .containsExactlyInAnyOrder(
                        "research-report.md",
                        "sources.json",
                        "claim-evidence.json",
                        "unresolved-questions.json",
                        "research-delivery.json");
        assertThat(MAPPER.readTree(resumed.structuredResult())
                        .path("schemaVersion")
                        .asText())
                .isEqualTo("pa.research-delivery/v2");
    }

    @Test
    void degradedReadableCandidateIsAlwaysPartialAndCarriesStableFailures() throws Exception {
        var publisher = publisher(new InMemoryArtifactStore(), newIds());
        String degradedReport = validReport().replace("<!-- haifa-section: synthesis -->", "");
        var request = intent(validTask());
        var candidate = synthesis(degradedReport);
        var quality = publisher.evaluate(request, candidate);

        var published = publisher.publishDegraded(request, candidate, quality);
        JsonNode manifest = MAPPER.readTree(published.structuredResult());

        assertThat(quality.failureCodes()).contains("REPORT_REQUIRED_SECTION_MISSING");
        assertThat(published.completionKind()).isEqualTo("PARTIAL");
        assertThat(manifest.path("degraded").asBoolean()).isTrue();
        assertThat(manifest.path("completionKind").asText()).isEqualTo("PARTIAL");
        assertThat(manifest.path("degradationReasons"))
                .extracting(JsonNode::asText)
                .contains("REPORT_REQUIRED_SECTION_MISSING");
    }

    @Test
    void rejectsAnIllegalDegradedCompleteV2Combination() throws Exception {
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("schemaVersion", "pa.research-delivery/v2");
        manifest.put("completionKind", "COMPLETE");
        manifest.put("degraded", true);
        manifest.putArray("degradationReasons").add("REPORT_REQUIRED_SECTION_MISSING");
        manifest.putObject("qualityGate").put("passed", false);
        manifest.putObject("evidenceSummary");
        manifest.putObject("efficiencyMetrics");

        assertThatThrownBy(() -> MissionArtifactPublisher.validateDeliveryManifest(manifest))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_RESULT_SCHEMA_INVALID");
    }

    @Test
    void failedTaskProducesPartialWithoutPretendingTheReportGateFailed() throws Exception {
        var request = new MissionSynthesisIntent(
                "mission-1",
                "conversation-1",
                "local/public-user",
                MissionMode.DEEP_RESEARCH,
                "Research objective",
                List.of(validTask().toString()),
                List.of("task-2:BLOCKED:SOURCE_UNAVAILABLE"),
                List.of("task-1"),
                2,
                10_000,
                Optional.empty(),
                Optional.of(researchBrief()));
        var published = publisher(new InMemoryArtifactStore(), newIds()).publish(request, synthesis(validReport()));
        JsonNode manifest = MAPPER.readTree(published.structuredResult());

        assertThat(published.completionKind()).isEqualTo("PARTIAL");
        assertThat(manifest.path("degraded").asBoolean()).isFalse();
        assertThat(manifest.path("qualityGate").path("passed").asBoolean()).isTrue();
        assertThat(manifest.path("affectedTaskIds"))
                .extracting(JsonNode::asText)
                .containsExactly("task-2");
    }

    @Test
    void rejectsUnreadableCandidateInsteadOfPublishingMetadataAsAReport() throws Exception {
        var publisher = publisher(new InMemoryArtifactStore(), newIds());
        var request = intent(validTask());
        var candidate = synthesis("status claim-1 source-1");

        assertThatThrownBy(() -> publisher.publishDegraded(request, candidate, publisher.evaluate(request, candidate)))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_REPORT_UNREADABLE");
    }

    @Test
    void validatesCitationClosureEvidenceStateDuplicateLocatorAndQuoteBounds() throws Exception {
        assertInvalid(task -> ((ArrayNode) task.path("claims").get(0).path("supportingSourceIds"))
                .set(0, MAPPER.getNodeFactory().textNode("missing")));
        assertInvalid(task -> {
            ObjectNode duplicate = task.path("sources").get(1).deepCopy();
            duplicate.put("sourceId", "source-duplicate");
            duplicate.put("locator", "https://RESEARCH.stub:443/a/../source-1?utm_source=x#fragment");
            ((ArrayNode) task.path("sources")).add(duplicate);
        });
        assertInvalid(task -> {
            ObjectNode source = (ObjectNode) task.path("sources").get(1);
            source.put("status", "INACCESSIBLE");
            source.putNull("fetchedAt");
            source.putNull("contentDigest");
            source.put("excerpt", "");
            ((ArrayNode) task.path("claims").get(0).path("supportingSourceIds"))
                    .removeAll()
                    .add("source-2");
        });
        assertInvalid(task -> {
            ObjectNode quote = MAPPER.createObjectNode();
            quote.put("sourceId", "source-1");
            quote.put(
                    "text",
                    "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one twenty-two twenty-three twenty-four twenty-five twenty-six");
            ((ArrayNode) task.path("claims").get(0).path("quotedSpans")).add(quote);
        });
    }

    @Test
    void serverCanonicalizesSourceIdentityAndRewritesReportCitations() throws Exception {
        ObjectNode task = validTask();
        ObjectNode source = (ObjectNode) task.path("sources").get(0);
        source.put("locator", "https://RESEARCH.stub:443/a/../source-1?utm_source=model#fragment");
        source.put("normalizedLocator", "https://untrusted.invalid/model-value");
        source.put("locatorDigest", "sha256:" + "0".repeat(64));
        var metadata = new InMemoryArtifactStore();
        var service = artifactService(metadata, newIds());
        var published = new MissionArtifactPublisher(service, MAPPER).publish(intent(task), synthesis(validReport()));
        JsonNode manifest = MAPPER.readTree(published.structuredResult());
        var report = metadata.findByProject("mission-mission-1").stream()
                .filter(value -> value.title().equals("research-report.md"))
                .findFirst()
                .orElseThrow();

        assertThat(published.sources()).contains("https://research.stub/source-1");
        assertThat(new String(service.load(report), java.nio.charset.StandardCharsets.UTF_8))
                .contains("[[source-")
                .doesNotContain("[[source-1]]");
        assertThat(manifest.path("sourceCount").asInt()).isEqualTo(2);
    }

    @Test
    void trustedPublisherAddsUnverifiedAndSingleSourceEvidenceWarnings() throws Exception {
        ObjectNode task = validTask();
        ObjectNode claim = (ObjectNode) task.path("claims").get(0);
        ((ArrayNode) claim.path("supportingSourceIds")).removeAll().add("source-1");
        claim.put("unverified", true);
        var metadata = new InMemoryArtifactStore();
        var service = artifactService(metadata, newIds());

        var published = new MissionArtifactPublisher(service, MAPPER).publish(intent(task), synthesis(validReport()));
        JsonNode manifest = MAPPER.readTree(published.structuredResult());
        var report = metadata.findByProject("mission-mission-1").stream()
                .filter(value -> value.title().equals("research-report.md"))
                .findFirst()
                .orElseThrow();
        String markdown = new String(service.load(report), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(manifest.path("evidenceSummary").path("unverifiedClaimCount").asInt())
                .isEqualTo(1);
        assertThat(manifest.path("evidenceSummary")
                        .path("singleSourceClaimCount")
                        .asInt())
                .isEqualTo(1);
        assertThat(markdown).contains("本报告包含尚未充分核实的判断，不应解读为所有关键结论均已确认。", "<!-- haifa-single-source-risk: claim-1 -->");
    }

    @Test
    void canonicalizesSafeLocatorsAndRejectsPrivateOrCredentialedTargets() {
        assertThat(ResearchSourceLocator.normalize(
                                "https://RESEARCH.stub:443/a/../source-1?b=2&utm_source=x&a=1#fragment")
                        .locator())
                .isEqualTo("https://research.stub/source-1?b=2&a=1");
        assertThatThrownBy(() -> ResearchSourceLocator.normalize("http://127.0.0.1/private"))
                .isInstanceOf(MissionException.class);
        assertThatThrownBy(() -> ResearchSourceLocator.normalize("https://user@example.com/private"))
                .isInstanceOf(MissionException.class);
    }

    private static void assertInvalid(ThrowingMutation mutation) throws Exception {
        ObjectNode task = validTask();
        mutation.apply(task);
        var publisher = publisher(new InMemoryArtifactStore(), newIds());
        assertThatThrownBy(() -> publisher.evaluate(intent(task), synthesis(validReport())))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_RESULT_SCHEMA_INVALID");
    }

    private static MissionArtifactPublisher publisher(InMemoryArtifactStore metadata, Ids ids) {
        return new MissionArtifactPublisher(artifactService(metadata, ids), MAPPER);
    }

    private static ArtifactService artifactService(InMemoryArtifactStore metadata, Ids ids) {
        return artifactService(metadata, new InMemoryArtifactPayloadStore(), ids);
    }

    private static ArtifactService artifactService(
            InMemoryArtifactStore metadata, InMemoryArtifactPayloadStore payloads, Ids ids) {
        return new ArtifactService(metadata, payloads, ids::next, () -> Instant.parse("2026-08-10T00:00:00Z"));
    }

    private static Ids newIds() {
        AtomicInteger ids = new AtomicInteger();
        return () -> "artifact-" + ids.incrementAndGet();
    }

    private static MissionSynthesisIntent intent(JsonNode task) {
        return new MissionSynthesisIntent(
                "mission-1",
                "conversation-1",
                "local/public-user",
                MissionMode.DEEP_RESEARCH,
                "Research objective",
                List.of(task.toString()),
                List.of(),
                List.of("task-1"),
                2,
                Long.MAX_VALUE,
                Optional.empty(),
                Optional.of(researchBrief()));
    }

    private static ResearchBrief researchBrief() {
        return new ResearchBrief(
                "Research objective",
                "Evidence scope",
                "Frozen range",
                "Global",
                "Reader",
                List.of("primary sources"),
                List.of("unsupported claims"),
                "Markdown");
    }

    private static MissionRuntimeAccess.SynthesisRunResult synthesis(String report) {
        return new MissionRuntimeAccess.SynthesisRunResult("session-synthesis", "run-synthesis", report);
    }

    private static String validReport() {
        return """
                # AI 能力主张证据审查
                <!-- haifa-section: executive-summary -->
                ## 执行摘要
                宣传主张得到部分证据支持，但真实能力受场景和技术限制约束，商业结论仍需谨慎。
                <!-- haifa-section: scope-method -->
                ## 范围、假设与方法
                调查比较官方资料与独立来源，区分已验证事实、反证、推断和未知信息。
                <!-- haifa-section: task-findings -->
                ## 分项研究发现
                <!-- haifa-task: task-1 -->
                ### 能力、技术来源与商业模式
                中文“引号”和换行不会进入控制 JSON。关键事实由 [[source-1]] 支持，反证来自 [[source-2]]。

                | 主张 | 证据 | 反证 | 判断 |
                | --- | --- | --- | --- |
                | 能力领先 | 官方演示 | 场景限制 | 部分成立 |

                https://example.test/path?a=1
                ```text
                representative code block
                ```
                <!-- haifa-section: synthesis -->
                ## 综合分析
                证据与反证共同表明，产品具有真实能力，但宣传把限定场景外推成了普遍能力。
                <!-- haifa-section: conclusions -->
                ## 结论与建议
                在获得可复现实测和完整定价前，不应把宣传指标直接当成采购或投资依据。
                <!-- haifa-section: risks-unknowns -->
                ## 风险、未知与待确认问题
                未公开训练数据、推理成本、客户留存和最新版本变化仍会影响最终判断。
                <!-- haifa-section: sources -->
                ## 来源
                - [[source-1]] Primary evidence
                - [[source-2]] Independent evidence
                """;
    }

    private static ObjectNode validTask() throws Exception {
        return (ObjectNode) MAPPER.readTree(
                """
                {"schemaVersion":"pa.research-task-result/v1","brief":"Bounded research",
                "queries":[{"query":"primary evidence","phase":"DISCOVER"},{"query":"independent evidence","phase":"CROSS_CHECK"}],
                "sources":[
                {"sourceId":"source-1","locator":"https://research.stub/source-1","normalizedLocator":"https://research.stub/source-1","locatorDigest":"sha256:%s","title":"Primary","safetyType":"DEVELOPMENT_STUB","fetchedAt":"2026-08-08T00:00:00Z","publishedAt":"2026-01-15T00:00:00Z","status":"FETCHED","excerpt":"Primary evidence.","contentDigest":"sha256:%s"},
                {"sourceId":"source-2","locator":"https://research.stub/source-2","normalizedLocator":"https://research.stub/source-2","locatorDigest":"sha256:%s","title":"Independent","safetyType":"DEVELOPMENT_STUB","fetchedAt":"2026-08-08T00:00:00Z","publishedAt":"2026-02-01T00:00:00Z","status":"FETCHED","excerpt":"Independent evidence.","contentDigest":"sha256:%s"}],
                "claims":[{"claimId":"claim-1","claim":"Supported claim","supportingSourceIds":["source-1","source-2"],"opposingSourceIds":[],"limitations":"Offline fixture","unverified":false,"quotedSpans":[]}],
                "artifactRefs":[],"unresolvedQuestions":["External freshness"],"stopReason":"SUFFICIENT_EVIDENCE","limitsUsed":{"searchCalls":1,"fetchCalls":2,"sources":2,"contentBytes":128}}
                """
                        .formatted("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64)));
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
