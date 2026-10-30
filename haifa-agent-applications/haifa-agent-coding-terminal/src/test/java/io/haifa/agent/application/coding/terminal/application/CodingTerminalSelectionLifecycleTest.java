package io.haifa.agent.application.coding.terminal.application;

import static io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingModelSelection;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.client.CodingApprovalPrompt;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.execution.api.ToolOutputPreview;
import io.haifa.agent.execution.api.ToolOutputPreviewPublisher;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodingTerminalSelectionLifecycleTest {
    @Test
    void staleSelectionResultsNeverReplaceTheNewestDisplayedSession() {
        FakeClient client = new FakeClient(sessionView("session-b", "B"));
        AgentSessionId a = new AgentSessionId("session-a");
        CodingSessionView oldA = sessionView("session-a", "A old");
        CodingSessionView freshA = sessionView("session-a", "A fresh");
        client.viewsBySession.put(a, freshA);
        ArrayDeque<Runnable> effects = new ArrayDeque<>();
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                effects::addLast);

        // Three startup loads are in flight: A, B and A again.
        controller.start(CodingTerminalStartup.autoLast());
        controller.start(CodingTerminalStartup.autoLast());
        controller.start(CodingTerminalStartup.autoLast());
        assertThat(effects).hasSize(3);

        // The newest A load completes first and becomes the displayed selection.
        client.listQueue.add(sessionView("session-a", "A fresh").summary());
        effects.removeLast().run();
        controller.drainEvents();
        assertThat(controller.state().session().map(value -> value.summary().displayName()))
                .contains("A fresh");

        // The old A and B loads complete afterwards. The old A session id matches the current
        // selection, but its generation does not, so it must not overwrite the fresh view.
        client.listQueue.add(sessionView("session-a", "A old").summary());
        client.viewsBySession.put(a, oldA);
        effects.removeFirst().run();
        controller.drainEvents();
        assertThat(controller.state().session().map(value -> value.summary().displayName()))
                .contains("A fresh");

        client.listQueue.add(sessionView("session-b", "B").summary());
        effects.removeFirst().run();
        controller.drainEvents();
        assertThat(controller.state().session().map(value -> value.summary().displayName()))
                .contains("A fresh");
    }

    @Test
    void failedTransitionKeepsTheDisplayedStreamAndAllowsRetry() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);
        AgentSessionId unavailable = new AgentSessionId("session-b");
        client.openFailures.put(unavailable, new ProjectProductException("SESSION_NOT_FOUND", "Session unavailable"));
        client.summaries = List.of(sessionView("session-b", "B").summary());

        // Select B through the bounded /resume effect; its load fails.
        controller.accept(input(TerminalInput.Kind.EXECUTE_COMMAND, "/resume"));
        assertThat(controller.state().selector()).isPresent();
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(controller.state().session().map(value -> value.summary().sessionId()))
                .contains(SESSION_ID);
        assertThat(controller.state().recoverableError()).contains("SESSION_NOT_FOUND");

        client.emit(event(1, new RunEventPayloads.AssistantTextDelta("generation-1", "still streaming")));
        controller.drainEvents();
        assertThat(controller.state().transcript()).anyMatch(item -> item.body().contains("still streaming"));

        controller.open(SESSION_ID);
        assertThat(controller.state().session().map(value -> value.summary().sessionId()))
                .contains(SESSION_ID);
    }

    @Test
    void eventEmittedSynchronouslyDuringSubscriptionInstallationIsNotLost() {
        FakeClient client = new FakeClient(activeView());
        client.synchronouslyReplayed.add(
                event(1, new RunEventPayloads.AssistantTextDelta("generation-1", "replayed while subscribing")));
        var controller = controller(client);

        controller.open(SESSION_ID);
        controller.drainEvents();

        assertThat(controller.state().transcript())
                .anyMatch(item -> item.body().contains("replayed while subscribing"));
    }

    @Test
    void cancelTargetsTheCapturedActiveRunWithoutReloadingTheSession() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));

        assertThat(client.cancelledRuns).containsExactly(new AgentRunId("run-1"));
        assertThat(client.cancelledSessions).containsExactly(SESSION_ID);
        assertThat(client.sessionLevelCancelCalls).isZero();
        assertThat(client.reconcileCalls).isZero();
    }

    @Test
    void controlCancelIsNotStarvedByBlockedInteractiveWork() {
        FakeClient client = new FakeClient(activeView());
        ArrayDeque<Runnable> control = new ArrayDeque<>();
        ArrayDeque<Runnable> interactive = new ArrayDeque<>();
        ArrayDeque<Runnable> maintenance = new ArrayDeque<>();
        TerminalUiReducer reducer = new TerminalUiReducer();
        TerminalUiState active = reducer.reduce(
                TerminalUiState.initial(120, 40), new TerminalUiAction.SessionLoaded(activeView(), List.of()));
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                reducer,
                active,
                control::add,
                interactive::add,
                maintenance::add);

        controller.accept(input(TerminalInput.Kind.EXECUTE_COMMAND, "/reload"));
        assertThat(interactive).hasSize(1);

        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
        assertThat(control).hasSize(1);

        control.removeFirst().run();

        assertThat(client.cancelledRuns).containsExactly(new AgentRunId("run-1"));
        assertThat(client.sessionLevelCancelCalls).isZero();
        assertThat(client.reconcileCalls).isZero();
        assertThat(interactive).hasSize(1);
    }

    @Test
    void remoteV1RefusesCredentialSetupConsentResetAndPreview() {
        FakeClient client = new FakeClient(activeView());
        RecordingPreviewPublisher preview = new RecordingPreviewPublisher();
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                CodingAuthenticationClient.unavailable(),
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                CodingTerminalConnectionOptions.remoteV1(),
                Runnable::run);
        controller.attachToolOutputPreviewPublisher(preview);
        controller.open(SESSION_ID);

        assertThat(preview.subscribeCalls).isZero();

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/connect api deepseek"));
        assertThat(controller.secureInputRequested()).isFalse();
        assertThat(controller.state().status()).contains("Host application");

        controller.accept(input(TerminalInput.Kind.EXECUTE_COMMAND, "/approval reset"));
        assertThat(client.resetSessionApprovalCalls).isZero();
        assertThat(controller.state().status()).contains("unavailable in remote mode");
    }

    @Test
    void remoteV1HidesSessionConsentButKeepsPreciseApproval() {
        FakeClient client = new FakeClient(activeView(Optional.of(approval()))) {
            @Override
            public Optional<CodingApprovalPrompt> prepareApproval(InteractionView interaction) {
                return Optional.of(new CodingApprovalPrompt(interaction, true));
            }
        };
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                CodingAuthenticationClient.unavailable(),
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                CodingTerminalConnectionOptions.remoteV1(),
                Runnable::run);

        controller.open(SESSION_ID);

        assertThat(controller.state().selector()).hasValueSatisfying(selector -> {
            assertThat(selector.kind()).startsWith("interaction:");
            assertThat(selector.options()).containsExactly("Reject", "Allow once");
        });
    }

    @Test
    void embeddedDefaultsStillSubscribeToPreview() {
        FakeClient client = new FakeClient(activeView());
        RecordingPreviewPublisher preview = new RecordingPreviewPublisher();
        var controller = controller(client);
        controller.attachToolOutputPreviewPublisher(preview);

        controller.open(SESSION_ID);

        assertThat(preview.subscribeCalls).isEqualTo(1);
    }

    @Test
    void reorderedSameGenerationSubscriptionHandoffsKeepOnlyTheNewestObservers() throws Exception {
        FakeClient client = new FakeClient(activeView());
        ArrayDeque<Runnable> control = new ArrayDeque<>();
        ArrayDeque<Runnable> interactive = new ArrayDeque<>();
        ArrayDeque<Runnable> maintenance = new ArrayDeque<>();
        TerminalEventPump pump = new TerminalEventPump(1);
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                control::add,
                interactive::add,
                maintenance::add);
        controller.open(SESSION_ID);
        maintenance.removeFirst().run();
        controller.drainEvents();

        client.lastSubscription.close();
        controller.drainEvents();
        maintenance.removeFirst().run();
        controller.drainEvents();
        Runnable older = maintenance.removeFirst();

        assertThat(pump.offer(new TerminalUiAction.StatusChanged("one"))).isTrue();
        assertThat(pump.offer(new TerminalUiAction.StatusChanged("dropped"))).isFalse();
        controller.drainEvents();
        // The reconcile applies another same-generation snapshot, which schedules a second
        // subscription handoff while the first handoff is still pending.
        maintenance.removeLast().run();
        controller.drainEvents();
        Runnable newer = maintenance.removeLast();
        newer.run();
        controller.drainEvents();
        older.run();
        controller.drainEvents();
        while (!maintenance.isEmpty()) {
            maintenance.removeFirst().run();
        }
        controller.drainEvents();
        // Rejected handoffs close on the cleanup lane; a FIFO barrier observes the close without polling.
        controller.cleanupEffects.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(client.subscriptions).hasSize(3);
        assertThat(client.subscriptions.get(1).closed()).isFalse();
        assertThat(client.subscriptions.get(2).closed()).isTrue();
    }

    @Test
    void closeClosesABlockedInFlightObservationExactlyOnceWithoutCancellingTheRun() throws Exception {
        var subscribeStarted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var closedOnce = new java.util.concurrent.CountDownLatch(1);
        var closeCount = new java.util.concurrent.atomic.AtomicInteger();
        FakeClient client = new FakeClient(activeView()) {
            @Override
            public io.haifa.agent.runtime.api.RunEventSubscription subscribe(
                    AgentRunId runId,
                    io.haifa.agent.runtime.api.RunEventCursor after,
                    io.haifa.agent.runtime.api.AgentRunEventListener listener) {
                subscribeStarted.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return new io.haifa.agent.runtime.api.RunEventSubscription() {
                    private boolean closed;

                    @Override
                    public boolean closed() {
                        return closed;
                    }

                    @Override
                    public void close() {
                        if (!closed) {
                            closed = true;
                            closeCount.incrementAndGet();
                            closedOnce.countDown();
                        }
                    }
                };
            }
        };
        java.util.concurrent.ExecutorService maintenance = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var controller = new CodingTerminalController(
                    PROJECT_ID,
                    client,
                    new TerminalEventPump(32),
                    new TerminalUiReducer(),
                    TerminalUiState.initial(120, 40),
                    Runnable::run,
                    Runnable::run,
                    maintenance);
            controller.open(SESSION_ID);
            assertThat(subscribeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();

            controller.close();
            release.countDown();

            assertThat(closedOnce.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            assertThat(closeCount).hasValue(1);
            assertThat(client.cancelledSessions).isEmpty();
        } finally {
            release.countDown();
            maintenance.shutdownNow();
        }
    }

    @Test
    void anotherSelectionCanReconcileWhileThePreviousSelectionReconcileIsOutstanding() {
        FakeClient client = new FakeClient(activeView());
        AgentSessionId b = new AgentSessionId("session-b");
        client.viewsBySession.put(b, sessionView("session-b", "B"));
        ArrayDeque<Runnable> control = new ArrayDeque<>();
        ArrayDeque<Runnable> interactive = new ArrayDeque<>();
        ArrayDeque<Runnable> maintenance = new ArrayDeque<>();
        TerminalEventPump pump = new TerminalEventPump(1);
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                control::add,
                interactive::add,
                maintenance::add);
        controller.open(SESSION_ID);
        maintenance.removeFirst().run();
        client.lastSubscription.close();
        controller.drainEvents();
        assertThat(maintenance).hasSize(1);

        controller.open(b);
        assertThat(pump.offer(new TerminalUiAction.StatusChanged("one"))).isTrue();
        assertThat(pump.offer(new TerminalUiAction.StatusChanged("dropped"))).isFalse();
        controller.drainEvents();
        // Subscription closes use the dedicated cleanup lane, so only reconcile/handoff work remains.
        assertThat(maintenance).hasSize(2);

        maintenance.removeLast().run();
        controller.drainEvents();
        assertThat(controller.state().session().map(value -> value.summary().displayName()))
                .contains("B");

        while (!maintenance.isEmpty()) {
            maintenance.removeFirst().run();
            controller.drainEvents();
        }
        assertThat(controller.state().session().map(value -> value.summary().displayName()))
                .contains("B");
    }

    @Test
    void staleStreamFromThePreviousSelectionIsIgnored() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);
        io.haifa.agent.runtime.api.AgentRunEventListener oldListener = client.lastListener;
        AgentSessionId b = new AgentSessionId("session-b");
        client.viewsBySession.put(b, sessionView("session-b", "B"));
        controller.open(b);

        oldListener.onEvent(event(1, new RunEventPayloads.AssistantTextDelta("generation-1", "old stream")));
        controller.drainEvents();

        assertThat(controller.state().transcript())
                .noneMatch(item -> item.body().contains("old stream"));
    }

    @Test
    void staleRenameReceiptCannotOverwriteTheNewSelection() {
        FakeClient client = new FakeClient(activeView());
        AgentSessionId b = new AgentSessionId("session-b");
        client.viewsBySession.put(b, sessionView("session-b", "B"));
        ArrayDeque<Runnable> control = new ArrayDeque<>();
        ArrayDeque<Runnable> interactive = new ArrayDeque<>();
        ArrayDeque<Runnable> maintenance = new ArrayDeque<>();
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                control::add,
                interactive::add,
                maintenance::add);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.EXECUTE_COMMAND, "/rename renamed-a"));
        assertThat(interactive).hasSize(1);

        controller.open(b);
        interactive.removeFirst().run();
        controller.drainEvents();

        assertThat(controller.state().session().map(value -> value.summary().displayName()))
                .contains("B");
    }

    @Test
    void staleSubscriptionAndPreviewErrorsDoNotLeakIntoTheNewSelection() {
        FakeClient client = new FakeClient(activeView()) {
            @Override
            public io.haifa.agent.runtime.api.RunEventSubscription subscribe(
                    AgentRunId runId,
                    io.haifa.agent.runtime.api.RunEventCursor after,
                    io.haifa.agent.runtime.api.AgentRunEventListener listener) {
                throw new ProjectProductException("SUBSCRIPTION_UNAVAILABLE", "Subscription unavailable");
            }
        };
        AgentSessionId b = new AgentSessionId("session-b");
        client.viewsBySession.put(b, activeSessionView("session-b", "run-2", "B"));
        ToolOutputPreviewPublisher failingPreview = new ToolOutputPreviewPublisher() {
            @Override
            public ToolOutputPreviewSubscription subscribe(
                    AgentRunId runId, java.util.function.Consumer<ToolOutputPreview> consumer) {
                throw new ProjectProductException("PREVIEW_UNAVAILABLE", "Preview unavailable");
            }

            @Override
            public ToolOutputPreviewSink open(AgentRunId runId, io.haifa.agent.core.tool.ToolCallId toolCallId) {
                return ToolOutputPreviewPublisher.noopSink();
            }
        };
        ArrayDeque<Runnable> control = new ArrayDeque<>();
        ArrayDeque<Runnable> interactive = new ArrayDeque<>();
        ArrayDeque<Runnable> maintenance = new ArrayDeque<>();
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                CodingAuthenticationClient.unavailable(),
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                CodingTerminalConnectionOptions.embedded(),
                control::add,
                interactive::add,
                maintenance::add);
        controller.attachToolOutputPreviewPublisher(failingPreview);

        controller.open(SESSION_ID);
        controller.open(b);
        // Run the older A preview/subscription work after B is already displayed.
        maintenance.removeFirst().run();
        maintenance.removeFirst().run();
        controller.drainEvents();

        assertThat(controller.state().session().map(value -> value.summary().displayName()))
                .contains("B");
        assertThat(controller.state().recoverableError()).isEmpty();
    }

    @Test
    void staleSnapshotCannotResurrectATerminalRunOrResetTheAppliedCursor() throws Exception {
        var reconcileStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseReconcile = new java.util.concurrent.CountDownLatch(1);
        var reconcileReturned = new java.util.concurrent.CountDownLatch(1);
        var events = List.of(
                event(1, new RunEventPayloads.AssistantTextDelta("generation-1", "one")),
                event(2, new RunEventPayloads.AssistantTextDelta("generation-1", "two")));
        FakeClient client = new FakeClient(activeView()) {
            @Override
            public io.haifa.agent.runtime.api.RunEventPage events(
                    AgentRunId runId, io.haifa.agent.runtime.api.RunEventCursor after, int limit) {
                return new io.haifa.agent.runtime.api.RunEventPage(
                        events, events.getLast().cursor(), events.getLast().cursor(), false);
            }

            @Override
            public CodingSessionView reconcile(AgentSessionId sessionId) {
                reconcileStarted.countDown();
                try {
                    releaseReconcile.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                reconcileReturned.countDown();
                return activeView();
            }
        };
        java.util.concurrent.ExecutorService maintenance = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            TerminalEventPump pump = new TerminalEventPump(1);
            var controller = new CodingTerminalController(
                    PROJECT_ID,
                    client,
                    pump,
                    new TerminalUiReducer(),
                    TerminalUiState.initial(120, 40),
                    Runnable::run,
                    Runnable::run,
                    maintenance);
            controller.open(SESSION_ID);
            assertThat(pump.offer(new TerminalUiAction.StatusChanged("one"))).isTrue();
            assertThat(pump.offer(new TerminalUiAction.StatusChanged("dropped")))
                    .isFalse();
            controller.drainEvents();
            assertThat(reconcileStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();

            var terminal = event(10, new RunEventPayloads.RunLifecycle("COMPLETED", 2, "NONE"));
            pump.offer(new TerminalUiAction.RunEventReceived(terminal));
            controller.drainEvents();
            assertThat(controller.state().currentRunId()).isEmpty();
            assertThat(controller.state().appliedCursor().map(cursor -> cursor.exclusiveSequence()
                            .orElse(0L)))
                    .contains(10L);
            assertThat(controller.state().seenEventIds()).contains("event-10");

            releaseReconcile.countDown();
            assertThat(reconcileReturned.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            controller.drainEvents();

            assertThat(controller.state().currentRunId()).isEmpty();
            assertThat(controller.state().appliedCursor().map(cursor -> cursor.exclusiveSequence()
                            .orElse(0L)))
                    .contains(10L);
            assertThat(controller.state().seenEventIds()).contains("event-10");
        } finally {
            releaseReconcile.countDown();
            maintenance.shutdownNow();
        }
    }

    @Test
    void rejectedMaintenanceCleanupDoesNotBlockTheNextFrame() throws Exception {
        var closeStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseClose = new java.util.concurrent.CountDownLatch(1);
        var closed = new java.util.concurrent.CountDownLatch(1);
        FakeClient client = new FakeClient(activeView()) {
            @Override
            public io.haifa.agent.runtime.api.RunEventSubscription subscribe(
                    AgentRunId runId,
                    io.haifa.agent.runtime.api.RunEventCursor after,
                    io.haifa.agent.runtime.api.AgentRunEventListener listener) {
                return new io.haifa.agent.runtime.api.RunEventSubscription() {
                    private boolean isClosed;

                    @Override
                    public boolean closed() {
                        return isClosed;
                    }

                    @Override
                    public void close() {
                        if (isClosed) return;
                        isClosed = true;
                        closeStarted.countDown();
                        try {
                            releaseClose.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        closed.countDown();
                    }
                };
            }
        };
        java.util.concurrent.atomic.AtomicBoolean rejectMaintenance = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.Executor maintenance = task -> {
            if (rejectMaintenance.get()) throw new java.util.concurrent.RejectedExecutionException();
            task.run();
        };
        TerminalEventPump pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run,
                Runnable::run,
                maintenance);
        controller.open(SESSION_ID);
        rejectMaintenance.set(true);

        controller.accept(input(TerminalInput.Kind.EXECUTE_COMMAND, "/new"));
        assertThat(closeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        pump.offer(new TerminalUiAction.TerminalResized(90, 25));
        controller.drainEvents();
        assertThat(controller.state().columns()).isEqualTo(90);
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "still responsive", 16));
        assertThat(controller.state().editorBuffer()).isEqualTo("still responsive");

        releaseClose.countDown();
        assertThat(closed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        controller.close();
    }

    @Test
    void cleanupQueuedBehindABlockedCloseStillClosesAtControllerExitExactlyOnce() throws Exception {
        var firstCloseStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseFirstClose = new java.util.concurrent.CountDownLatch(1);
        var secondClosed = new java.util.concurrent.CountDownLatch(1);
        var subscriptionIds = new java.util.concurrent.atomic.AtomicInteger();
        var closeCounts = new java.util.concurrent.atomic.AtomicIntegerArray(4);
        FakeClient client = new FakeClient(activeView()) {
            @Override
            public io.haifa.agent.runtime.api.RunEventSubscription subscribe(
                    AgentRunId runId,
                    io.haifa.agent.runtime.api.RunEventCursor after,
                    io.haifa.agent.runtime.api.AgentRunEventListener listener) {
                int id = subscriptionIds.incrementAndGet();
                return new io.haifa.agent.runtime.api.RunEventSubscription() {
                    private boolean closed;

                    @Override
                    public boolean closed() {
                        return closed;
                    }

                    @Override
                    public void close() {
                        synchronized (this) {
                            if (closed) return;
                            closed = true;
                        }
                        closeCounts.incrementAndGet(id);
                        if (id == 1) {
                            firstCloseStarted.countDown();
                            try {
                                releaseFirstClose.await();
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        if (id == 2) secondClosed.countDown();
                    }
                };
            }
        };
        AgentSessionId b = new AgentSessionId("session-b");
        AgentSessionId c = new AgentSessionId("session-c");
        client.viewsBySession.put(b, activeSessionView("session-b", "run-2", "B"));
        client.viewsBySession.put(c, activeSessionView("session-c", "run-3", "C"));
        ArrayDeque<Runnable> control = new ArrayDeque<>();
        ArrayDeque<Runnable> interactive = new ArrayDeque<>();
        ArrayDeque<Runnable> maintenance = new ArrayDeque<>();
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                CodingAuthenticationClient.unavailable(),
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                CodingTerminalConnectionOptions.embedded(),
                3,
                control::add,
                interactive::add,
                maintenance::add);
        controller.open(SESSION_ID);
        maintenance.removeFirst().run();
        controller.drainEvents();

        controller.open(b);
        maintenance.removeFirst().run();
        controller.drainEvents();
        assertThat(firstCloseStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .isTrue();

        // The first close occupies the cleanup lane, so replacing B queues a second cleanup behind it.
        controller.open(c);
        maintenance.removeFirst().run();
        controller.drainEvents();
        assertThat(controller.state().session().map(value -> value.summary().displayName()))
                .contains("C");

        controller.close();
        releaseFirstClose.countDown();

        assertThat(secondClosed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(closeCounts.get(1)).isEqualTo(1);
        assertThat(closeCounts.get(2)).isEqualTo(1);
        assertThat(closeCounts.get(3)).isEqualTo(1);
        assertThat(client.cancelledSessions).isEmpty();
    }

    @Test
    void observationCapacityDeniesAdmissionWithoutBlockingAndReusesAfterClose() throws Exception {
        var closeStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseClose = new java.util.concurrent.CountDownLatch(1);
        var closed = new java.util.concurrent.CountDownLatch(1);
        var subscribed = new java.util.concurrent.atomic.AtomicInteger();
        FakeClient client = new FakeClient(activeView()) {
            @Override
            public io.haifa.agent.runtime.api.RunEventSubscription subscribe(
                    AgentRunId runId,
                    io.haifa.agent.runtime.api.RunEventCursor after,
                    io.haifa.agent.runtime.api.AgentRunEventListener listener) {
                subscribed.incrementAndGet();
                return new io.haifa.agent.runtime.api.RunEventSubscription() {
                    private boolean isClosed;

                    @Override
                    public boolean closed() {
                        return isClosed;
                    }

                    @Override
                    public void close() {
                        if (isClosed) return;
                        isClosed = true;
                        closeStarted.countDown();
                        try {
                            releaseClose.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        closed.countDown();
                    }
                };
            }
        };
        AgentSessionId b = new AgentSessionId("session-b");
        client.viewsBySession.put(b, activeSessionView("session-b", "run-2", "B"));
        ArrayDeque<Runnable> control = new ArrayDeque<>();
        ArrayDeque<Runnable> interactive = new ArrayDeque<>();
        ArrayDeque<Runnable> maintenance = new ArrayDeque<>();
        TerminalEventPump pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                CodingAuthenticationClient.unavailable(),
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                CodingTerminalConnectionOptions.embedded(),
                1,
                control::add,
                interactive::add,
                maintenance::add);
        controller.open(SESSION_ID);
        maintenance.removeFirst().run();
        controller.drainEvents();

        controller.accept(input(TerminalInput.Kind.EXECUTE_COMMAND, "/new"));
        assertThat(closeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        controller.open(b);
        assertThat(controller.state().session().map(value -> value.summary().displayName()))
                .contains("B");
        assertThat(controller.state().recoverableError()).contains("TERMINAL_OBSERVATION_CAPACITY");

        pump.offer(new TerminalUiAction.TerminalResized(90, 25));
        controller.drainEvents();
        assertThat(controller.state().columns()).isEqualTo(90);
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "still responsive", 16));
        assertThat(controller.state().editorBuffer()).isEqualTo("still responsive");

        releaseClose.countDown();
        assertThat(closed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // The bounded cleanup lane releases the permit after closing the handle, so a FIFO barrier
        // task proves the permit is reusable without polling.
        controller.cleanupEffects.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);

        controller.open(SESSION_ID);
        maintenance.removeFirst().run();
        controller.drainEvents();
        assertThat(subscribed).hasValue(2);
        controller.close();
    }

    private static CodingSessionView activeSessionView(String sessionId, String runId, String displayName) {
        AgentSessionId id = new AgentSessionId(sessionId);
        AgentRunId activeRunId = new AgentRunId(runId);
        return new CodingSessionView(
                new CodingSessionSummary(
                        id,
                        PROJECT_ID,
                        displayName,
                        AgentSessionStatus.ACTIVE,
                        Optional.of(activeRunId),
                        Optional.of(io.haifa.agent.core.run.AgentRunStatus.RUNNING),
                        0,
                        Instant.EPOCH,
                        0),
                Optional.of(new io.haifa.agent.runtime.api.AgentRunSnapshot(
                        activeRunId,
                        io.haifa.agent.core.run.AgentRunStatus.RUNNING,
                        1,
                        Instant.EPOCH,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                "sha256:configuration",
                "cli-coding@1.0.0",
                new CodingModelSelection(model("cli-coding@1.0.0", "cli-coding@1.0.0"), 0, true));
    }

    private static CodingSessionView sessionView(String sessionId, String displayName) {
        AgentSessionId id = new AgentSessionId(sessionId);
        return new CodingSessionView(
                new CodingSessionSummary(
                        id,
                        PROJECT_ID,
                        displayName,
                        AgentSessionStatus.ACTIVE,
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        Instant.EPOCH,
                        0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "sha256:configuration",
                "cli-coding@1.0.0",
                new CodingModelSelection(model("cli-coding@1.0.0", "cli-coding@1.0.0"), 0, true));
    }

    private static final class RecordingPreviewPublisher implements ToolOutputPreviewPublisher {
        private int subscribeCalls;

        @Override
        public ToolOutputPreviewSubscription subscribe(
                AgentRunId runId, java.util.function.Consumer<ToolOutputPreview> consumer) {
            subscribeCalls++;
            return () -> {};
        }

        @Override
        public ToolOutputPreviewSink open(AgentRunId runId, io.haifa.agent.core.tool.ToolCallId toolCallId) {
            return ToolOutputPreviewPublisher.noopSink();
        }
    }
}
