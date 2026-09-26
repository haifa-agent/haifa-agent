package io.haifa.agent.sdk.api;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentPlanView;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.AgentRunHandle;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.ChildRunView;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventSubscription;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RunOutputSubscription;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.sdk.diagnostics.PromptDiagnostics;
import io.haifa.agent.sdk.internal.ProcessLocalPromptDiagnostics;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Stable run query and control facade; Runtime construction remains hidden. */
public final class AgentRuns {
    private final io.haifa.agent.runtime.api.AgentRuntime runtime;
    private final ProcessLocalPromptDiagnostics promptDiagnostics;
    private final IdentifierGenerator ids;
    private final TimeProvider time;

    AgentRuns(
            io.haifa.agent.runtime.api.AgentRuntime runtime,
            ProcessLocalPromptDiagnostics promptDiagnostics,
            IdentifierGenerator ids,
            TimeProvider time) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.promptDiagnostics = Objects.requireNonNull(promptDiagnostics, "promptDiagnostics must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    /** Starts one standard Run. Products remain responsible for trusted Session creation and request construction. */
    public AgentRunSnapshot start(AgentRunRequest request) {
        return runtime.start(Objects.requireNonNull(request, "request must not be null"));
    }

    public Optional<AgentRunSnapshot> find(AgentRunId runId) {
        return runtime.find(Objects.requireNonNull(runId, "runId must not be null"));
    }

    /** Reclaims a durable executing Run after its previous physical Runtime disappeared. */
    public AgentRunSnapshot recover(AgentRunId runId) {
        return runtime.recover(Objects.requireNonNull(runId, "runId must not be null"));
    }

    public Optional<AgentRunViewSnapshot> view(AgentRunId runId) {
        return runtime.view(Objects.requireNonNull(runId, "runId must not be null"));
    }

    /**
     * Returns redacted Prompt composition evidence when this process built Context for an
     * authorized Run. This method does not promise availability across process restarts.
     */
    public PromptDiagnostics promptDiagnostics(AgentRunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        if (runtime.find(runId).isEmpty()) return PromptDiagnostics.unavailable(runId);
        return promptDiagnostics.find(runId);
    }

    public Optional<AgentPlanView> plan(AgentRunId runId) {
        return runtime.plan(Objects.requireNonNull(runId, "runId must not be null"));
    }

    /**
     * Lists the child runs a parent run delegated through the {@code task} Tool, oldest first. Child sessions are
     * never Conversations; a child's own events are read with {@link #events} using its run ID.
     */
    public List<ChildRunView> children(AgentRunId parentRunId) {
        return runtime.children(Objects.requireNonNull(parentRunId, "parentRunId must not be null"));
    }

    public AgentRunHandle handle(AgentRunId runId) {
        return runtime.handle(Objects.requireNonNull(runId, "runId must not be null"));
    }

    public Optional<InteractionView> pendingInteraction(AgentRunId runId) {
        return runtime.pendingInteraction(Objects.requireNonNull(runId, "runId must not be null"));
    }

    public InteractionResponseReceipt respond(InteractionResponseSubmission response) {
        return runtime.respond(Objects.requireNonNull(response, "response must not be null"));
    }

    /**
     * Submits steer text to one active Run of the current caller.
     *
     * <p>The input is applied at the Run's next {@code BEFORE_ITERATION}: after a running Tool returns, never inside
     * Tool execution or model request construction. If the model produces its final answer while accepted input is
     * still pending, completion is deferred so the model sees the input first; an input the Run can no longer apply
     * because it stopped is settled as {@link RunInputStatus#REJECTED} with a {@code run.input.rejected} event.
     * A Run that is already completing or terminal returns {@code REJECTED} with
     * {@link RunInputResult#RUN_NOT_ACCEPTING_INPUT}. Steer never cancels; cancellation stays on {@link #handle}.
     */
    public RunInputResult submitInput(RunInputCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        RunInputId inputId = new RunInputId(ids.nextValue());
        RunInputSubmission submission = new RunInputSubmission(
                inputId,
                command.runId(),
                OptionalLong.empty(),
                List.of(new TextPart(command.message(), "plain")),
                command.idempotencyKey(),
                time.now());
        try {
            return RunInputResult.from(runtime.submitInput(submission));
        } catch (RuntimeContractException refused) {
            if (refused.code() != RuntimeApiErrorCode.RUN_STATE_CONFLICT) throw refused;
            return RunInputResult.refused(inputId.value(), command.runId());
        }
    }

    public RunEventPage events(AgentRunId runId, RunEventCursor after, int limit) {
        return runtime.events(
                Objects.requireNonNull(runId, "runId must not be null"),
                Objects.requireNonNull(after, "after must not be null"),
                limit);
    }

    public RunEventSubscription subscribe(AgentRunId runId, RunEventCursor after, AgentRunEventListener listener) {
        return runtime.subscribe(
                Objects.requireNonNull(runId, "runId must not be null"),
                Objects.requireNonNull(after, "after must not be null"),
                Objects.requireNonNull(listener, "listener must not be null"));
    }

    /**
     * Reads the bounded transient output buffer for an active Run. It is not durable replay.
     */
    public List<AgentRunOutputEvent> outputEvents(AgentRunId runId, RunOutputCursor after, int limit) {
        return runtime.outputEvents(
                Objects.requireNonNull(runId, "runId must not be null"),
                Objects.requireNonNull(after, "after must not be null"),
                limit);
    }

    /** Replays buffered transient output and then tails one active Run until closed. */
    public RunOutputSubscription subscribeOutput(
            AgentRunId runId, RunOutputCursor after, AgentRunOutputListener listener) {
        return runtime.subscribeOutput(
                Objects.requireNonNull(runId, "runId must not be null"),
                Objects.requireNonNull(after, "after must not be null"),
                Objects.requireNonNull(listener, "listener must not be null"));
    }

    public AgentRunSnapshot await(AgentRunId runId) throws InterruptedException {
        return handle(runId).awaitCompletion();
    }

    public Optional<AgentRunSnapshot> await(AgentRunId runId, Duration timeout) throws InterruptedException {
        return handle(runId).awaitCompletion(Objects.requireNonNull(timeout, "timeout must not be null"));
    }
}
