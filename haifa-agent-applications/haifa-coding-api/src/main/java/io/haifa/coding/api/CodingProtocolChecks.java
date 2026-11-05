package io.haifa.coding.api;

import io.haifa.coding.api.CodingProtocol.*;
import java.util.HashSet;
import java.util.Set;

/** Cross-field product invariants, shared by the Java producer and atomic consumer. No execution decisions. */
public final class CodingProtocolChecks {
    private CodingProtocolChecks() {}

    public static void snapshot(Snapshot value) {
        if (!value.scope().sessionId().equals(value.session().id())) fail("SESSION_SCOPE_MISMATCH");
        Set<String> ids = new HashSet<>();
        for (Entry entry : value.entries()) {
            if (!ids.add(entry.id())) fail("DUPLICATE_ENTRY_ID");
            if (entry.kind().equals("interaction") != (entry.interaction() != null)) fail("INTERACTION_SHAPE_INVALID");
            if (entry.interaction() != null) {
                if (entry.runId() == null) fail("INTERACTION_RUN_REQUIRED");
                for (Action action : entry.interaction().actions()) {
                    Target target = action.target();
                    if (!value.scope().sessionId().equals(target.sessionId())
                            || !entry.runId().equals(target.runId())
                            || !entry.interaction().id().equals(target.interactionId())
                            || target.targetRevision() == null
                            || target.targetRevision() != entry.interaction().revision())
                        fail("INTERACTION_TARGET_MISMATCH");
                    action(value, action);
                }
            }
        }
        for (Entry entry : value.entries()) {
            if (entry.kind().equals("tool") && (entry.toolCallId() == null || entry.runId() == null))
                fail("TOOL_IDENTITY_REQUIRED");
            if (entry.interactionId() != null) {
                var linked = value.entries().stream()
                        .filter(e ->
                                e.interaction() != null && e.interaction().id().equals(entry.interactionId()))
                        .findFirst();
                if (!entry.kind().equals("tool")
                        || linked.isEmpty()
                        || !java.util.Objects.equals(
                                entry.runId(), linked.orElseThrow().runId())) fail("INTERACTION_REFERENCE_INVALID");
            }
        }
        for (Action action : value.actions()) action(value, action);
        if (!value.history().hasEarlier() && value.history().beforeSequence() != null) fail("HISTORY_CURSOR_INVALID");
        if (value.consent().state().equals("ASK") != (value.consent().expiresAtEpochMillis() == null))
            fail("CONSENT_LEASE_INVALID");
    }

    private static void action(Snapshot snapshot, Action action) {
        if (!snapshot.scope().sessionId().equals(action.target().sessionId())) fail("ACTION_SCOPE_MISMATCH");
        if (action.available() && action.reason() != null) fail("ACTION_AVAILABILITY_INVALID");
        if (!action.available() && (action.reason() == null || action.reason().isEmpty()))
            fail("ACTION_REASON_REQUIRED");
        if (action.kind().equals("cancel") && action.target().runId() == null) fail("EXACT_RUN_REQUIRED");
        if (snapshot.hydration().equals("refreshing") && action.available()) fail("REFRESHING_ACTION_UNAVAILABLE");
    }

    public static void command(Command command) {
        if (command.clientRequestId().isBlank()) fail("REQUEST_ID_REQUIRED");
        var target = command.target();
        if (!Set.of("create", "resourcesReload", "workspaceRevoke").contains(command.operation())
                && blank(target.sessionId())) fail("SESSION_REQUIRED");
        if (Set.of("cancel", "steer", "followUp", "respond", "allowForSession", "prepareApproval")
                        .contains(command.operation())
                && blank(target.runId())) fail("EXACT_RUN_REQUIRED");
        if (Set.of("respond", "allowForSession", "prepareApproval").contains(command.operation())
                && (blank(target.interactionId()) || target.targetRevision() == null))
            fail("INTERACTION_TARGET_REQUIRED");
        if (Set.of("rename", "archive", "delete", "restore", "selectModel").contains(command.operation())
                && target.targetRevision() == null) fail("ENTITY_REVISION_REQUIRED");
        if (Set.of("create", "submit", "steer", "followUp", "respond", "cancel", "selectModel", "allowForSession")
                        .contains(command.operation())
                && blank(command.idempotencyKey())) fail("IDEMPOTENCY_KEY_REQUIRED");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void fail(String code) {
        throw new IllegalArgumentException(code);
    }
}
