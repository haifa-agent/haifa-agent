package io.haifa.agent.application.project.product.coding.client;

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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Process-local consent owned by one project client; Runtime still validates every exact approval. */
final class CodingSessionApprovals {
    private static final Set<String> ORDINARY_TOOLS = Set.of(
            "file_list",
            "file_stat",
            "file_read",
            "file_search",
            "file_create",
            "file_write",
            "file_delete",
            "file_move",
            "file_diff",
            "file_patch",
            "execution_run",
            "web_search",
            "web_fetch",
            "skill_load",
            "skill_resource_read");
    private final AgentRuntime runtime;
    private final ConcurrentHashMap<AgentSessionId, Consent> consents = new ConcurrentHashMap<>();

    CodingSessionApprovals(AgentRuntime runtime) {
        this.runtime = runtime;
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
                            } catch (RuntimeContractException staleConsent) {
                                consents.remove(interaction.sessionId(), consent);
                                return Optional.of(new CodingApprovalPrompt(interaction, true));
                            }
                            consent.active = applied(receipt, consent.submission);
                        }
                        if (consent.active) {
                            if (consent.automatic == null
                                    || !consent.automatic.requestId().equals(interaction.requestId())) {
                                consent.automatic = submit.apply(interaction);
                            }
                            runtime.respond(consent.automatic);
                            return Optional.empty();
                        }
                    }
                }
            }
            return Optional.of(new CodingApprovalPrompt(interaction, ordinary));
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
                        && !call.arguments().truncated())
                .filter(call -> ORDINARY_TOOLS.contains(call.toolName()))
                .anyMatch(call -> !call.toolName().equals("execution_run")
                        || !"PERMISSION_REQUEST"
                                .equals(call.arguments().values().get("operationFamily")));
    }

    private static boolean applied(InteractionResponseReceipt receipt, InteractionResponseSubmission submission) {
        return receipt.interactionState() == InteractionState.APPLIED
                && receipt.requestId().equals(submission.requestId())
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
