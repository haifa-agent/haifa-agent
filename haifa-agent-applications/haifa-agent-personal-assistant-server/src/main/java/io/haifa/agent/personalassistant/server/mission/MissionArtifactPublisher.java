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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict research schema, source identity, citation closure, and immutable Artifact publication. */
public final class MissionArtifactPublisher implements MissionResultPublisher {
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

    public MissionArtifactPublisher(ArtifactService artifacts, ObjectMapper mapper) {
        this(artifacts, mapper, 24, 2_097_152, 8, 4L * 1024 * 1024);
    }

    public MissionArtifactPublisher(
            ArtifactService artifacts,
            ObjectMapper mapper,
            int maxSources,
            int maxTotalContentBytes,
            int maxArtifacts,
            long maxTotalArtifactBytes) {
        this.artifacts = java.util.Objects.requireNonNull(artifacts);
        this.mapper = java.util.Objects.requireNonNull(mapper).copy();
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
        JsonNode finalResult = object(synthesis.structuredOutput(), "Synthesis result");
        String schema = intent.mode() == MissionMode.DEEP_RESEARCH
                ? "pa.research-final-result/v1"
                : "pa.mission-final-result/v1";
        requireSchema(finalResult, schema);
        FinalDelivery delivery = finalDelivery(finalResult, intent.mode() == MissionMode.DEEP_RESEARCH);
        if (intent.mode() == MissionMode.DEEP_RESEARCH) {
            return publishResearch(intent, synthesis, finalResult, delivery);
        }
        Artifact artifact = publish(
                intent,
                synthesis,
                "mission-result",
                "mission-result.json",
                synthesis.structuredOutput(),
                "application/json");
        return new MissionPublishedResult(
                artifact.id().value(),
                List.of(artifact.id().value()),
                List.of(),
                synthesis.structuredOutput(),
                delivery.directAnswer(),
                delivery.completionKind());
    }

    private MissionPublishedResult publishResearch(
            MissionSynthesisIntent intent,
            MissionRuntimeAccess.SynthesisRunResult synthesis,
            JsonNode finalResult,
            FinalDelivery delivery) {
        ResearchEvidence evidence = evidence(intent.taskResults());
        List<String> canonicalSourceRefs = delivery.sourceRefs().stream()
                .map(reference -> canonicalSourceRef(reference, evidence))
                .distinct()
                .toList();
        if (canonicalSourceRefs.size() > maxSources) {
            invalid("Final result cites too many distinct sources");
        }
        delivery = new FinalDelivery(
                delivery.directAnswer(),
                delivery.completedItems(),
                delivery.failedItems(),
                canonicalSourceRefs,
                delivery.unverifiedClaims(),
                delivery.unresolvedQuestions(),
                delivery.residualRisks(),
                delivery.completionKind());
        ((ObjectNode) finalResult).set("sourceRefs", mapper.valueToTree(canonicalSourceRefs));
        if (!evidence.claims().keySet().containsAll(delivery.unverifiedClaims())) {
            invalid("Final result names an unavailable unverified claim");
        }
        if (!delivery.unverifiedClaims().containsAll(evidence.requiredUnverifiedClaimIds())) {
            invalid("Final result omitted an unverified claim");
        }
        LinkedHashSet<String> canonicalUnverified = new LinkedHashSet<>(delivery.unverifiedClaims());
        canonicalUnverified.addAll(evidence.aggregateDowngradedClaimIds());
        delivery = new FinalDelivery(
                delivery.directAnswer(),
                delivery.completedItems(),
                delivery.failedItems(),
                delivery.sourceRefs(),
                List.copyOf(canonicalUnverified),
                delivery.unresolvedQuestions(),
                delivery.residualRisks(),
                delivery.completionKind());
        ((ObjectNode) finalResult).set("unverifiedClaims", mapper.valueToTree(canonicalUnverified));

        ObjectNode sourcesDocument = mapper.createObjectNode();
        sourcesDocument.put("schemaVersion", "pa.research-sources/v1");
        sourcesDocument.set("sources", array(evidence.sources().values()));
        ObjectNode claimsDocument = mapper.createObjectNode();
        claimsDocument.put("schemaVersion", "pa.claim-evidence/v1");
        claimsDocument.set("claims", array(evidence.claims().values()));
        ObjectNode unresolvedDocument = mapper.createObjectNode();
        unresolvedDocument.put("schemaVersion", "pa.unresolved-questions/v1");
        unresolvedDocument.set("unresolvedQuestions", mapper.valueToTree(evidence.unresolvedQuestions()));

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
        String reportText = report(delivery, evidence);
        Artifact report = publish(
                intent, synthesis, "research-report", "research-report.md", reportText, "text/markdown; charset=utf-8");

        ObjectNode resultDocument = finalResult.deepCopy();
        resultDocument.set("reportArtifactRef", reference(report));
        resultDocument.set("sourcesArtifactRef", reference(sources));
        resultDocument.set("claimEvidenceArtifactRef", reference(claims));
        resultDocument.putNull("resultArtifactRef");
        resultDocument.set("unresolvedArtifactRef", reference(unresolved));
        ArrayNode priorRefs = mapper.createArrayNode();
        priorRefs.add(reference(report));
        priorRefs.add(reference(sources));
        priorRefs.add(reference(claims));
        priorRefs.add(reference(unresolved));
        resultDocument.set("artifactRefs", priorRefs);
        Artifact result = publish(
                intent, synthesis, "research-data", "research-result.json", encode(resultDocument), "application/json");

        ObjectNode storedResult = resultDocument.deepCopy();
        storedResult.set("resultArtifactRef", reference(result));
        ArrayNode allRefs = mapper.createArrayNode();
        List.of(report, sources, claims, result, unresolved).forEach(value -> allRefs.add(reference(value)));
        storedResult.set("artifactRefs", allRefs);
        List<Artifact> published = List.of(report, sources, claims, result, unresolved);
        return new MissionPublishedResult(
                report.id().value(),
                published.stream().map(value -> value.id().value()).toList(),
                evidence.sources().values().stream()
                        .map(value -> value.get("normalizedLocator").asText())
                        .toList(),
                encode(storedResult),
                reportText,
                delivery.completionKind());
    }

    private ResearchEvidence evidence(List<String> taskResults) {
        if (taskResults.isEmpty() || taskResults.size() > MAX_TASK_RESULTS) {
            invalid("Research Task results are unavailable");
        }
        Map<String, JsonNode> sources = new LinkedHashMap<>();
        Map<String, String> sourceAliases = new LinkedHashMap<>();
        List<NormalizedTask> taskObjects = new ArrayList<>();
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        for (String encoded : taskResults) {
            JsonNode task = object(encoded, "Research Task result");
            requireSchema(task, "pa.research-task-result/v1");
            requiredText(task, "brief", 8_000);
            JsonNode queries = requiredArray(task, "queries", 20);
            validateQueries(queries);
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
            for (JsonNode claim : requiredArray(task.value(), "claims", 40)) {
                rewriteClaimSourceRefs(claim, task.sourceAliases());
                validateClaim(claim, task.sources());
                String id = stableId(claim, "claimId");
                JsonNode previous = claims.putIfAbsent(id, claim);
                if (previous != null && !previous.equals(claim)) invalid("Claim ID is ambiguous");
                if (claim.get("unverified").asBoolean()) requiredUnverified.add(id);
            }
        }
        LinkedHashSet<String> aggregateDowngraded = new LinkedHashSet<>();
        claims.forEach((claimId, claim) -> {
            LinkedHashSet<String> references = new LinkedHashSet<>();
            claim.path("supportingSourceIds").forEach(value -> references.add(value.asText()));
            claim.path("opposingSourceIds").forEach(value -> references.add(value.asText()));
            boolean insufficient = references.stream().anyMatch(reference ->
                    !"FETCHED".equals(sources.get(reference).path("status").asText()));
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
                List.copyOf(unresolved));
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
        if (firstFetched && candidateFetched
                && !first.path("contentDigest").equals(candidate.path("contentDigest"))) {
            ObjectNode conflict = first.deepCopy();
            conflict.put("status", "CONFLICT");
            conflict.putNull("fetchedAt");
            conflict.putNull("contentDigest");
            conflict.put("excerpt", "");
            return conflict;
        }
        return !firstFetched && candidateFetched ? candidate : first;
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

    private FinalDelivery finalDelivery(JsonNode value, boolean research) {
        String answer = requiredText(value, "directAnswer", 24_000);
        List<String> completed = textArray(value, "completedItems", 40);
        List<String> failed = textArray(value, "failedItems", 40);
        List<String> sourceRefs = textArray(value, "sourceRefs", maxSources * MAX_TASK_RESULTS);
        List<String> unverified = textArray(value, "unverifiedClaims", MAX_FINAL_UNVERIFIED_CLAIMS);
        List<String> unresolved = textArray(value, "unresolvedQuestions", 20);
        List<String> risks = textArray(value, "residualRisks", 20);
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
        }
        return new FinalDelivery(answer, completed, failed, sourceRefs, unverified, unresolved, risks, completion);
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
                        .put(field, LocalDate.parse(node.asText()).atStartOfDay(ZoneOffset.UTC).toInstant().toString());
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
            List<String> unresolvedQuestions) {}

    private record NormalizedTask(
            JsonNode value, Map<String, String> sourceAliases, Map<String, JsonNode> sources) {}

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
