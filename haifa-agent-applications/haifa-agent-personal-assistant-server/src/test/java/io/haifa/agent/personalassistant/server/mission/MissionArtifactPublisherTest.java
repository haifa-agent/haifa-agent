package io.haifa.agent.personalassistant.server.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.InMemoryArtifactPayloadStore;
import io.haifa.agent.artifact.InMemoryArtifactStore;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionMode;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.mission.MissionSynthesisIntent;
import io.haifa.agent.personalassistant.application.mission.ResearchBrief;
import java.nio.charset.StandardCharsets;
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

    @Test
    void publishesStandardMissionWithV2MarkdownReportAsPrimaryArtifact() throws Exception {
        var metadata = new InMemoryArtifactStore();
        var publisher = publisher(metadata, newIds());
        var intent = new MissionSynthesisIntent(
                "mission-std-1",
                "conversation-1",
                "owner-1",
                MissionMode.STANDARD,
                "Analyze distributed transaction protocols",
                List.of("{\"task\":\"completed\"}"),
                List.of());

        String richAnswerMarkdown =
                """
                # 分布式事务协议对比分析

                ## 核心机制概述
                在分布式系统中，保证跨节点操作的一致性通常采用两阶段提交（2PC）、三阶段提交（3PC）或基于 Saga 的补偿机制。
                两阶段提交通过协调者与参与者之间的 Prepare 和 Commit 两个阶段保证强一致性，但在协调者故障时可能发生阻塞。

                ## 技术参数与选型权衡
                | 方案 | 一致性级别 | 吞吐能力 | 延迟 | 容错复杂度 |
                | --- | --- | --- | --- | --- |
                | 2PC | 强一致（ACID） | 低（阻塞锁） | 高（两轮网络交互） | 协调者单点风险高 |
                | 3PC | 强一致（ACID） | 较低 | 极高（三轮网络交互） | 降低阻塞概率 |
                | Saga | 最终一致 | 极高（无长事务锁）| 低 | 需实现向前重试或向后补偿 |

                ## 实践建议
                对于长事务金融计费业务，建议采用 Saga 模式编排；跨行即时结算采用 TCC 模式，严格避免直接使用分布式两阶段阻塞长事务。
                """;

        String standardV2Result =
                """
                {
                  "schemaVersion": "pa.mission-final-result/v2",
                  "directAnswer": "分布式事务在强一致与高吞吐之间存在根本权衡，短事务推荐2PC，长流程业务建议采用Saga编排。",
                  "answerMarkdown": "%s",
                  "completedItems": ["对比 2PC 与 3PC 机制", "分析 Saga 补偿模式"],
                  "failedItems": [],
                  "artifactRefs": [],
                  "sourceRefs": ["https://research.example.com/transactions"],
                  "unverifiedClaims": [],
                  "unresolvedQuestions": [],
                  "residualRisks": [],
                  "completionKind": "COMPLETE"
                }
                """
                        .formatted(richAnswerMarkdown
                                .replace("\r\n", "\\n")
                                .replace("\n", "\\n")
                                .replace("\"", "\\\""));

        var published = publisher.publish(intent, synthesis(standardV2Result));

        assertThat(published.artifactIds()).hasSize(2);
        assertThat(published.completionKind()).isEqualTo("COMPLETE");
        var artifacts = metadata.findByProject("mission-mission-std-1");
        assertThat(artifacts)
                .extracting(value -> value.title())
                .containsExactlyInAnyOrder("mission-result.json", "mission-report.md");
        var reportArtifact = artifacts.stream()
                .filter(a -> a.title().equals("mission-report.md"))
                .findFirst()
                .orElseThrow();
        assertThat(published.finalArtifactId()).isEqualTo(reportArtifact.id().value());
    }

    @Test
    void publishesResearchTaskWithV2FindingsAndTaskSummary() throws Exception {
        ObjectNode taskV2 = (ObjectNode) MAPPER.readTree(
                """
                {
                  "schemaVersion": "pa.research-task-result/v2",
                  "taskSummary": "Deep analysis of cloud infrastructure costs and optimization strategies.",
                  "queries": [{"query": "cloud infrastructure", "phase": "DISCOVER"}],
                  "findings": [{
                    "findingId": "finding-1",
                    "title": "Spot instance reclamation",
                    "mechanism": "Cloud providers terminate spot nodes with 2-minute notice when capacity is constrained.",
                    "keyParameters": ["notice period: 120s", "cost discount: 70-90%%"],
                    "evidenceSummary": "Documented in vendor SLAs and independently benchmarked.",
                    "implications": "Requires state checkpointing or stateless workload design.",
                    "limitations": "Unpredictable interruption spikes.",
                    "supportingSourceIds": ["source-1", "source-2"],
                    "opposingSourceIds": [],
                    "evidenceAssessment": "PARTIALLY_SUPPORTED",
                    "unverified": true
                  }],
                  "sources": [
                    {"sourceId":"source-1","locator":"https://research.stub/source-1","normalizedLocator":"https://research.stub/source-1","locatorDigest":"sha256:%s","title":"Primary","safetyType":"DEVELOPMENT_STUB","fetchedAt":"2026-08-08T00:00:00Z","publishedAt":"2026-01-15T00:00:00Z","status":"FETCHED","excerpt":"Primary evidence.","contentDigest":"sha256:%s"},
                    {"sourceId":"source-2","locator":"https://research.stub/source-2","normalizedLocator":"https://research.stub/source-2","locatorDigest":"sha256:%s","title":"Independent","safetyType":"DEVELOPMENT_STUB","fetchedAt":"2026-08-08T00:00:00Z","publishedAt":"2026-02-01T00:00:00Z","status":"FETCHED","excerpt":"Independent evidence.","contentDigest":"sha256:%s"}
                  ],
                  "artifactRefs": [],
                  "unresolvedQuestions": ["Regional pricing variance"],
                  "stopReason": "SUFFICIENT_EVIDENCE",
                  "limitsUsed": {"searchCalls": 1, "fetchCalls": 2, "sources": 2, "contentBytes": 2048}
                }
                """
                        .formatted("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64)));

        var publisher = publisher(new InMemoryArtifactStore(), newIds());
        var published = publisher.publish(intent(taskV2), synthesis(validReport()));
        JsonNode manifest = MAPPER.readTree(published.structuredResult());

        assertThat(published.artifactIds()).hasSize(5);
        assertThat(manifest.path("schemaVersion").asText()).isEqualTo("pa.research-delivery/v2");
        assertThat(manifest.path("completionKind").asText()).isEqualTo("COMPLETE");
        assertThat(manifest.path("evidenceSummary").path("totalClaimCount").asInt())
                .isEqualTo(1);
        assertThat(manifest.path("evidenceSummary").path("unverifiedClaimCount").asInt())
                .isEqualTo(1);
    }

    @Test
    void publishesSupportedNormativeFindingFromOneFetchedAuthoritativeSource() throws Exception {
        ObjectNode task = (ObjectNode) MAPPER.readTree(
                """
                {
                  "schemaVersion":"pa.research-task-result/v2",
                  "taskSummary":"An authoritative specification defines the normative protocol value.",
                  "queries":[{"query":"protocol specification","phase":"DISCOVER"}],
                  "findings":[{
                    "findingId":"finding-1","title":"Normative value",
                    "mechanism":"The specification defines the protocol value as 64.",
                    "keyParameters":["value: 64"],"evidenceSummary":"Direct primary-source statement.",
                    "implications":"Implementations must use the value.","limitations":"Normative fact only.",
                    "supportingSourceIds":["source-1"],"opposingSourceIds":[],
                    "evidenceAssessment":"SUPPORTED","unverified":false
                  }],
                  "sources":[{
                    "sourceId":"source-1","locator":"https://research.stub/source-1",
                    "normalizedLocator":"https://research.stub/source-1","locatorDigest":"sha256:%s",
                    "title":"Authoritative protocol specification","safetyType":"DEVELOPMENT_STUB",
                    "fetchedAt":"2026-08-08T00:00:00Z","publishedAt":"2026-01-15T00:00:00Z",
                    "status":"FETCHED","excerpt":"The specification defines the value.","contentDigest":"sha256:%s"
                  }],
                  "artifactRefs":[],"unresolvedQuestions":[],"stopReason":"SUFFICIENT_EVIDENCE",
                  "limitsUsed":{"searchCalls":1,"fetchCalls":1,"sources":1,"contentBytes":128}
                }
                """
                        .formatted("a".repeat(64), "b".repeat(64)));
        var store = new InMemoryArtifactStore();
        var payloads = new InMemoryArtifactPayloadStore();
        var publisher = new MissionArtifactPublisher(artifactService(store, payloads, newIds()), MAPPER);
        String report =
                validReport().replace("，反证来自 [[source-2]]", "").replace("- [[source-2]] Independent evidence\n", "");

        var published = publisher.publish(intent(task), synthesis(report));
        JsonNode manifest = MAPPER.readTree(published.structuredResult());
        Artifact claimsArtifact = store.findByProject("mission-mission-1").stream()
                .filter(artifact -> artifact.title().equals("claim-evidence.json"))
                .findFirst()
                .orElseThrow();
        JsonNode claim = MAPPER.readTree(
                        new String(payloads.load(claimsArtifact.payload()).orElseThrow(), StandardCharsets.UTF_8))
                .path("claims")
                .get(0);

        assertThat(claim.path("unverified").asBoolean()).isFalse();
        assertThat(manifest.path("unverifiedClaimCount").asInt()).isZero();
        assertThat(manifest.path("evidenceSummary")
                        .path("singleSourceClaimCount")
                        .asInt())
                .isEqualTo(1);
    }

    @Test
    void rejectsStandardV2WithoutAnswerMarkdown() {
        var publisher = publisher(new InMemoryArtifactStore(), newIds());
        var intent = new MissionSynthesisIntent(
                "mission-std-missing-answer",
                "conversation-std",
                "local:user",
                MissionMode.STANDARD,
                "Compare transaction protocols",
                List.of("settled task result"),
                List.of());
        String invalid =
                """
                {
                  "schemaVersion":"pa.mission-final-result/v2",
                  "directAnswer":"This conclusion is intentionally long enough for the legacy fallback threshold.",
                  "completedItems":[],"failedItems":[],"artifactRefs":[],"sourceRefs":[],
                  "unverifiedClaims":[],"unresolvedQuestions":[],"residualRisks":[],"completionKind":"COMPLETE"
                }
                """;

        assertThatThrownBy(() -> publisher.publish(intent, synthesis(invalid)))
                .isInstanceOf(MissionException.class)
                .hasMessageContaining("Standard v2 result shape is invalid");
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
