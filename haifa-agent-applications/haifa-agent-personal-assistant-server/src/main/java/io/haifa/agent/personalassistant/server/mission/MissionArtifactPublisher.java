package io.haifa.agent.personalassistant.server.mission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.artifact.ArtifactProvenance;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.ArtifactType;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionMode;
import io.haifa.agent.personalassistant.application.mission.MissionPublishedResult;
import io.haifa.agent.personalassistant.application.mission.MissionResultPublisher;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.mission.MissionSynthesisIntent;
import io.haifa.agent.personalassistant.application.mission.ReportQualityGate;
import io.haifa.agent.personalassistant.application.mission.SourceReference;
import io.haifa.agent.personalassistant.application.mission.StandardMissionQualityGate;
import io.haifa.agent.personalassistant.application.research.ResearchFetchEvidence;
import io.haifa.agent.personalassistant.application.research.ResearchFetchEvidenceReader;
import io.haifa.agent.personalassistant.application.runtime.SdkMissionRuntimeAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict research schema, source identity, citation closure, and immutable Artifact publication. */
public final class MissionArtifactPublisher implements MissionResultPublisher {
    private static final Set<String> EVIDENCE_ASSESSMENTS =
            Set.of("SUPPORTED", "PARTIALLY_SUPPORTED", "CONFLICTED", "INSUFFICIENT");
    private static final Set<String> STANDARD_V2_REQUIRED_FIELDS = Set.of(
            "schemaVersion",
            "directAnswer",
            "answerMarkdown",
            "completedItems",
            "failedItems",
            "artifactRefs",
            "completionKind");
    private static final Set<String> STANDARD_V2_ALLOWED_FIELDS = Set.of(
            "schemaVersion",
            "directAnswer",
            "answerMarkdown",
            "completedItems",
            "failedItems",
            "artifactRefs",
            "sourceRefs",
            "taskOutcomes",
            "acceptanceOutcomes",
            "sectionSources",
            "sources",
            "unverifiedClaims",
            "unresolvedQuestions",
            "residualRisks",
            "completionKind",
            "reportArtifactRef",
            "resultArtifactRef");
    private static final Pattern STABLE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<String> SOURCE_STATUSES =
            Set.of("FETCHED", "INACCESSIBLE", "STALE", "UNKNOWN", "CONFLICT", "UNDATED", "UNSAFE");
    private static final Set<String> SOURCE_SAFETY_TYPES = Set.of("PUBLIC_WEB", "DEVELOPMENT_STUB");
    private static final Set<String> QUERY_PHASES = Set.of("DISCOVER", "DEEPEN", "CROSS_CHECK");
    private static final Set<String> STOP_REASONS = Set.of(
            "SUFFICIENT_EVIDENCE",
            "SOURCE_LIMIT",
            "CONTENT_LIMIT",
            "TIME_LIMIT",
            "TOOL_LIMIT",
            "NO_MORE_SAFE_SOURCES",
            "CANCELLED");
    private static final int MAX_TASK_RESULTS = 8;
    private static final int MAX_CLAIMS_PER_TASK = 40;
    private static final int MAX_FINAL_UNVERIFIED_CLAIMS = MAX_TASK_RESULTS * MAX_CLAIMS_PER_TASK;

    private final ArtifactService artifacts;
    private final ObjectMapper mapper;
    private final int maxSources;
    private final int maxTotalContentBytes;
    private final int maxArtifacts;
    private final long maxTotalArtifactBytes;
    private final ReportQualityGate reportQualityGate = new ReportQualityGate();
    private final ResearchFetchEvidenceReader fetchEvidenceReader;

    public MissionArtifactPublisher(ArtifactService artifacts, ObjectMapper mapper) {
        this(artifacts, mapper, 24, 2_097_152, 8, 4L * 1024 * 1024, ResearchFetchEvidenceReader.empty());
    }

    public MissionArtifactPublisher(
            ArtifactService artifacts, ObjectMapper mapper, ResearchFetchEvidenceReader fetchEvidenceReader) {
        this(artifacts, mapper, 24, 2_097_152, 8, 4L * 1024 * 1024, fetchEvidenceReader);
    }

    public MissionArtifactPublisher(
            ArtifactService artifacts,
            ObjectMapper mapper,
            int maxSources,
            int maxTotalContentBytes,
            int maxArtifacts,
            long maxTotalArtifactBytes) {
        this(
                artifacts,
                mapper,
                maxSources,
                maxTotalContentBytes,
                maxArtifacts,
                maxTotalArtifactBytes,
                ResearchFetchEvidenceReader.empty());
    }

    public MissionArtifactPublisher(
            ArtifactService artifacts,
            ObjectMapper mapper,
            int maxSources,
            int maxTotalContentBytes,
            int maxArtifacts,
            long maxTotalArtifactBytes,
            ResearchFetchEvidenceReader fetchEvidenceReader) {
        this.artifacts = java.util.Objects.requireNonNull(artifacts);
        this.mapper = java.util.Objects.requireNonNull(mapper).copy();
        this.fetchEvidenceReader =
                java.util.Objects.requireNonNullElseGet(fetchEvidenceReader, ResearchFetchEvidenceReader::empty);
        if (maxSources < 2 || maxSources > 24 || maxTotalContentBytes < 1 || maxTotalContentBytes > 2_097_152) {
            throw new IllegalArgumentException("Research limits are invalid");
        }
        if (maxArtifacts < 5
                || maxArtifacts > 8
                || maxTotalArtifactBytes < 1
                || maxTotalArtifactBytes > 4L * 1024 * 1024) {
            throw new IllegalArgumentException("Artifact limits are invalid");
        }
        this.maxSources = maxSources;
        this.maxTotalContentBytes = maxTotalContentBytes;
        this.maxArtifacts = maxArtifacts;
        this.maxTotalArtifactBytes = maxTotalArtifactBytes;
    }

    @Override
    public MissionPublishedResult publish(
            MissionSynthesisIntent intent, MissionRuntimeAccess.SynthesisRunResult synthesis) {
        if (intent.mode() == MissionMode.DEEP_RESEARCH) {
            ReportQualityGate.Result quality = evaluate(intent, synthesis);
            if (!quality.passed()) {
                throw new MissionException("MISSION_REPORT_QUALITY_FAILED", quality.revisionFeedback());
            }
            return publishResearchV2(intent, synthesis, quality, false);
        }
        JsonNode finalResult = object(synthesis.structuredOutput(), "Synthesis result");
        String schema = finalResult.path("schemaVersion").asText();
        if (!"pa.mission-final-result/v1".equals(schema) && !"pa.mission-final-result/v2".equals(schema)) {
            invalid("Result schema is unsupported");
        }
        if ("pa.mission-final-result/v2".equals(schema)) validateStandardV2Shape(finalResult);
        FinalDelivery delivery = finalDelivery(finalResult, false);
        StandardMissionQualityGate standardGate = new StandardMissionQualityGate();
        StandardMissionQualityGate.Result gateResult = standardGate.evaluate(
                candidateFrom(finalResult),
                intent.taskResults(),
                intent.completedTaskIds(),
                intent.completedTaskObjectives(),
                intent.acceptanceCriteria(),
                intent.asOf());
        if (!gateResult.passed()) {
            throw new MissionException("MISSION_REPORT_QUALITY_FAILED", gateResult.revisionFeedback());
        }

        ObjectNode enrichedResult = (ObjectNode) finalResult.deepCopy();

        List<SourceReference> authoritativeSources = new ArrayList<>();
        Set<String> seenLocators = new LinkedHashSet<>();
        if (!intent.completedTaskRunIds().isEmpty()) {
            for (String runId : intent.completedTaskRunIds()) {
                for (ResearchFetchEvidence evidence : fetchEvidenceReader.findCompletedFetches(runId)) {
                    if (evidence.successful() && evidence.sourceAvailable()) {
                        String loc = evidence.canonicalFinalUrl() != null
                                        && !evidence.canonicalFinalUrl().isBlank()
                                ? evidence.canonicalFinalUrl()
                                : evidence.canonicalRequestedUrl();
                        if (loc != null && !loc.isBlank() && seenLocators.add(loc)) {
                            String sourceId =
                                    "src-" + String.format(Locale.ROOT, "%03d", authoritativeSources.size() + 1);
                            String title = SdkMissionRuntimeAccess.urlDomainOrTitle(loc);
                            authoritativeSources.add(new SourceReference(sourceId, title, loc));
                        }
                    }
                }
            }
        }

        List<String> resultSources = new ArrayList<>();
        ArrayNode sourcesArray = mapper.createArrayNode();
        for (SourceReference ref : authoritativeSources) {
            ObjectNode sNode = mapper.createObjectNode();
            sNode.put("sourceId", ref.sourceId());
            sNode.put("title", ref.title());
            sNode.put("locator", ref.locator());
            sourcesArray.add(sNode);
            resultSources.add(ref.locator());
        }
        if ("pa.mission-final-result/v2".equals(schema)) {
            enrichedResult.set("sources", sourcesArray);
            enrichedResult.set("sourceRefs", mapper.createArrayNode());

            if (finalResult.has("sectionSources")
                    && finalResult.get("sectionSources").isArray()) {
                Set<String> authoritativeSourceIdSet = authoritativeSources.stream()
                        .map(SourceReference::sourceId)
                        .collect(java.util.stream.Collectors.toSet());
                ArrayNode validatedSectionSources = mapper.createArrayNode();
                for (JsonNode sec : finalResult.get("sectionSources")) {
                    String heading = sec.has("sectionHeading")
                            ? sec.path("sectionHeading").asText("")
                            : sec.path("section").asText("");
                    ArrayNode secSourceIds = mapper.createArrayNode();
                    if (sec.has("sourceIds") && sec.get("sourceIds").isArray()) {
                        for (JsonNode sidNode : sec.get("sourceIds")) {
                            String sid = sidNode.asText("").trim();
                            if (authoritativeSourceIdSet.contains(sid)) {
                                secSourceIds.add(sid);
                            } else {
                                throw new MissionException(
                                        "MISSION_REPORT_QUALITY_FAILED",
                                        "sectionSources references unknown sourceId: " + sid);
                            }
                        }
                    }
                    ObjectNode secNode = mapper.createObjectNode();
                    secNode.put("sectionHeading", heading);
                    secNode.set("sourceIds", secSourceIds);
                    validatedSectionSources.add(secNode);
                }
                enrichedResult.set("sectionSources", validatedSectionSources);
            }
        } else {
            if (!authoritativeSources.isEmpty()) {
                enrichedResult.set("sources", sourcesArray);
            } else {
                resultSources.addAll(delivery.sourceRefs());
            }
        }

        List<String> publishedIds = new ArrayList<>();
        String answerMarkdown = finalResult.path("answerMarkdown").isTextual()
                ? finalResult.path("answerMarkdown").asText().trim()
                : "";
        Artifact reportArtifact = null;
        if (!answerMarkdown.isBlank() && answerMarkdown.length() >= 300) {
            reportArtifact = publish(
                    intent,
                    synthesis,
                    "mission-report",
                    "mission-report.md",
                    answerMarkdown,
                    "text/markdown; charset=utf-8");
            publishedIds.add(reportArtifact.id().value());
            enrichedResult.set("reportArtifactRef", reference(reportArtifact));
        }

        Artifact resultArtifact = publish(
                intent, synthesis, "mission-result", "mission-result.json", encode(enrichedResult), "application/json");
        publishedIds.add(0, resultArtifact.id().value());
        String primaryArtifactId = reportArtifact != null
                ? reportArtifact.id().value()
                : resultArtifact.id().value();
        enrichedResult.set("resultArtifactRef", reference(resultArtifact));

        ArrayNode artifactRefsArray = mapper.createArrayNode();
        for (String pubId : publishedIds) {
            artifactRefsArray.add(pubId);
        }
        enrichedResult.set("artifactRefs", artifactRefsArray);

        return new MissionPublishedResult(
                primaryArtifactId,
                publishedIds,
                resultSources,
                encode(enrichedResult),
                delivery.directAnswer(),
                delivery.completionKind());
    }

    @Override
    public ReportQualityGate.Result evaluate(
            MissionSynthesisIntent intent, MissionRuntimeAccess.SynthesisRunResult synthesis) {
        if (intent.mode() != MissionMode.DEEP_RESEARCH) return ReportQualityGate.Result.passedResult();
        ResearchEvidence evidence = evidence(intent.taskResults());
        LinkedHashSet<String> availableSourceIds =
                new LinkedHashSet<>(evidence.sources().keySet());
        availableSourceIds.addAll(evidence.sourceAliases().keySet());
        ReportQualityGate.EvidenceSummary summary = evidenceSummary(evidence);
        ReportQualityGate.Result quality = reportQualityGate.evaluate(
                trustedReport(synthesis.structuredOutput(), evidence, summary),
                intent.completedTaskIds(),
                Set.copyOf(availableSourceIds),
                summary);
        if (synthesis.degradationReasons().isEmpty()) return quality;
        List<ReportQualityGate.Failure> failures = new ArrayList<>(quality.failures());
        failures.add(new ReportQualityGate.Failure("REPORT_SYNTHESIS_DEGRADED", synthesis.degradationReasons()));
        return new ReportQualityGate.Result(false, failures);
    }

    @Override
    public MissionPublishedResult publishDegraded(
            MissionSynthesisIntent intent,
            MissionRuntimeAccess.SynthesisRunResult synthesis,
            ReportQualityGate.Result quality) {
        if (intent.mode() != MissionMode.DEEP_RESEARCH || quality.passed()) {
            throw new MissionException("MISSION_REPORT_QUALITY_FAILED", "Degraded publication request is invalid");
        }
        if (!reportQualityGate.readable(synthesis.structuredOutput())) {
            throw new MissionException(
                    "MISSION_REPORT_UNREADABLE", "No readable research report candidate is available");
        }
        return publishResearchV2(intent, synthesis, quality, true);
    }

    private MissionPublishedResult publishResearchV2(
            MissionSynthesisIntent intent,
            MissionRuntimeAccess.SynthesisRunResult synthesis,
            ReportQualityGate.Result quality,
            boolean degraded) {
        if (degraded && quality.passed()) {
            throw new MissionException(
                    "MISSION_RESULT_SCHEMA_INVALID", "Degraded delivery cannot pass its quality gate");
        }
        ResearchEvidence evidence = evidence(intent.taskResults());
        ReportQualityGate.EvidenceSummary summary = evidenceSummary(evidence);
        String reportText = canonicalizeReportCitations(
                trustedReport(synthesis.structuredOutput(), evidence, summary), evidence.sourceAliases());

        ObjectNode sourcesDocument = mapper.createObjectNode();
        sourcesDocument.put("schemaVersion", "pa.research-sources/v1");
        sourcesDocument.set("sources", array(evidence.sources().values()));
        ObjectNode claimsDocument = mapper.createObjectNode();
        claimsDocument.put("schemaVersion", "pa.claim-evidence/v1");
        claimsDocument.set("claims", array(evidence.claims().values()));
        ObjectNode unresolvedDocument = mapper.createObjectNode();
        unresolvedDocument.put("schemaVersion", "pa.unresolved-questions/v1");
        unresolvedDocument.set("unresolvedQuestions", mapper.valueToTree(evidence.unresolvedQuestions()));

        Artifact report = publish(
                intent, synthesis, "research-report", "research-report.md", reportText, "text/markdown; charset=utf-8");
        Artifact sources = publish(
                intent, synthesis, "research-data", "sources.json", encode(sourcesDocument), "application/json");
        Artifact claims = publish(
                intent, synthesis, "research-data", "claim-evidence.json", encode(claimsDocument), "application/json");
        Artifact unresolved = publish(
                intent,
                synthesis,
                "research-data",
                "unresolved-questions.json",
                encode(unresolvedDocument),
                "application/json");

        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", "pa.research-delivery/v2");
        boolean partial = degraded || !intent.failedItems().isEmpty();
        manifest.put("completionKind", partial ? "PARTIAL" : "COMPLETE");
        manifest.put("degraded", degraded);
        List<String> degradationReasons = degraded
                ? java.util.stream.Stream.concat(
                                quality.failureCodes().stream(), synthesis.degradationReasons().stream())
                        .distinct()
                        .toList()
                : List.of();
        manifest.set("degradationReasons", mapper.valueToTree(degradationReasons));
        ObjectNode reportRef = reference(report);
        ObjectNode sourcesRef = reference(sources);
        ObjectNode claimsRef = reference(claims);
        ObjectNode unresolvedRef = reference(unresolved);
        manifest.set("reportArtifactRef", reportRef);
        manifest.set("sourcesArtifactRef", sourcesRef);
        manifest.set("claimEvidenceArtifactRef", claimsRef);
        manifest.set("unresolvedArtifactRef", unresolvedRef);
        List<String> affectedTaskIds = affectedTaskIds(intent, quality);
        List<String> coveredTaskIds = intent.completedTaskIds().stream()
                .filter(taskId -> !affectedTaskIds.contains(taskId))
                .toList();
        manifest.set("coveredTaskIds", mapper.valueToTree(coveredTaskIds));
        manifest.set("affectedTaskIds", mapper.valueToTree(affectedTaskIds));
        manifest.put("sourceCount", evidence.sources().size());
        LinkedHashSet<String> unverified = new LinkedHashSet<>(evidence.requiredUnverifiedClaimIds());
        unverified.addAll(evidence.aggregateDowngradedClaimIds());
        manifest.put("unverifiedClaimCount", unverified.size());
        manifest.put("unresolvedQuestionCount", evidence.unresolvedQuestions().size());
        ObjectNode evidenceSummary = manifest.putObject("evidenceSummary");
        evidenceSummary.put("totalClaimCount", summary.totalClaims());
        evidenceSummary.put("unverifiedClaimCount", summary.unverifiedClaims());
        evidenceSummary.put("singleSourceClaimCount", summary.singleSourceClaims());
        evidenceSummary.put("counterevidenceClaimCount", summary.counterevidenceClaims());
        evidenceSummary.put(
                "unresolvedQuestionCount", summary.unresolvedQuestions().size());
        ObjectNode efficiency = manifest.putObject("efficiencyMetrics");
        long totalTokens = Math.addExact(
                intent.preSynthesisUsage().modelTokens(), synthesis.usage().modelTokens());
        int validSources = (int) evidence.sources().values().stream()
                .filter(source -> "FETCHED".equals(source.path("status").asText()))
                .count();
        efficiency.put("tokensPerValidSource", ratio(totalTokens, validSources));
        efficiency.put(
                "duplicateSearchFetchRatio",
                ratio(evidence.duplicateResearchOperations(), evidence.researchOperations()));
        int evidenceLinks = evidence.claims().values().stream()
                .mapToInt(claim -> claim.path("supportingSourceIds").size()
                        + claim.path("opposingSourceIds").size())
                .sum();
        efficiency.put("evidencePerMaterialClaim", ratio(evidenceLinks, summary.totalClaims()));
        efficiency.put("singleSourceClaimRatio", ratio(summary.singleSourceClaims(), summary.totalClaims()));
        efficiency.put("synthesisTokenRatio", ratio(synthesis.usage().modelTokens(), totalTokens));
        efficiency.put("qualityGateRevisionCount", synthesis.qualityGateRevisionCount());
        ObjectNode gate = manifest.putObject("qualityGate");
        gate.put("passed", quality.passed());
        gate.set("failedChecks", mapper.valueToTree(quality.failureCodes()));
        validateDeliveryManifest(manifest);
        validateReference(report, reportRef);
        validateReference(sources, sourcesRef);
        validateReference(claims, claimsRef);
        validateReference(unresolved, unresolvedRef);
        validateEvidenceConsistency(manifest, sourcesDocument, claimsDocument, unresolvedDocument);
        if (manifest.toString().contains("research-delivery.json")) {
            throw new MissionException("REPORT_ARTIFACT_INCONSISTENT", "Delivery manifest cannot self-reference");
        }
        Artifact delivery = publish(
                intent, synthesis, "research-delivery", "research-delivery.json", encode(manifest), "application/json");
        List<Artifact> published = List.of(report, sources, claims, unresolved, delivery);
        return new MissionPublishedResult(
                report.id().value(),
                published.stream().map(value -> value.id().value()).toList(),
                evidence.sources().values().stream()
                        .map(value -> value.get("normalizedLocator").asText())
                        .toList(),
                encode(manifest),
                conversationDeliveryMessage(intent, manifest),
                partial ? "PARTIAL" : "COMPLETE");
    }

    static void validateDeliveryManifest(JsonNode manifest) {
        boolean validEnvelope = manifest != null
                && manifest.isObject()
                && "pa.research-delivery/v2"
                        .equals(manifest.path("schemaVersion").asText())
                && ("COMPLETE".equals(manifest.path("completionKind").asText())
                        || "PARTIAL".equals(manifest.path("completionKind").asText()))
                && manifest.path("degraded").isBoolean()
                && manifest.path("degradationReasons").isArray()
                && manifest.path("qualityGate").isObject()
                && manifest.path("qualityGate").path("passed").isBoolean()
                && manifest.path("evidenceSummary").isObject()
                && manifest.path("efficiencyMetrics").isObject();
        if (!validEnvelope) {
            throw new MissionException("MISSION_RESULT_SCHEMA_INVALID", "Research delivery manifest is invalid");
        }
        boolean degraded = manifest.path("degraded").asBoolean();
        boolean complete = "COMPLETE".equals(manifest.path("completionKind").asText());
        boolean hasReasons = !manifest.path("degradationReasons").isEmpty();
        boolean gatePassed = manifest.path("qualityGate").path("passed").asBoolean();
        if ((degraded && (complete || !hasReasons || gatePassed)) || (!degraded && hasReasons)) {
            throw new MissionException(
                    "MISSION_RESULT_SCHEMA_INVALID", "Research delivery degradation semantics are inconsistent");
        }
    }

    private static void validateEvidenceConsistency(
            JsonNode manifest, JsonNode sourcesDocument, JsonNode claimsDocument, JsonNode unresolvedDocument) {
        JsonNode evidence = manifest.path("evidenceSummary");
        JsonNode claims = claimsDocument.path("claims");
        long unverified = java.util.stream.StreamSupport.stream(claims.spliterator(), false)
                .filter(claim -> claim.path("unverified").asBoolean())
                .count();
        long singleSource = java.util.stream.StreamSupport.stream(claims.spliterator(), false)
                .filter(claim -> claim.path("supportingSourceIds").size() == 1)
                .count();
        long counterevidence = java.util.stream.StreamSupport.stream(claims.spliterator(), false)
                .filter(claim -> !claim.path("opposingSourceIds").isEmpty())
                .count();
        boolean consistent = manifest.path("sourceCount").asInt(-1)
                        == sourcesDocument.path("sources").size()
                && manifest.path("unverifiedClaimCount").asLong(-1) == unverified
                && manifest.path("unresolvedQuestionCount").asInt(-1)
                        == unresolvedDocument.path("unresolvedQuestions").size()
                && evidence.path("totalClaimCount").asInt(-1) == claims.size()
                && evidence.path("unverifiedClaimCount").asLong(-1) == unverified
                && evidence.path("singleSourceClaimCount").asLong(-1) == singleSource
                && evidence.path("counterevidenceClaimCount").asLong(-1) == counterevidence
                && evidence.path("unresolvedQuestionCount").asInt(-1)
                        == unresolvedDocument.path("unresolvedQuestions").size();
        if (!consistent) {
            throw new MissionException(
                    "REPORT_ARTIFACT_INCONSISTENT", "Evidence counts contradict the published Artifacts");
        }
    }

    private static String conversationDeliveryMessage(MissionSynthesisIntent intent, JsonNode manifest) {
        String state = "COMPLETE".equals(manifest.path("completionKind").asText()) ? "已完成" : "部分完成";
        return "<!-- haifa-mission-delivery:" + intent.missionId() + " -->\n"
                + "Deep Research Mission " + state + "。完整报告与证据已保存在 Mission 中。\n"
                + "来源 " + manifest.path("sourceCount").asInt() + " 个 · 待核实结论 "
                + manifest.path("unverifiedClaimCount").asInt() + " 个 · 未决问题 "
                + manifest.path("unresolvedQuestionCount").asInt() + " 个";
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) return 0.0d;
        return Math.round(((double) numerator / denominator) * 10_000.0d) / 10_000.0d;
    }

    private static String canonicalizeReportCitations(String report, Map<String, String> sourceAliases) {
        String value = report;
        for (Map.Entry<String, String> alias : sourceAliases.entrySet()) {
            value = value.replace("[[" + alias.getKey() + "]]", "[[" + alias.getValue() + "]]");
        }
        return value;
    }

    private static List<String> affectedTaskIds(MissionSynthesisIntent intent, ReportQualityGate.Result quality) {
        LinkedHashSet<String> affected = new LinkedHashSet<>(quality.affectedTaskIds());
        for (String failedItem : intent.failedItems()) {
            String candidate = failedItem.contains(":") ? failedItem.substring(0, failedItem.indexOf(':')) : failedItem;
            if (!candidate.isBlank()) affected.add(candidate);
        }
        return List.copyOf(affected);
    }

    private static void validateReference(Artifact artifact, JsonNode reference) {
        boolean consistent = artifact.id()
                        .value()
                        .equals(reference.path("artifactId").asText())
                && artifact.version().value() == reference.path("version").asLong()
                && artifact.payload().sha256().equals(reference.path("sha256").asText())
                && artifact.payload().byteCount() == reference.path("byteCount").asLong()
                && artifact.payload()
                        .mediaType()
                        .equals(reference.path("mediaType").asText())
                && artifact.title().equals(reference.path("title").asText());
        if (!consistent) {
            throw new MissionException("REPORT_ARTIFACT_INCONSISTENT", "Published Artifact reference is inconsistent");
        }
    }

    private ResearchEvidence evidence(List<String> taskResults) {
        if (taskResults.isEmpty() || taskResults.size() > MAX_TASK_RESULTS) {
            invalid("Research Task results are unavailable");
        }
        Map<String, JsonNode> sources = new LinkedHashMap<>();
        Map<String, String> sourceAliases = new LinkedHashMap<>();
        List<NormalizedTask> taskObjects = new ArrayList<>();
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        LinkedHashSet<String> uniqueResearchOperations = new LinkedHashSet<>();
        int researchOperations = 0;
        for (String encoded : taskResults) {
            JsonNode task = object(encoded, "Research Task result");
            String schema = task.path("schemaVersion").asText();
            if (!"pa.research-task-result/v1".equals(schema) && !"pa.research-task-result/v2".equals(schema)) {
                invalid("Result schema is unsupported");
            }
            if ("pa.research-task-result/v2".equals(schema)) {
                requiredText(task, "taskSummary", 8_000);
            } else {
                requiredText(task, "brief", 8_000);
            }
            JsonNode queries = requiredArray(task, "queries", 20);
            validateQueries(queries);
            for (JsonNode query : queries) {
                researchOperations++;
                uniqueResearchOperations.add(
                        "search:" + query.path("query").asText().trim().toLowerCase(Locale.ROOT));
            }
            JsonNode limits = requiredObject(task, "limitsUsed");
            validateLimits(limits);
            if (!STOP_REASONS.contains(requiredText(task, "stopReason", 64))) invalid("stopReason is invalid");
            if (!requiredArray(task, "artifactRefs", 8).isEmpty()) {
                invalid("Task result cannot invent Artifact references");
            }
            JsonNode taskSources = requiredArray(task, "sources", maxSources);
            if (limits.get("sources").intValue() != taskSources.size()
                    || limits.get("fetchCalls").intValue()
                            < java.util.stream.StreamSupport.stream(taskSources.spliterator(), false)
                                    .filter(source -> "FETCHED"
                                            .equals(source.path("status").asText()))
                                    .count()) {
                invalid("limitsUsed contradicts the research evidence");
            }
            Map<String, String> taskAliases = new LinkedHashMap<>();
            Map<String, JsonNode> taskSourceIndex = new LinkedHashMap<>();
            for (JsonNode source : taskSources) {
                SourceIdentity identity = validateSource(source);
                researchOperations++;
                uniqueResearchOperations.add("fetch:" + identity.canonicalId());
                String previousAlias = taskAliases.putIfAbsent(identity.originalId(), identity.canonicalId());
                if (previousAlias != null && !previousAlias.equals(identity.canonicalId())) {
                    invalid("Source ID is ambiguous within a Task");
                }
                if (taskSourceIndex.putIfAbsent(identity.canonicalId(), source) != null) {
                    invalid("Source locator is duplicated within a Task");
                }
                String globalAlias = sourceAliases.putIfAbsent(identity.originalId(), identity.canonicalId());
                if (globalAlias != null && !globalAlias.equals(identity.canonicalId())) {
                    invalid("Source ID is ambiguous across Tasks");
                }
                JsonNode previous = sources.putIfAbsent(identity.canonicalId(), source);
                if (previous != null) {
                    sources.put(identity.canonicalId(), preferredSource(previous, source));
                }
            }
            unresolved.addAll(textArray(task, "unresolvedQuestions", 20));
            taskObjects.add(new NormalizedTask(task, taskAliases, taskSourceIndex));
        }
        if (sources.size() > maxSources) invalid("Research evidence exceeds the Mission source limit");

        Map<String, JsonNode> claims = new LinkedHashMap<>();
        LinkedHashSet<String> requiredUnverified = new LinkedHashSet<>();
        for (NormalizedTask task : taskObjects) {
            if (task.value().has("findings")) {
                for (JsonNode finding : requiredArray(task.value(), "findings", 40)) {
                    rewriteFindingSourceRefs(finding, task.sourceAliases());
                    validateFinding(finding, task.sources());
                    String id = stableId(finding, "findingId");
                    ObjectNode claimNode = projectFindingToClaim(finding);
                    JsonNode previous = claims.putIfAbsent(id, claimNode);
                    if (previous != null && !previous.equals(claimNode)) invalid("Finding ID is ambiguous");
                    if (claimNode.path("unverified").asBoolean(false)) requiredUnverified.add(id);
                }
            } else {
                for (JsonNode claim : requiredArray(task.value(), "claims", 40)) {
                    rewriteClaimSourceRefs(claim, task.sourceAliases());
                    validateClaim(claim, task.sources());
                    String id = stableId(claim, "claimId");
                    JsonNode previous = claims.putIfAbsent(id, claim);
                    if (previous != null && !previous.equals(claim)) invalid("Claim ID is ambiguous");
                    if (claim.get("unverified").asBoolean()) requiredUnverified.add(id);
                }
            }
        }
        LinkedHashSet<String> aggregateDowngraded = new LinkedHashSet<>();
        claims.forEach((claimId, claim) -> {
            LinkedHashSet<String> references = new LinkedHashSet<>();
            claim.path("supportingSourceIds").forEach(value -> references.add(value.asText()));
            claim.path("opposingSourceIds").forEach(value -> references.add(value.asText()));
            boolean insufficient = references.stream().anyMatch(reference -> !"FETCHED"
                    .equals(sources.get(reference).path("status").asText()));
            if (insufficient && !claim.path("unverified").asBoolean()) {
                ((ObjectNode) claim).put("unverified", true);
                aggregateDowngraded.add(claimId);
            }
        });
        return new ResearchEvidence(
                sources,
                sourceAliases,
                claims,
                Set.copyOf(requiredUnverified),
                Set.copyOf(aggregateDowngraded),
                List.copyOf(unresolved),
                researchOperations,
                researchOperations - uniqueResearchOperations.size());
    }

    private static ReportQualityGate.EvidenceSummary evidenceSummary(ResearchEvidence evidence) {
        int unverified = (int) evidence.claims().values().stream()
                .filter(claim -> claim.path("unverified").asBoolean())
                .count();
        int singleSource = (int) evidence.claims().values().stream()
                .filter(claim -> claim.path("supportingSourceIds").size() == 1)
                .count();
        int counterevidence = (int) evidence.claims().values().stream()
                .filter(claim -> !claim.path("opposingSourceIds").isEmpty())
                .count();
        return new ReportQualityGate.EvidenceSummary(
                evidence.claims().size(), unverified, singleSource, counterevidence, evidence.unresolvedQuestions());
    }

    private static String trustedReport(
            String modelReport, ResearchEvidence evidence, ReportQualityGate.EvidenceSummary summary) {
        StringBuilder status = new StringBuilder()
                .append("<!-- haifa-section: evidence-summary -->\n")
                .append("## 证据状态\n\n")
                .append("<!-- haifa-evidence-counts: total=")
                .append(summary.totalClaims())
                .append(" unverified=")
                .append(summary.unverifiedClaims())
                .append(" single-source=")
                .append(summary.singleSourceClaims())
                .append(" counterevidence=")
                .append(summary.counterevidenceClaims())
                .append(" unresolved=")
                .append(summary.unresolvedQuestions().size())
                .append(" -->\n")
                .append("- 主要结论：")
                .append(summary.totalClaims())
                .append("\n")
                .append("- 待核实：")
                .append(summary.unverifiedClaims())
                .append("\n")
                .append("- 仅单一来源支持：")
                .append(summary.singleSourceClaims())
                .append("\n")
                .append("- 发现反向证据：")
                .append(summary.counterevidenceClaims())
                .append("\n")
                .append("- 未决问题：")
                .append(summary.unresolvedQuestions().size())
                .append("\n");
        if (summary.unverifiedClaims() > 0) {
            status.append("\n> 本报告包含尚未充分核实的判断，不应解读为所有关键结论均已确认。\n");
        }
        StringBuilder risks = new StringBuilder("\n");
        evidence.claims().forEach((claimId, claim) -> {
            if (claim.path("supportingSourceIds").size() == 1) {
                risks.append("<!-- haifa-single-source-risk: ").append(claimId).append(" -->\n");
            }
        });
        String sourceMarker = "<!-- haifa-section: sources -->";
        String report = modelReport == null ? "" : modelReport;
        int sourceAt = report.toLowerCase(Locale.ROOT).indexOf(sourceMarker);
        if (sourceAt >= 0) {
            report = report.substring(0, sourceAt) + risks + "\n" + report.substring(sourceAt);
        } else {
            report = report + risks;
        }
        return status.append("\n").append(report).toString();
    }

    private SourceIdentity validateSource(JsonNode source) {
        if (!source.isObject() || source.size() != 11) invalid("Source shape is invalid");
        String originalId = stableId(source, "sourceId");
        String locator = requiredText(source, "locator", 4_096);
        ResearchSourceLocator.Normalized normalized = ResearchSourceLocator.normalize(locator);
        ObjectNode canonical = (ObjectNode) source;
        String canonicalId = "source-" + normalized.digest().substring("sha256:".length(), 31);
        canonical.put("sourceId", canonicalId);
        canonical.put("normalizedLocator", normalized.locator());
        canonical.put("locatorDigest", normalized.digest());
        requiredText(source, "title", 1_024);
        if (!SOURCE_SAFETY_TYPES.contains(requiredText(source, "safetyType", 64))) {
            invalid("Source safety type is invalid");
        }
        nullableInstant(source, "fetchedAt");
        nullablePublishedInstant(source, "publishedAt");
        String status = requiredText(source, "status", 64);
        if (!SOURCE_STATUSES.contains(status)) invalid("Source status is invalid");
        optionalText(source, "excerpt", 4_096);
        JsonNode digest = source.get("contentDigest");
        if (digest == null
                || (!digest.isNull()
                        && (!digest.isTextual()
                                || !SHA256.matcher(digest.asText()).matches()))) {
            invalid("Source content digest is invalid");
        }
        if ("FETCHED".equals(status)
                && (source.get("fetchedAt").isNull()
                        || digest.isNull()
                        || source.get("excerpt").asText().isBlank())) {
            invalid("Fetched source evidence is incomplete");
        }
        return new SourceIdentity(originalId, canonicalId);
    }

    private static JsonNode preferredSource(JsonNode first, JsonNode candidate) {
        if ("CONFLICT".equals(first.path("status").asText())) return first;
        if ("CONFLICT".equals(candidate.path("status").asText())) return candidate;
        boolean firstFetched = "FETCHED".equals(first.path("status").asText());
        boolean candidateFetched = "FETCHED".equals(candidate.path("status").asText());
        if (firstFetched && candidateFetched && !first.path("contentDigest").equals(candidate.path("contentDigest"))) {
            ObjectNode conflict = first.deepCopy();
            conflict.put("status", "CONFLICT");
            conflict.putNull("fetchedAt");
            conflict.putNull("contentDigest");
            conflict.put("excerpt", "");
            return conflict;
        }
        return !firstFetched && candidateFetched ? candidate : first;
    }

    private void rewriteFindingSourceRefs(JsonNode finding, Map<String, String> aliases) {
        rewriteSourceIds(requiredArray(finding, "supportingSourceIds", 20), aliases);
        rewriteSourceIds(requiredArray(finding, "opposingSourceIds", 20), aliases);
    }

    private void rewriteClaimSourceRefs(JsonNode claim, Map<String, String> aliases) {
        rewriteSourceIds(requiredArray(claim, "supportingSourceIds", 20), aliases);
        rewriteSourceIds(requiredArray(claim, "opposingSourceIds", 20), aliases);
        for (JsonNode quote : requiredArray(claim, "quotedSpans", 20)) {
            if (!quote.isObject()) invalid("Quoted span shape is invalid");
            String original = requiredText(quote, "sourceId", 128);
            String canonical = aliases.get(original);
            if (canonical == null) invalid("Claim references an unavailable source");
            ((ObjectNode) quote).put("sourceId", canonical);
        }
    }

    private static void rewriteSourceIds(JsonNode values, Map<String, String> aliases) {
        ArrayNode array = (ArrayNode) values;
        for (int index = 0; index < array.size(); index++) {
            String original = array.get(index).asText();
            String canonical = aliases.get(original);
            if (canonical == null) invalid("Claim references an unavailable source");
            array.set(index, array.textNode(canonical));
        }
    }

    private static String canonicalSourceRef(String reference, ResearchEvidence evidence) {
        String canonical = evidence.sourceAliases().get(reference);
        if (canonical == null && evidence.sources().containsKey(reference)) canonical = reference;
        if (canonical == null) invalid("Final result cites an unavailable source");
        return canonical;
    }

    private void validateClaim(JsonNode claim, Map<String, JsonNode> sources) {
        if (!claim.isObject() || claim.size() != 7) invalid("Claim shape is invalid");
        stableId(claim, "claimId");
        requiredText(claim, "claim", 4_000);
        List<String> supporting = textArray(claim, "supportingSourceIds", 20);
        List<String> opposing = textArray(claim, "opposingSourceIds", 20);
        requiredTextAllowEmpty(claim, "limitations", 2_000);
        JsonNode unverifiedNode = claim.get("unverified");
        if (unverifiedNode == null || !unverifiedNode.isBoolean()) invalid("Claim unverified flag is invalid");
        LinkedHashSet<String> references = new LinkedHashSet<>(supporting);
        references.addAll(opposing);
        if (references.isEmpty() || !sources.keySet().containsAll(references)) {
            invalid("Claim references an unavailable source");
        }
        boolean insufficient = supporting.isEmpty()
                || references.stream().anyMatch(id -> !"FETCHED"
                        .equals(sources.get(id).get("status").asText()));
        if (insufficient && !unverifiedNode.asBoolean()) invalid("Insufficiently supported claim must be unverified");
        for (JsonNode quote : requiredArray(claim, "quotedSpans", 20)) {
            if (!quote.isObject() || quote.size() != 2) invalid("Quoted span shape is invalid");
            String sourceId = requiredText(quote, "sourceId", 128);
            if (!references.contains(sourceId)) invalid("Quoted span source is unavailable");
            String text = requiredText(quote, "text", 320);
            if (quotedWords(text) > 25 || cjkCharacters(text) > 80) invalid("Quoted span exceeds the source limit");
        }
    }

    private void validateFinding(JsonNode finding, Map<String, JsonNode> sources) {
        if (!finding.isObject()) invalid("Finding shape is invalid");
        stableId(finding, "findingId");
        requiredText(finding, "title", 512);
        requiredText(finding, "mechanism", 4_000);
        requiredTextAllowEmpty(finding, "evidenceSummary", 4_000);
        requiredTextAllowEmpty(finding, "implications", 3_000);
        requiredTextAllowEmpty(finding, "limitations", 2_000);
        List<String> supporting = textArray(finding, "supportingSourceIds", 20);
        List<String> opposing = textArray(finding, "opposingSourceIds", 20);
        String evidenceAssessment = requiredText(finding, "evidenceAssessment", 32);
        if (!EVIDENCE_ASSESSMENTS.contains(evidenceAssessment)) {
            invalid("Finding evidence assessment is invalid");
        }
        JsonNode unverifiedNode = finding.get("unverified");
        if (unverifiedNode == null || !unverifiedNode.isBoolean()) {
            invalid("Finding unverified flag is invalid");
        }
        LinkedHashSet<String> references = new LinkedHashSet<>(supporting);
        references.addAll(opposing);
        if (references.isEmpty() || !sources.keySet().containsAll(references)) {
            invalid("Finding references an unavailable source");
        }
        boolean insufficient = supporting.isEmpty()
                || !"SUPPORTED".equals(evidenceAssessment)
                || references.stream().anyMatch(id -> !"FETCHED"
                        .equals(sources.get(id).get("status").asText()));
        if (insufficient && !unverifiedNode.asBoolean()) {
            invalid("Insufficiently supported finding must be unverified");
        }
    }

    private ObjectNode projectFindingToClaim(JsonNode finding) {
        ObjectNode claim = mapper.createObjectNode();
        claim.put("claimId", finding.path("findingId").asText());
        String mechanism = finding.path("mechanism").asText();
        String title = finding.path("title").asText();
        claim.put("claim", title + (mechanism.isBlank() ? "" : ": " + mechanism));
        claim.set("supportingSourceIds", finding.path("supportingSourceIds").deepCopy());
        claim.set("opposingSourceIds", finding.path("opposingSourceIds").deepCopy());
        claim.put("limitations", finding.path("limitations").asText());
        claim.put("unverified", finding.path("unverified").asBoolean());
        claim.putArray("quotedSpans");
        if (finding.has("mechanism")) claim.set("mechanism", finding.get("mechanism"));
        if (finding.has("keyParameters")) claim.set("keyParameters", finding.get("keyParameters"));
        if (finding.has("evidenceSummary")) claim.set("evidenceSummary", finding.get("evidenceSummary"));
        if (finding.has("implications")) claim.set("implications", finding.get("implications"));
        if (finding.has("evidenceAssessment")) {
            claim.set("evidenceAssessment", finding.get("evidenceAssessment"));
        }
        return claim;
    }

    private FinalDelivery finalDelivery(JsonNode value, boolean research) {
        String answer = requiredText(value, "directAnswer", 24_000);
        List<String> completed = textArray(value, "completedItems", 40);
        List<String> failed = textArray(value, "failedItems", 40);
        List<String> sourceRefs =
                value.has("sourceRefs") && !value.path("sourceRefs").isNull()
                        ? textArray(value, "sourceRefs", maxSources * MAX_TASK_RESULTS)
                        : List.of();
        List<String> unverified =
                value.has("unverifiedClaims") && !value.path("unverifiedClaims").isNull()
                        ? textArray(value, "unverifiedClaims", MAX_FINAL_UNVERIFIED_CLAIMS)
                        : List.of();
        List<String> unresolved = value.has("unresolvedQuestions")
                        && !value.path("unresolvedQuestions").isNull()
                ? textArray(value, "unresolvedQuestions", 20)
                : List.of();
        List<String> risks =
                value.has("residualRisks") && !value.path("residualRisks").isNull()
                        ? textArray(value, "residualRisks", 20)
                        : List.of();
        String completion = requiredText(value, "completionKind", 16);
        if (!("COMPLETE".equals(completion) || "PARTIAL".equals(completion))) {
            invalid("completionKind is invalid");
        }
        if (("COMPLETE".equals(completion) && !failed.isEmpty())
                || ("PARTIAL".equals(completion) && failed.isEmpty())) {
            invalid("completionKind contradicts failedItems");
        }
        if (!requiredArray(value, "artifactRefs", 8).isEmpty()) {
            invalid("Synthesis cannot invent Artifact references");
        }
        if (research) {
            for (String field : List.of(
                    "reportArtifactRef",
                    "sourcesArtifactRef",
                    "claimEvidenceArtifactRef",
                    "resultArtifactRef",
                    "unresolvedArtifactRef")) {
                JsonNode reference = value.get(field);
                if (reference == null || !reference.isNull()) invalid("Synthesis Artifact placeholder is invalid");
            }
        } else {
            for (String field : List.of("reportArtifactRef", "resultArtifactRef")) {
                if (value.has(field)) {
                    JsonNode reference = value.get(field);
                    if (reference != null && !reference.isNull()) invalid("Synthesis Artifact placeholder is invalid");
                }
            }
        }
        return new FinalDelivery(answer, completed, failed, sourceRefs, unverified, unresolved, risks, completion);
    }

    private StandardMissionQualityGate.Candidate candidateFrom(JsonNode value) {
        String schemaVersion = value.path("schemaVersion").asText("");
        String directAnswer = value.path("directAnswer").asText("");
        String answerMarkdown = value.path("answerMarkdown").asText("");
        String completionKind = value.path("completionKind").asText("");
        List<String> completedItems = extractTextList(value.path("completedItems"));
        List<String> failedItems = extractTextList(value.path("failedItems"));
        List<StandardMissionQualityGate.TaskOutcome> taskOutcomes = new ArrayList<>();
        if (value.has("taskOutcomes") && value.get("taskOutcomes").isArray()) {
            for (JsonNode to : value.get("taskOutcomes")) {
                taskOutcomes.add(new StandardMissionQualityGate.TaskOutcome(
                        to.path("taskId").asText(""), to.path("status").asText("")));
            }
        }
        List<StandardMissionQualityGate.AcceptanceOutcome> acceptanceOutcomes = new ArrayList<>();
        if (value.has("acceptanceOutcomes") && value.get("acceptanceOutcomes").isArray()) {
            for (JsonNode ao : value.get("acceptanceOutcomes")) {
                List<String> taskIds = extractTextList(ao.path("taskIds"));
                acceptanceOutcomes.add(new StandardMissionQualityGate.AcceptanceOutcome(
                        ao.path("criterionIndex").asInt(-1), ao.path("status").asText(""), taskIds));
            }
        }
        List<SourceReference> sources = new ArrayList<>();
        if (value.has("sources") && value.get("sources").isArray()) {
            for (JsonNode s : value.get("sources")) {
                String sourceId = s.path("sourceId").asText("");
                String title = s.path("title").asText("");
                String locator = s.path("locator").asText("");
                try {
                    sources.add(new SourceReference(sourceId, title, locator));
                } catch (IllegalArgumentException exception) {
                    invalid("Standard v2 sources contains invalid locator or sourceId: " + exception.getMessage());
                }
            }
        }
        List<StandardMissionQualityGate.SectionSource> sectionSources = new ArrayList<>();
        if (value.has("sectionSources") && value.get("sectionSources").isArray()) {
            for (JsonNode sec : value.get("sectionSources")) {
                String heading = sec.has("sectionHeading")
                        ? sec.path("sectionHeading").asText("")
                        : sec.path("section").asText("");
                List<String> sourceIds = extractTextList(sec.path("sourceIds"));
                sectionSources.add(new StandardMissionQualityGate.SectionSource(heading, sourceIds));
            }
        }
        List<String> sourceRefs = extractTextList(value.path("sourceRefs"));
        return new StandardMissionQualityGate.Candidate(
                schemaVersion,
                directAnswer,
                answerMarkdown,
                completionKind,
                completedItems,
                failedItems,
                taskOutcomes,
                acceptanceOutcomes,
                sectionSources,
                sources,
                sourceRefs);
    }

    private static List<String> extractTextList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) {
                list.add(item.asText().trim());
            }
        });
        return List.copyOf(list);
    }

    private void validateStandardV2Shape(JsonNode value) {
        if (!value.isObject()) invalid("Standard v2 result shape is invalid");
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        for (String req : STANDARD_V2_REQUIRED_FIELDS) {
            if (!fields.contains(req)) invalid("Standard v2 result shape is invalid");
        }
        for (String f : fields) {
            if (!STANDARD_V2_ALLOWED_FIELDS.contains(f)) invalid("Standard v2 result shape is invalid");
        }
        requiredText(value, "directAnswer", 4_000);
        requiredText(value, "answerMarkdown", 240_000);
        if (value.has("taskOutcomes") && !value.path("taskOutcomes").isNull()) {
            JsonNode taskOutcomes = value.get("taskOutcomes");
            if (!taskOutcomes.isArray()) invalid("Standard v2 result shape is invalid");
        }
        if (value.has("acceptanceOutcomes") && !value.path("acceptanceOutcomes").isNull()) {
            JsonNode acceptanceOutcomes = value.get("acceptanceOutcomes");
            if (!acceptanceOutcomes.isArray()) invalid("Standard v2 result shape is invalid");
        }
        if (value.has("sectionSources") && !value.path("sectionSources").isNull()) {
            JsonNode sectionSources = value.get("sectionSources");
            if (!sectionSources.isArray()) invalid("Standard v2 result shape is invalid");
            for (JsonNode sec : sectionSources) {
                if (!sec.isObject()) invalid("Standard v2 result shape is invalid");
                String heading = sec.has("sectionHeading")
                        ? sec.path("sectionHeading").asText("")
                        : sec.path("section").asText("");
                if (heading.isBlank() || heading.length() > 512) {
                    invalid("Standard v2 sectionSources element is invalid");
                }
                if (!sec.has("sourceIds")
                        || !sec.get("sourceIds").isArray()
                        || sec.get("sourceIds").isEmpty()) {
                    invalid("Standard v2 sectionSources element is invalid");
                }
            }
        }
        if (value.has("sources") && !value.path("sources").isNull()) {
            JsonNode sourcesNode = value.get("sources");
            if (!sourcesNode.isArray()) invalid("Standard v2 result shape is invalid");
            for (JsonNode s : sourcesNode) {
                if (!s.isObject()) invalid("Standard v2 sources element is invalid");
                requiredText(s, "sourceId", 128);
                requiredText(s, "locator", 2_048);
            }
        }
    }

    private Artifact publish(
            MissionSynthesisIntent intent,
            MissionRuntimeAccess.SynthesisRunResult synthesis,
            String type,
            String title,
            String content,
            String mediaType) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String projectId = "mission-" + intent.missionId();
        String sourceHash = "sha256:" + sha256(bytes);
        List<Artifact> existing = artifacts.findByProject(projectId).stream()
                .filter(value -> value.title().equals(title))
                .toList();
        if (existing.size() == 1) {
            Artifact artifact = existing.getFirst();
            if (!artifact.provenance().sourceHash().equals(sourceHash)
                    || !artifact.payload().sha256().equals(sourceHash)
                    || artifact.payload().byteCount() != bytes.length
                    || !artifact.payload().mediaType().equals(mediaType)) {
                throw new MissionException("MISSION_ARTIFACT_CONFLICT", "Frozen Mission Artifact content changed");
            }
            return artifact;
        }
        if (existing.size() > 1) {
            throw new MissionException("MISSION_ARTIFACT_CONFLICT", "Mission Artifact identity is ambiguous");
        }
        List<Artifact> all = artifacts.findByProject(projectId);
        long existingBytes =
                all.stream().mapToLong(value -> value.payload().byteCount()).sum();
        if (all.size() >= maxArtifacts || Math.addExact(existingBytes, bytes.length) > maxTotalArtifactBytes) {
            throw new MissionException("MISSION_ARTIFACT_LIMIT_EXCEEDED", "Mission Artifact capacity is exhausted");
        }
        String[] owner = intent.ownerScope().split("/", 2);
        String principal = owner.length == 2 ? owner[1] : intent.ownerScope();
        return artifacts.publish(
                new ArtifactType(type),
                title,
                bytes,
                mediaType,
                new ArtifactProvenance(
                        new ProjectRef(projectId),
                        "personal-assistant",
                        new AgentRunId(synthesis.runId()),
                        new AgentSessionId(synthesis.sessionId()),
                        null,
                        SdkMissionRuntimeAccess.synthesisDispatchKey(intent.missionId()),
                        title,
                        sourceHash,
                        "owner-only",
                        new PrincipalRef(principal, "user")));
    }

    private ObjectNode reference(Artifact artifact) {
        ObjectNode value = mapper.createObjectNode();
        value.put("artifactId", artifact.id().value());
        value.put("version", artifact.version().value());
        value.put("sha256", artifact.payload().sha256());
        value.put("byteCount", artifact.payload().byteCount());
        value.put("mediaType", artifact.payload().mediaType());
        value.put("title", artifact.title());
        return value;
    }

    private ArrayNode array(java.util.Collection<JsonNode> values) {
        ArrayNode result = mapper.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private JsonNode object(String encoded, String label) {
        if (encoded == null || encoded.length() > 256_000) invalid(label + " exceeds its limit");
        try {
            JsonNode value = mapper.readTree(encoded);
            if (value == null || !value.isObject()) invalid(label + " must be a JSON object");
            return value;
        } catch (JsonProcessingException exception) {
            throw new MissionException("MISSION_RESULT_SCHEMA_INVALID", label + " is invalid JSON", exception);
        }
    }

    private static void requireSchema(JsonNode value, String schema) {
        if (!schema.equals(value.path("schemaVersion").asText())) invalid("Result schema is unsupported");
    }

    private static String stableId(JsonNode value, String field) {
        String id = requiredText(value, field, 128);
        if (!STABLE_ID.matcher(id).matches()) invalid(field + " is invalid");
        return id;
    }

    private static String requiredText(JsonNode value, String field, int maximum) {
        JsonNode node = value.get(field);
        if (node == null
                || !node.isTextual()
                || node.asText().isBlank()
                || node.asText().length() > maximum) {
            invalid(field + " is invalid");
        }
        return node.asText();
    }

    private static String requiredTextAllowEmpty(JsonNode value, String field, int maximum) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.asText().length() > maximum) invalid(field + " is invalid");
        return node.asText();
    }

    private static String optionalText(JsonNode value, String field, int maximum) {
        return requiredTextAllowEmpty(value, field, maximum);
    }

    private static JsonNode requiredArray(JsonNode value, String field, int maximum) {
        JsonNode node = value.get(field);
        if (node == null || !node.isArray() || node.size() > maximum) invalid(field + " is invalid");
        return node;
    }

    private static JsonNode requiredObject(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isObject()) invalid(field + " is invalid");
        return node;
    }

    private static List<String> textArray(JsonNode value, String field, int maximum) {
        JsonNode values = requiredArray(value, field, maximum);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode item : values) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 4_096) {
                invalid(field + " contains an invalid value");
            }
            if (!result.add(item.asText())) invalid(field + " contains a duplicate value");
        }
        return List.copyOf(result);
    }

    private static void validateQueries(JsonNode queries) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonNode query : queries) {
            if (!query.isObject() || query.size() != 2) invalid("Research query shape is invalid");
            String text = requiredText(query, "query", 2_048);
            if (!QUERY_PHASES.contains(requiredText(query, "phase", 32))) invalid("Research query phase is invalid");
            if (!unique.add(text)) invalid("Research query is duplicated");
        }
        if (unique.isEmpty()) invalid("Research queries are unavailable");
    }

    private void validateLimits(JsonNode limits) {
        if (limits.size() != 4) invalid("limitsUsed shape is invalid");
        boundedInteger(limits, "searchCalls", 100);
        boundedInteger(limits, "fetchCalls", 100);
        boundedInteger(limits, "sources", maxSources);
        boundedInteger(limits, "contentBytes", maxTotalContentBytes);
    }

    private static void boundedInteger(JsonNode value, String field, int maximum) {
        JsonNode node = value.get(field);
        if (node == null || !node.canConvertToInt() || node.intValue() < 0 || node.intValue() > maximum) {
            invalid(field + " exceeds its limit");
        }
    }

    private static void nullableInstant(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || (!node.isNull() && !node.isTextual())) invalid(field + " is invalid");
        if (node.isTextual()) {
            try {
                Instant.parse(node.asText());
            } catch (DateTimeParseException exception) {
                throw new MissionException("MISSION_RESULT_SCHEMA_INVALID", field + " is invalid", exception);
            }
        }
    }

    private static void nullablePublishedInstant(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || (!node.isNull() && !node.isTextual())) invalid(field + " is invalid");
        if (!node.isTextual()) return;
        try {
            ((ObjectNode) value).put(field, Instant.parse(node.asText()).toString());
        } catch (DateTimeParseException instantFailure) {
            try {
                ((ObjectNode) value)
                        .put(
                                field,
                                LocalDate.parse(node.asText())
                                        .atStartOfDay(ZoneOffset.UTC)
                                        .toInstant()
                                        .toString());
            } catch (DateTimeParseException dateFailure) {
                throw new MissionException("MISSION_RESULT_SCHEMA_INVALID", field + " is invalid", dateFailure);
            }
        }
    }

    private static int quotedWords(String text) {
        String normalized = text.replaceAll("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]", " ")
                .trim();
        return normalized.isEmpty() ? 0 : normalized.split("\\s+").length;
    }

    private static long cjkCharacters(String text) {
        return text.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HIRAGANA
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.KATAKANA
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL)
                .count();
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new MissionException(
                    "MISSION_RESULT_SCHEMA_INVALID", "Research Artifact cannot be encoded", exception);
        }
    }

    private static String report(FinalDelivery delivery, ResearchEvidence evidence) {
        StringBuilder value = new StringBuilder("# Research report\n\n## Answer\n\n").append(delivery.directAnswer());
        value.append("\n\n## Completion\n\n- ")
                .append(delivery.completionKind())
                .append('\n');
        delivery.completedItems()
                .forEach(item -> value.append("- Completed: ").append(item).append('\n'));
        delivery.failedItems()
                .forEach(item -> value.append("- Failed: ").append(item).append('\n'));
        value.append("\n## Unverified claims\n\n");
        delivery.unverifiedClaims()
                .forEach(item -> value.append("- ").append(item).append('\n'));
        value.append("\n## Conflicts and residual risks\n\n");
        delivery.residualRisks().forEach(item -> value.append("- ").append(item).append('\n'));
        value.append("\n## Unresolved questions\n\n");
        LinkedHashSet<String> unresolved = new LinkedHashSet<>(delivery.unresolvedQuestions());
        unresolved.addAll(evidence.unresolvedQuestions());
        unresolved.forEach(item -> value.append("- ").append(item).append('\n'));
        value.append("\n## Sources\n\n");
        evidence.sources().forEach((id, source) -> value.append("- [")
                .append(source.get("title").asText())
                .append("](")
                .append(source.get("normalizedLocator").asText())
                .append(") (`")
                .append(id)
                .append("`, ")
                .append(source.get("status").asText())
                .append(")\n"));
        return value.toString();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static void invalid(String message) {
        throw new MissionException("MISSION_RESULT_SCHEMA_INVALID", message);
    }

    private record ResearchEvidence(
            Map<String, JsonNode> sources,
            Map<String, String> sourceAliases,
            Map<String, JsonNode> claims,
            Set<String> requiredUnverifiedClaimIds,
            Set<String> aggregateDowngradedClaimIds,
            List<String> unresolvedQuestions,
            int researchOperations,
            int duplicateResearchOperations) {}

    private record NormalizedTask(JsonNode value, Map<String, String> sourceAliases, Map<String, JsonNode> sources) {}

    private record SourceIdentity(String originalId, String canonicalId) {}

    private record FinalDelivery(
            String directAnswer,
            List<String> completedItems,
            List<String> failedItems,
            List<String> sourceRefs,
            List<String> unverifiedClaims,
            List<String> unresolvedQuestions,
            List<String> residualRisks,
            String completionKind) {}
}
