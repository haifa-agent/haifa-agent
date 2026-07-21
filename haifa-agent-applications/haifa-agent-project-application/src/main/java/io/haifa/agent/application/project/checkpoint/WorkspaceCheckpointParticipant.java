package io.haifa.agent.application.project.checkpoint;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.project.snapshot.WorkspaceDriftKind;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotService;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotStore;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotValidator;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointCaptureContext;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointCaptureStatus;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointParticipant;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointParticipantId;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRef;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRestoreContext;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointValidation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class WorkspaceCheckpointParticipant implements CapabilityCheckpointParticipant {
    public static final String CAPABILITY_ID = "project.workspace";
    private static final CapabilityCheckpointParticipantId ID =
            new CapabilityCheckpointParticipantId("project.workspace.snapshot");
    private static final String VERSION = "1.0";

    private final WorkspaceSnapshotService snapshots;
    private final WorkspaceSnapshotStore snapshotStore;
    private final WorkspaceCheckpointStateStore states;
    private final WorkspaceCheckpointResolver resolver;
    private final WorkspaceCurrentEvidenceProvider evidence;
    private final WorkspaceSnapshotValidator validator;
    private final IdentifierGenerator ids;
    private final WorkspaceIsolatedRestorePort isolatedRestore;

    public WorkspaceCheckpointParticipant(
            WorkspaceSnapshotService snapshots,
            WorkspaceSnapshotStore snapshotStore,
            WorkspaceCheckpointStateStore states,
            WorkspaceCheckpointResolver resolver,
            WorkspaceCurrentEvidenceProvider evidence,
            WorkspaceSnapshotValidator validator,
            IdentifierGenerator ids) {
        this(
                snapshots,
                snapshotStore,
                states,
                resolver,
                evidence,
                validator,
                ids,
                WorkspaceIsolatedRestorePort.unavailable());
    }

    public WorkspaceCheckpointParticipant(
            WorkspaceSnapshotService snapshots,
            WorkspaceSnapshotStore snapshotStore,
            WorkspaceCheckpointStateStore states,
            WorkspaceCheckpointResolver resolver,
            WorkspaceCurrentEvidenceProvider evidence,
            WorkspaceSnapshotValidator validator,
            IdentifierGenerator ids,
            WorkspaceIsolatedRestorePort isolatedRestore) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
        this.states = Objects.requireNonNull(states);
        this.resolver = Objects.requireNonNull(resolver);
        this.evidence = Objects.requireNonNull(evidence);
        this.validator = Objects.requireNonNull(validator);
        this.ids = Objects.requireNonNull(ids);
        this.isolatedRestore = Objects.requireNonNull(isolatedRestore);
    }

    @Override
    public CapabilityCheckpointParticipantId id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String capabilityId() {
        return CAPABILITY_ID;
    }

    @Override
    public CapabilityCheckpointRef capture(CapabilityCheckpointCaptureContext context) {
        WorkspaceCheckpointPlan plan = resolver.capturePlan(context);
        var snapshot = snapshots.capture(new WorkspaceSnapshotService.CaptureRequest(
                context.runId().value() + ":" + context.checkpointRef(),
                plan.workspaceId(),
                plan.strategy(),
                plan.providerId(),
                plan.providerVersion(),
                context.runId().value(),
                context.checkpointRef(),
                plan.changeSetRefs(),
                plan.retentionPolicy()));
        String payloadRef = "workspace-checkpoint/" + ids.nextValue();
        WorkspaceCheckpointState state = new WorkspaceCheckpointState(
                snapshot.id(),
                snapshot.workspaceId(),
                plan.bindingRef(),
                snapshot.strategy(),
                snapshot.providerId(),
                snapshot.providerVersion(),
                snapshot.contentDigest());
        String digest = digest(state);
        states.create(payloadRef, state);
        return new CapabilityCheckpointRef(
                CAPABILITY_ID, ID, VERSION, payloadRef, digest, CapabilityCheckpointCaptureStatus.CAPTURED);
    }

    @Override
    public CapabilityCheckpointValidation validate(
            CapabilityCheckpointRef reference, CapabilityCheckpointRestoreContext context) {
        WorkspaceCheckpointState state = states.find(reference.payloadRef()).orElse(null);
        if (state == null || !reference.stateDigest().equals(digest(state))) {
            return CapabilityCheckpointValidation.rejected(
                    WorkspaceDriftKind.SNAPSHOT_MISSING_OR_CORRUPT.name(),
                    "workspace checkpoint payload is missing or corrupt");
        }
        var snapshot = snapshotStore.find(state.snapshotId()).orElse(null);
        if (snapshot == null || !snapshot.contentDigest().equals(state.snapshotDigest())) {
            return CapabilityCheckpointValidation.rejected(
                    WorkspaceDriftKind.SNAPSHOT_MISSING_OR_CORRUPT.name(), "workspace snapshot is missing or changed");
        }
        WorkspaceCheckpointAccess access = resolver.currentAccess(context, state);
        var currentEvidence = access.workspace() == null
                ? snapshot.evidence()
                : evidence.inspect(access.workspace(), state.strategy());
        var decision = validator.validate(new WorkspaceSnapshotValidator.ValidationRequest(
                snapshot,
                access.workspace(),
                access.binding(),
                access.authorized(),
                state.providerId(),
                state.providerVersion(),
                currentEvidence));
        if (decision.kind() == WorkspaceDriftKind.NO_DRIFT
                || decision.kind() == WorkspaceDriftKind.SAFE_METADATA_DRIFT) {
            return CapabilityCheckpointValidation.accepted();
        }
        if (decision.kind() == WorkspaceDriftKind.MANUAL_RESOLUTION_REQUIRED
                && decision.automaticRestoreAllowed()
                && isolatedRestore.supports(state)) {
            return CapabilityCheckpointValidation.accepted();
        }
        return CapabilityCheckpointValidation.rejected(decision.kind().name(), decision.reasonCode());
    }

    @Override
    public void restore(CapabilityCheckpointRef reference, CapabilityCheckpointRestoreContext context) {
        WorkspaceCheckpointState state = states.find(reference.payloadRef()).orElseThrow();
        var snapshot = snapshotStore.find(state.snapshotId()).orElseThrow();
        WorkspaceCheckpointAccess access = resolver.currentAccess(context, state);
        if (access.workspace() == null || access.binding() == null || !access.authorized()) {
            throw new SecurityException("workspace access changed after checkpoint validation");
        }
        var decision = validator.validate(new WorkspaceSnapshotValidator.ValidationRequest(
                snapshot,
                access.workspace(),
                access.binding(),
                access.authorized(),
                state.providerId(),
                state.providerVersion(),
                evidence.inspect(access.workspace(), state.strategy())));
        if (decision.kind() == WorkspaceDriftKind.MANUAL_RESOLUTION_REQUIRED
                && decision.automaticRestoreAllowed()
                && isolatedRestore.supports(state)) {
            isolatedRestore.restoreToNewControlledWorkspace(context, state, snapshot);
        }
        // NO_DRIFT and SAFE_METADATA_DRIFT restore only the reference. DIRECT workspaces are never mutated here.
    }

    private static String digest(WorkspaceCheckpointState state) {
        try {
            String canonical = state.snapshotId().value()
                    + "|"
                    + state.workspaceId().value()
                    + "|"
                    + state.bindingRef()
                    + "|"
                    + state.strategy()
                    + "|"
                    + state.providerId()
                    + "|"
                    + state.providerVersion()
                    + "|"
                    + state.snapshotDigest();
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
