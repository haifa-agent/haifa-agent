package io.haifa.agent.application.project.product.coding.client;

import io.haifa.agent.application.project.tool.ProjectToolCatalog;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionInputType;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RuntimeContractException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Process-local consent owned by one project client; Runtime still validates every exact approval. */
final class CodingSessionApprovals {
    /**
     * Session consent follows the product's built-in Tool catalog instead of a second hand-written list, so a Tool
     * the project registers later is covered without editing this class. Attaching another workspace widens the
     * trusted root and keeps a per-call approval; Web and Skill Tools are registered by their own contributions.
     */
    private static final Set<String> ORDINARY_TOOLS = ordinaryToolNames();

    private final AgentRuntime runtime;
    private final ConcurrentHashMap<AgentSessionId, Consent> consents = new ConcurrentHashMap<>();

    CodingSessionApprovals(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    static Set<String> ordinaryToolNames() {
        Set<String> names = new LinkedHashSet<>(ProjectToolCatalog.BUILT_IN_TOOL_NAMES);
        names.remove(ProjectToolCatalog.WORKSPACE_ATTACH);
        names.addAll(Set.of("web_search", "web_fetch", "skill_load", "skill_resource_read"));
        return Set.copyOf(names);
    }

    Optional<CodingApprovalPrompt> prepare(
            AgentRunId runId, Function<InteractionView, InteractionResponseSubmission> submit) {
        return runtime.pendingInteraction(runId).flatMap(interaction -> {
            boolean ordinary = ordinary(interaction);
            Consent consent = consents.get(interaction.sessionId());
            if (ordinary && consent != null) {
                synchronized (consent) {
                    if (consents.get(interaction.sessionId()) == consent) {
                        if (!consent.active) {
                            // Replaying the exact idempotent submission reads its latest application state;
                            // the Runtime's duplicate path never schedules execution a second time.
                            InteractionResponseReceipt receipt;
                            try {
                                receipt = runtime.respond(consent.submission);
                            } catch (RuntimeException staleConsent) {
                                consents.remove(interaction.sessionId(), consent);
                                return Optional.of(new CodingApprovalPrompt(interaction, true));
                            }
                            consent.active = applied(receipt, consent.submission);
                        }
                        if (consent.active) {
                            try {
                                if (consent.automatic == null
                                        || !consent.automatic.requestId().equals(interaction.requestId())) {
                                    consent.automatic = submit.apply(interaction);
                                }
                                InteractionResponseReceipt receipt = runtime.respond(consent.automatic);
                                if (!matches(receipt, consent.automatic)
                                        || (receipt.interactionState() != InteractionState.APPLIED
                                                && receipt.interactionState() != InteractionState.RESPONDED)) {
                                    consents.remove(interaction.sessionId(), consent);
                                    return Optional.of(new CodingApprovalPrompt(interaction, true));
                                }
                                return Optional.empty();
                            } catch (RuntimeException automaticFailure) {
                                consents.remove(interaction.sessionId(), consent);
                                return Optional.of(new CodingApprovalPrompt(interaction, true));
                            }
                        }
                    }
                }
            }
            return Optional.of(new CodingApprovalPrompt(interaction, ordinary,
                    ordinary ? Optional.empty() : Optional.of(
                            "This request needs approval each time: its full target must be reviewed, or it changes permissions.")));
        });
    }

    InteractionResponseReceipt approve(InteractionView pending, InteractionResponseSubmission submission) {
        if (!ordinary(pending) || !submission.action().equals(InteractionAction.APPROVE)) {
            throw new IllegalArgumentException("Session approval is unavailable for this interaction");
        }
        Consent consent = new Consent(submission);
        synchronized (consent) {
            consents.put(pending.sessionId(), consent);
            try {
                InteractionResponseReceipt receipt = runtime.respond(submission);
                consent.active = applied(receipt, submission);
                return receipt;
            } catch (RuntimeException failure) {
                consents.remove(pending.sessionId(), consent);
                throw failure;
            }
        }
    }

    void reset(AgentSessionId sessionId) {
        Consent consent = consents.get(sessionId);
        if (consent != null)
            synchronized (consent) {
                consents.remove(sessionId, consent);
            }
    }

    private boolean ordinary(InteractionView interaction) {
        if (!interaction.kind().equals(InteractionKind.APPROVAL)
                || interaction.state() != InteractionState.PENDING
                || !interaction.inputContract().type().equals(InteractionInputType.NONE)
                || !interaction.target().type().equals("tool")
                || !interaction.allowedActions().contains(InteractionAction.APPROVE)) return false;
        return runtime.toolCalls(interaction.runId()).stream()
                .filter(call -> call.id().value().equals(interaction.target().reference()))
                .filter(call -> call.runId().equals(interaction.runId())
                        && call.status() == ToolCallStatus.WAITING_APPROVAL
                        && completeTarget(call))
                .filter(call -> ORDINARY_TOOLS.contains(call.toolName()))
                .anyMatch(call -> !call.toolName().equals(ProjectToolCatalog.EXECUTION_RUN)
                        || !"PERMISSION_REQUEST"
                                .equals(call.arguments().values().get("operationFamily")));
    }

    private static boolean completeTarget(io.haifa.agent.runtime.api.ToolCallView call) {
        if (!call.arguments().truncated()) return true;
        // Content is data, not a target. Only the trusted projector can certify the other fields.
        // Patch text embeds its paths, so a truncated patch remains an explicit approval.
        if (!Set.of("file_create", "file_write", "file_diff").contains(call.toolName())) return false;
        var arguments = call.arguments();
        return arguments.values().get("path") instanceof String path && !path.isBlank()
                && arguments.completeFields().contains("path")
                && arguments.values().keySet().stream()
                        .filter(key -> !key.equals("content"))
                        .allMatch(arguments.completeFields()::contains);
    }

    private static boolean applied(InteractionResponseReceipt receipt, InteractionResponseSubmission submission) {
        return receipt.interactionState() == InteractionState.APPLIED
                && matches(receipt, submission);
    }

    private static boolean matches(InteractionResponseReceipt receipt, InteractionResponseSubmission submission) {
        return receipt.requestId().equals(submission.requestId())
                && receipt.runId().equals(submission.runId())
                && receipt.responseId().equals(submission.responseId());
    }

    private static final class Consent {
        final InteractionResponseSubmission submission;
        InteractionResponseSubmission automatic;
        boolean active;

        Consent(InteractionResponseSubmission submission) {
            this.submission = submission;
        }
    }
}
