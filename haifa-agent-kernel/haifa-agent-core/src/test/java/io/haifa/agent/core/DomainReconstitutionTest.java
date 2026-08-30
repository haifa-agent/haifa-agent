package io.haifa.agent.core;

import static io.haifa.agent.core.CoreTestFixtures.NOW;
import static io.haifa.agent.core.CoreTestFixtures.error;
import static io.haifa.agent.core.CoreTestFixtures.runSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.persistence.DomainReconstitutionException;
import io.haifa.agent.core.persistence.DomainReconstitutionFailure;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.plan.AgentPlanId;
import io.haifa.agent.core.plan.AgentPlanPersistenceSnapshot;
import io.haifa.agent.core.plan.TodoItem;
import io.haifa.agent.core.plan.TodoItemId;
import io.haifa.agent.core.plan.TodoPriority;
import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentInvocationMode;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunPersistenceSnapshot;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.RunTerminationReason;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionPersistenceSnapshot;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.step.AgentStepError;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.step.AgentStepResult;
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DomainReconstitutionTest {

    @Test
    void runRoundTripsEveryLegalStatusAndOptionalTerminalValue() {
        AgentRun run = AgentRun.createRoot(new AgentRunId("run-lifecycle"), runSpec(2), NOW);
        assertRunRoundTrip(run);
        run.markQueued(NOW.plusSeconds(1));
        assertRunRoundTrip(run);
        run.start(NOW.plusSeconds(2));
        assertRunRoundTrip(run);
        run.requestSuspend(NOW.plusSeconds(3));
        assertRunRoundTrip(run);
        run.suspend(NOW.plusSeconds(4));
        assertRunRoundTrip(run);
        run.resume(NOW.plusSeconds(5));
        assertRunRoundTrip(run);
        run.waitForInteraction(new InteractionRequestRef("request", "input"), NOW.plusSeconds(6));
        assertRunRoundTrip(run);
        run.resume(NOW.plusSeconds(7));
        run.waitForApproval(new InteractionRequestRef("approval", "tool"), NOW.plusSeconds(8));
        assertRunRoundTrip(run);
        run.resume(NOW.plusSeconds(9));
        run.beginCompleting(NOW.plusSeconds(10));
        assertRunRoundTrip(run);
        run.complete(successResult(), NOW.plusSeconds(11));
        assertRunRoundTrip(run);

        AgentRun failed = AgentRun.createRoot(new AgentRunId("run-failed"), runSpec(2), NOW);
        failed.fail(error(NOW.plusSeconds(1)), NOW.plusSeconds(1));
        assertRunRoundTrip(failed);

        AgentRun cancelled = AgentRun.createRoot(new AgentRunId("run-cancelled"), runSpec(2), NOW);
        cancelled.cancel(new RunTerminationReason("USER", "cancelled"), NOW.plusSeconds(1));
        assertRunRoundTrip(cancelled);

        AgentRun timedOut = AgentRun.createRoot(new AgentRunId("run-timeout"), runSpec(2), NOW);
        timedOut.timeout(new RunTerminationReason("TIME", "deadline"), NOW.plusSeconds(1));
        assertRunRoundTrip(timedOut);

        AgentRun root = AgentRun.createRoot(new AgentRunId("root"), runSpec(2), NOW);
        AgentRun child = AgentRun.createChild(
                new AgentRunId("child"), root, AgentInvocationMode.AGENT_AS_TOOL, runSpec(2), NOW.plusSeconds(1));
        assertRunRoundTrip(child);
    }

    @Test
    void sessionRoundTripsEveryLegalStatusAndOptionalProject() {
        AgentSession active = session("active", null, SessionScope.EPHEMERAL);
        assertSessionRoundTrip(active);

        AgentSession archived = session("archived", new ProjectRef("project"), SessionScope.PROJECT);
        archived.archive(NOW.plusSeconds(1));
        assertSessionRoundTrip(archived);

        AgentSession closed = session("closed", new ProjectRef("project"), SessionScope.PROJECT);
        closed.archive(NOW.plusSeconds(1));
        closed.close(NOW.plusSeconds(2));
        assertSessionRoundTrip(closed);

        AgentSession deleted = session("deleted", null, SessionScope.USER);
        deleted.delete(NOW.plusSeconds(1));
        assertSessionRoundTrip(deleted);
    }

    @Test
    void stepRoundTripsEveryLegalStatusAndOptionalFields() {
        assertStepRoundTrip(step("pending"));

        AgentStep running = step("running");
        running.start(NOW.plusSeconds(1));
        assertStepRoundTrip(running);

        AgentStep waiting = step("waiting");
        waiting.start(NOW.plusSeconds(1));
        waiting.waitForExternalInput();
        assertStepRoundTrip(waiting);

        AgentStep completed = step("completed");
        completed.start(NOW.plusSeconds(1));
        completed.complete(new AgentStepResult("done", Map.of("ok", true), List.of()), NOW.plusSeconds(2));
        assertStepRoundTrip(completed);

        AgentStep failed = step("failed");
        failed.start(NOW.plusSeconds(1));
        failed.fail(new AgentStepError(error(NOW.plusSeconds(2))), NOW.plusSeconds(2));
        assertStepRoundTrip(failed);

        AgentStep cancelled = step("cancelled");
        cancelled.cancel(NOW.plusSeconds(1));
        assertStepRoundTrip(cancelled);

        AgentStep skipped = step("skipped");
        skipped.skip(NOW.plusSeconds(1));
        assertStepRoundTrip(skipped);
    }

    @Test
    void toolCallRoundTripsEveryLegalStatusAndOptionalOutcome() {
        assertToolRoundTrip(toolCall("requested"));

        ToolCall validating = toolCall("validating");
        validating.beginValidation();
        assertToolRoundTrip(validating);

        ToolCall policy = throughPolicy("policy");
        assertToolRoundTrip(policy);

        ToolCall waiting = throughPolicy("waiting");
        waiting.waitForApproval();
        assertToolRoundTrip(waiting);

        ToolCall approved = throughPolicy("approved");
        approved.waitForApproval();
        approved.approve();
        assertToolRoundTrip(approved);

        ToolCall running = throughPolicy("running");
        running.start(NOW.plusSeconds(1));
        assertToolRoundTrip(running);

        ToolCall completed = throughPolicy("completed");
        completed.start(NOW.plusSeconds(1));
        completed.complete(
                new ToolResult(true, "done", Map.of("ok", true), List.of(), List.of(), false), NOW.plusSeconds(2));
        assertToolRoundTrip(completed);

        ToolCall failed = throughPolicy("failed");
        failed.start(NOW.plusSeconds(1));
        failed.fail(
                new ToolExecutionError(error(NOW.plusSeconds(2))),
                new ToolResult(
                        false,
                        "bounded failure",
                        Map.of("failureCategory", "COMMAND_FAILED", "output", "bounded output"),
                        List.of(),
                        List.of(),
                        false),
                NOW.plusSeconds(2));
        assertToolRoundTrip(failed);

        ToolCall denied = throughPolicy("denied");
        denied.deny(NOW.plusSeconds(1));
        assertToolRoundTrip(denied);

        ToolCall cancelled = toolCall("cancelled");
        cancelled.cancel(NOW.plusSeconds(1));
        assertToolRoundTrip(cancelled);

        ToolCall timedOut = throughPolicy("timeout");
        timedOut.start(NOW.plusSeconds(1));
        timedOut.timeout(NOW.plusSeconds(2));
        assertToolRoundTrip(timedOut);
    }

    @Test
    void planAndTodoItemsRoundTripEveryLegalItemStatus() {
        List<TodoItem> items = List.of(
                todo("pending"),
                startedTodo("in-progress"),
                blockedTodo("blocked"),
                completedTodo("completed"),
                cancelledTodo("cancelled"),
                skippedTodo("skipped"));
        AgentPlan plan = new AgentPlan(new AgentPlanId("plan"), new AgentRunId("run"), "objective", items, NOW);
        plan.revise("revised objective", items, NOW.plusSeconds(1));

        AgentPlanPersistenceSnapshot snapshot = plan.persistenceSnapshot();

        assertThat(AgentPlan.reconstitute(snapshot).persistenceSnapshot()).isEqualTo(snapshot);
    }

    @Test
    void rejectsUnknownVersionsEnumsInvalidStateTimeAndVersion() {
        AgentPlanPersistenceSnapshot plan = new AgentPlan(
                        new AgentPlanId("plan"), new AgentRunId("run"), "objective", List.of(), NOW)
                .persistenceSnapshot();
        AgentPlanPersistenceSnapshot unknownVersion = new AgentPlanPersistenceSnapshot(
                "999", plan.id(), plan.runId(), plan.createdAt(), plan.objective(), plan.items(), 1, plan.updatedAt());
        assertFailure(
                () -> AgentPlan.reconstitute(unknownVersion), DomainReconstitutionFailure.UNSUPPORTED_SCHEMA_VERSION);

        AgentSessionPersistenceSnapshot session =
                session("invalid-enum", null, SessionScope.USER).persistenceSnapshot();
        AgentSessionPersistenceSnapshot unknownStatus = new AgentSessionPersistenceSnapshot(
                session.schemaVersion(),
                session.id(),
                session.tenant(),
                session.owner(),
                session.project(),
                session.scope(),
                session.createdAt(),
                "NOT_A_STATUS",
                session.updatedAt(),
                session.closedAt(),
                session.version(),
                session.metadata());
        assertFailure(() -> AgentSession.reconstitute(unknownStatus), DomainReconstitutionFailure.UNKNOWN_ENUM);

        AgentSessionPersistenceSnapshot backwards = new AgentSessionPersistenceSnapshot(
                session.schemaVersion(),
                session.id(),
                session.tenant(),
                session.owner(),
                session.project(),
                session.scope(),
                session.createdAt(),
                session.status(),
                session.createdAt().minusSeconds(1),
                session.closedAt(),
                session.version(),
                session.metadata());
        assertFailure(() -> AgentSession.reconstitute(backwards), DomainReconstitutionFailure.INVALID_HISTORY);

        AgentRunPersistenceSnapshot run = AgentRun.createRoot(new AgentRunId("invalid-run"), runSpec(2), NOW)
                .persistenceSnapshot();
        AgentRunPersistenceSnapshot inconsistent = copyRun(run, "COMPLETED", successResult(), 3);
        assertFailure(() -> AgentRun.reconstitute(inconsistent), DomainReconstitutionFailure.INVALID_HISTORY);
        AgentRunPersistenceSnapshot negativeVersion = copyRun(run, run.status(), run.result(), -1);
        assertFailure(() -> AgentRun.reconstitute(negativeVersion), DomainReconstitutionFailure.INVALID_HISTORY);
    }

    private static AgentRunPersistenceSnapshot copyRun(
            AgentRunPersistenceSnapshot source, String status, AgentRunResult result, long version) {
        return new AgentRunPersistenceSnapshot(
                source.schemaVersion(),
                source.id(),
                source.rootRunId(),
                source.parentRunId(),
                source.sessionId(),
                source.project(),
                source.tenant(),
                source.principal(),
                source.agentDefinitionId(),
                source.agentDefinitionVersion(),
                source.productProfileId(),
                source.productProfileVersion(),
                source.runType(),
                source.invocationMode(),
                source.depth(),
                source.objective(),
                source.budget(),
                source.limits(),
                source.configurationSnapshot(),
                source.createdAt(),
                status,
                source.usage(),
                result,
                source.error(),
                source.waitingFor(),
                source.terminationReason(),
                source.accumulatedHumanWaitMillis(),
                source.humanWaitStartedAt(),
                source.queuedAt(),
                source.startedAt(),
                source.suspendedAt(),
                source.resumedAt(),
                source.completedAt(),
                source.updatedAt(),
                version);
    }

    private static void assertRunRoundTrip(AgentRun run) {
        AgentRunPersistenceSnapshot snapshot = run.persistenceSnapshot();
        assertThat(AgentRun.reconstitute(snapshot).persistenceSnapshot()).isEqualTo(snapshot);
    }

    private static AgentRunResult successResult() {
        return new AgentRunResult(
                AgentRunOutcome.SUCCESS, "done", "result", "1", Map.of("ok", true), List.of(), List.of("warning"));
    }

    private static AgentSession session(String id, ProjectRef project, SessionScope scope) {
        return AgentSession.open(
                new AgentSessionId(id),
                new TenantRef("tenant"),
                new PrincipalRef("owner", "user"),
                project,
                scope,
                NOW,
                Map.of("nested", List.of("value")));
    }

    private static void assertSessionRoundTrip(AgentSession session) {
        AgentSessionPersistenceSnapshot snapshot = session.persistenceSnapshot();
        assertThat(AgentSession.reconstitute(snapshot).persistenceSnapshot()).isEqualTo(snapshot);
    }

    private static AgentStep step(String id) {
        return new AgentStep(
                new AgentStepId(id), new AgentRunId("run"), null, "branch", AgentStepType.MODEL_CALL, 1, NOW);
    }

    private static void assertStepRoundTrip(AgentStep step) {
        var snapshot = step.persistenceSnapshot();
        assertThat(AgentStep.reconstitute(snapshot).persistenceSnapshot()).isEqualTo(snapshot);
    }

    private static ToolCall toolCall(String id) {
        return new ToolCall(
                new ToolCallId(id),
                new AgentRunId("run"),
                new AgentStepId("step"),
                new ProviderToolCallCorrelationId("provider-" + id),
                new RuntimeIdempotencyKey("key-" + id),
                "file.read",
                "1.0.0",
                new ToolArguments("file-read", "1", Map.of("path", "README.md")),
                NOW);
    }

    private static ToolCall throughPolicy(String id) {
        ToolCall call = toolCall(id);
        call.beginValidation();
        call.beginPolicyCheck();
        return call;
    }

    private static void assertToolRoundTrip(ToolCall call) {
        var snapshot = call.persistenceSnapshot();
        assertThat(ToolCall.reconstitute(snapshot).persistenceSnapshot()).isEqualTo(snapshot);
    }

    private static TodoItem todo(String id) {
        return new TodoItem(new TodoItemId(id), id, "description", TodoPriority.NORMAL, List.of());
    }

    private static TodoItem startedTodo(String id) {
        TodoItem item = todo(id);
        item.start(Set.of(), NOW);
        return item;
    }

    private static TodoItem blockedTodo(String id) {
        TodoItem item = startedTodo(id);
        item.block();
        return item;
    }

    private static TodoItem completedTodo(String id) {
        TodoItem item = startedTodo(id);
        item.complete("done", NOW.plusSeconds(1));
        return item;
    }

    private static TodoItem cancelledTodo(String id) {
        TodoItem item = todo(id);
        item.cancel(NOW.plusSeconds(1));
        return item;
    }

    private static TodoItem skippedTodo(String id) {
        TodoItem item = todo(id);
        item.skip(NOW.plusSeconds(1));
        return item;
    }

    private static void assertFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, DomainReconstitutionFailure failure) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(
                        DomainReconstitutionException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(failure));
    }
}
