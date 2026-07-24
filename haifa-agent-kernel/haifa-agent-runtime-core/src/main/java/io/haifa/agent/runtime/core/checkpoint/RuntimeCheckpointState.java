package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRef;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRef;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Runtime-owned continuation state referenced by a Core checkpoint. */
public record RuntimeCheckpointState(
        AgentRunId runId,
        AgentSessionId sessionId,
        TenantRef tenant,
        PrincipalRef principal,
        int nextIteration,
        List<String> decisionFingerprints,
        MessageCursor sessionMessageCursor,
        Optional<SummaryCheckpointRef> activeSummary,
        RunConfigurationSnapshotRef configurationSnapshot,
        String modelConfigurationDigest,
        String contextPolicyVersion,
        String estimatorVersion,
        String compressorVersion,
        List<ToolCheckpointRef> toolCalls,
        Optional<InteractionRequestRef> pendingInteraction,
        int forcedContextRebuildAttempts,
        List<AssetRef> derivedContentReferences,
        List<MemoryCheckpointRef> selectedMemories,
        String memoryRetrievalPolicyVersion,
        String memoryQueryDigest,
        List<ModelContinuationRef> modelContinuations,
        List<SkillCheckpointRef> skillActivations,
        List<CapabilityCheckpointRef> capabilityCheckpoints,
        Instant capturedAt) {
    public RuntimeCheckpointState(
            AgentRunId runId,
            AgentSessionId sessionId,
            TenantRef tenant,
            PrincipalRef principal,
            int nextIteration,
            List<String> decisionFingerprints,
            MessageCursor sessionMessageCursor,
            Optional<SummaryCheckpointRef> activeSummary,
            RunConfigurationSnapshotRef configurationSnapshot,
            String modelConfigurationDigest,
            String contextPolicyVersion,
            String estimatorVersion,
            String compressorVersion,
            List<ToolCheckpointRef> toolCalls,
            Optional<InteractionRequestRef> pendingInteraction,
            int forcedContextRebuildAttempts,
            List<AssetRef> derivedContentReferences,
            List<MemoryCheckpointRef> selectedMemories,
            String memoryRetrievalPolicyVersion,
            String memoryQueryDigest,
            List<CapabilityCheckpointRef> capabilityCheckpoints,
            Instant capturedAt) {
        this(
                runId,
                sessionId,
                tenant,
                principal,
                nextIteration,
                decisionFingerprints,
                sessionMessageCursor,
                activeSummary,
                configurationSnapshot,
                modelConfigurationDigest,
                contextPolicyVersion,
                estimatorVersion,
                compressorVersion,
                toolCalls,
                pendingInteraction,
                forcedContextRebuildAttempts,
                derivedContentReferences,
                selectedMemories,
                memoryRetrievalPolicyVersion,
                memoryQueryDigest,
                List.of(),
                List.of(),
                capabilityCheckpoints,
                capturedAt);
    }

    public RuntimeCheckpointState(
            AgentRunId runId,
            AgentSessionId sessionId,
            TenantRef tenant,
            PrincipalRef principal,
            int nextIteration,
            List<String> decisionFingerprints,
            MessageCursor sessionMessageCursor,
            Optional<SummaryCheckpointRef> activeSummary,
            RunConfigurationSnapshotRef configurationSnapshot,
            String modelConfigurationDigest,
            String contextPolicyVersion,
            String estimatorVersion,
            String compressorVersion,
            List<ToolCheckpointRef> toolCalls,
            Optional<InteractionRequestRef> pendingInteraction,
            int forcedContextRebuildAttempts,
            List<AssetRef> derivedContentReferences,
            List<MemoryCheckpointRef> selectedMemories,
            String memoryRetrievalPolicyVersion,
            String memoryQueryDigest,
            Instant capturedAt) {
        this(
                runId,
                sessionId,
                tenant,
                principal,
                nextIteration,
                decisionFingerprints,
                sessionMessageCursor,
                activeSummary,
                configurationSnapshot,
                modelConfigurationDigest,
                contextPolicyVersion,
                estimatorVersion,
                compressorVersion,
                toolCalls,
                pendingInteraction,
                forcedContextRebuildAttempts,
                derivedContentReferences,
                selectedMemories,
                memoryRetrievalPolicyVersion,
                memoryQueryDigest,
                List.of(),
                List.of(),
                List.of(),
                capturedAt);
    }

    public RuntimeCheckpointState {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        if (nextIteration < 1) throw new IllegalArgumentException("nextIteration must be positive");
        decisionFingerprints =
                List.copyOf(Objects.requireNonNull(decisionFingerprints, "decisionFingerprints must not be null"));
        sessionMessageCursor = Objects.requireNonNull(sessionMessageCursor, "sessionMessageCursor must not be null");
        activeSummary = Objects.requireNonNull(activeSummary, "activeSummary must not be null");
        configurationSnapshot = Objects.requireNonNull(configurationSnapshot, "configurationSnapshot must not be null");
        modelConfigurationDigest = requireText(modelConfigurationDigest, "modelConfigurationDigest");
        contextPolicyVersion = requireText(contextPolicyVersion, "contextPolicyVersion");
        estimatorVersion = requireText(estimatorVersion, "estimatorVersion");
        compressorVersion = requireText(compressorVersion, "compressorVersion");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls must not be null"));
        pendingInteraction = Objects.requireNonNull(pendingInteraction, "pendingInteraction must not be null");
        if (forcedContextRebuildAttempts < 0 || forcedContextRebuildAttempts > 1) {
            throw new IllegalArgumentException("forcedContextRebuildAttempts must be zero or one");
        }
        derivedContentReferences = List.copyOf(
                Objects.requireNonNull(derivedContentReferences, "derivedContentReferences must not be null"));
        selectedMemories = List.copyOf(Objects.requireNonNull(selectedMemories, "selectedMemories must not be null"));
        memoryRetrievalPolicyVersion = requireText(memoryRetrievalPolicyVersion, "memoryRetrievalPolicyVersion");
        memoryQueryDigest = requireText(memoryQueryDigest, "memoryQueryDigest");
        modelContinuations =
                List.copyOf(Objects.requireNonNull(modelContinuations, "modelContinuations must not be null"));
        skillActivations = List.copyOf(Objects.requireNonNull(skillActivations, "skillActivations must not be null"));
        capabilityCheckpoints =
                List.copyOf(Objects.requireNonNull(capabilityCheckpoints, "capabilityCheckpoints must not be null"));
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
