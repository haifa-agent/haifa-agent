package io.haifa.agent.application.project.product.coding.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.*;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.credential.api.SecretRedactor;
import io.haifa.agent.runtime.api.*;
import io.haifa.coding.api.CodingPresentationSubscription;
import io.haifa.coding.api.CodingProtocol.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One bounded serial publisher. Source registration precedes snapshot installation; delivery never owns execution. */
public final class CodingPresentationObservation implements CodingPresentationSubscription {
    private static final int SNAPSHOT_BYTES = 256 * 1024;
    private static final int UPDATE_BYTES = 64 * 1024;
    private static final int QUEUE_BYTES = 512 * 1024;
    private final CodingSessionClient client;
    private final AgentSessionId sessionId;
    private final Scope scope;
    private final SecretRedactor redactor;
    private final Supplier<Consent> consent;
    private final String context;
    private final java.util.function.Function<AgentRunId, RunEventCursor> eventStart;
    private final java.util.function.Function<InteractionView, CodingReviewState> reviewState;
    private final ObjectMapper json = new ObjectMapper();
    private final ThreadPoolExecutor publisher = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            Thread.ofVirtual().factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final CompletableFuture<Snapshot> initial = new CompletableFuture<>();
    private final ArrayDeque<Envelope> outgoing = new ArrayDeque<>();
    private final Map<String, Entry> tools = new LinkedHashMap<>();
    private static final int DELIVERY_LIMIT = 8;
    private static final String DELIVERY_WINDOW_NOTICE = "Earlier delivery events may be outside the view window.";
    private final ArrayDeque<Entry> delivery = new ArrayDeque<>();
    private boolean deliveryWindowReduced;
    private int queuedBytes;
    private CodingSessionChanges.Registration sessionHandle;
    private AutoCloseable events;
    private AutoCloseable output;
    private AgentRunId watched;
    private long eventSequence;
    private long outputSequence;
    private String generation = "";
    private String modelCall = "";
    private int physicalAttempt;
    private String liveText = "";
    private io.haifa.agent.credential.core.DefaultSecretRedactor.Stream liveRedaction;
    private CodingPresentationText.Stream liveDisplay;
    private boolean liveTruncated;
    private Snapshot current;
    private String pendingToolKey;
    private String pendingInteractionId;
    private final Set<AgentRunId> savedRuns = new LinkedHashSet<>();
    private final Object handleLock = new Object();

    public CodingPresentationObservation(
            CodingSessionService sessions,
            CodingSessionClient client,
            Scope scope,
            SecretRedactor redactor,
            Supplier<Consent> consent) {
        this(sessions, client, scope, redactor, consent, "Host process");
    }

    public CodingPresentationObservation(
            CodingSessionService sessions,
            CodingSessionClient client,
            Scope scope,
            SecretRedactor redactor,
            Supplier<Consent> consent,
            String context) {
        this(sessions, client, scope, redactor, consent, context, run -> {
            var head = client.events(run, RunEventCursor.beforeFirst(run), 1).headCursor();
            long sequence = Math.max(0, head.exclusiveSequence().orElse(0) - 128);
            return sequence == 0
                    ? RunEventCursor.beforeFirst(run)
                    : new RunEventCursor(run, head.feedVersion(), java.util.OptionalLong.of(sequence));
        });
    }

    public CodingPresentationObservation(
            CodingSessionService sessions,
            CodingSessionClient client,
            Scope scope,
            SecretRedactor redactor,
            Supplier<Consent> consent,
            String context,
            java.util.function.Function<AgentRunId, RunEventCursor> eventStart) {
        this(
                listener -> sessions.observeChanges(new AgentSessionId(scope.sessionId()), listener),
                client,
                scope,
                redactor,
                consent,
                context,
                eventStart,
                sessions::reviewState);
    }

    CodingPresentationObservation(
            java.util.function.Function<Runnable, CodingSessionChanges.Registration> register,
            CodingSessionClient client,
            Scope scope,
            SecretRedactor redactor,
            Supplier<Consent> consent) {
        this(
                register,
                client,
                scope,
                redactor,
                consent,
                "Host process",
                RunEventCursor::beforeFirst,
                interaction -> new CodingReviewState(false, true, false));
    }

    private CodingPresentationObservation(
            java.util.function.Function<Runnable, CodingSessionChanges.Registration> register,
            CodingSessionClient client,
            Scope scope,
            SecretRedactor redactor,
            Supplier<Consent> consent,
            String context,
            java.util.function.Function<AgentRunId, RunEventCursor> eventStart,
            java.util.function.Function<InteractionView, CodingReviewState> reviewState) {
        this.reviewState = Objects.requireNonNull(reviewState);
        this.eventStart = Objects.requireNonNull(eventStart);
        this.context = Objects.requireNonNull(context);
        this.client = Objects.requireNonNull(client);
        this.scope = Objects.requireNonNull(scope);
        this.sessionId = new AgentSessionId(scope.sessionId());
        this.redactor = Objects.requireNonNull(redactor);
        this.consent = Objects.requireNonNull(consent);
        try {
            sessionHandle = register.apply(this::refresh);
            enqueue(() -> {
                Snapshot first = read(0);
                io.haifa.coding.api.CodingProtocolChecks.snapshot(first);
                if (bytes(first) > SNAPSHOT_BYTES) throw new IllegalStateException("SNAPSHOT_BUDGET");
                current = first;
                initial.complete(first);
            });
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    /** Coalesced invalidation, called before receipt serialization and by product-owned maintenance. */
    public void refresh() {
        if (refreshPending.compareAndSet(false, true))
            enqueue(() -> {
                refreshPending.set(false);
                if (current != null) publish();
            });
    }

    /** Lease changes do not require a full Session/history reload. */
    public void refreshConsent() {
        enqueue(() -> {
            if (current == null) return;
            if (watched != null)
                client.events(
                        watched,
                        eventSequence == 0
                                ? RunEventCursor.beforeFirst(watched)
                                : new RunEventCursor(watched, "1", java.util.OptionalLong.of(eventSequence)),
                        1);
            Consent value = consent.get();
            if (value.available() && !current.consent().available()) {
                publish();
                return;
            }
            if (!value.equals(current.consent()))
                publish(new Snapshot(
                        "snapshot",
                        scope,
                        current.revision() + 1,
                        current.hydration(),
                        current.session(),
                        current.entries().stream()
                                .map(entry -> consentAvailability(entry, value))
                                .toList(),
                        current.history(),
                        current.live(),
                        value,
                        current.actions(),
                        current.notices()));
        });
    }

    private Entry consentAvailability(Entry entry, Consent consent) {
        if (entry.interaction() == null || consent.available()) return entry;
        var value = entry.interaction();
        return new Entry(
                entry.id(),
                entry.kind(),
                entry.runId(),
                entry.role(),
                entry.title(),
                entry.text(),
                entry.status(),
                entry.sequence(),
                entry.truncated(),
                new Interaction(
                        value.id(),
                        value.revision(),
                        value.kind(),
                        value.input(),
                        value.review(),
                        value.actions().stream()
                                .map(action -> action.kind().equals("allowForSession")
                                        ? new Action(
                                                action.kind(),
                                                action.target(),
                                                action.choice(),
                                                false,
                                                "SESSION_CONSENT_UNAVAILABLE")
                                        : action)
                                .toList()),
                entry.occurredAtEpochMillis(),
                entry.toolCallId(),
                entry.interactionId(),
                entry.noticeCode(),
                entry.diagnosticId());
    }

    private void enqueue(Runnable action) {
        if (closed.get()) return;
        try {
            publisher.execute(() -> {
                if (closed.get()) return;
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    resync("SOURCE_UNAVAILABLE");
                }
            });
        } catch (RejectedExecutionException overflow) {
            resync("SOURCE_QUEUE_OVERFLOW");
        }
    }

    private Snapshot read(long revision) {
        var view = client.presentationSnapshot(sessionId);
        AgentRunId run = view.activeRun().map(AgentRunSnapshot::runId).orElse(null);
        var history = client.history(sessionId, 31);
        boolean previousSaved = watched != null
                && history.items().stream()
                        .anyMatch(item -> item.kind() == CodingSessionHistoryItem.Kind.ASSISTANT
                                && item.sourceRunId()
                                        .filter(watched.value()::equals)
                                        .isPresent());
        boolean handoffPending =
                run != null && !run.equals(watched) && watched != null && !liveText.isEmpty() && !previousSaved;
        if (run != null && !run.equals(watched) && !handoffPending) watch(run);
        // Register first, then reread authority. Concurrent changes are queued behind this serial installation.
        view = client.presentationSnapshot(sessionId);
        history = client.history(sessionId, 31);
        AgentRunId observed = view.activeRun().map(AgentRunSnapshot::runId).orElse(null);
        if (observed != null && !observed.equals(watched) && !handoffPending) refresh();
        List<Entry> entries = new ArrayList<>();
        for (var item : history.items()) entries.add(CodingPresentationHistory.entry(item, redactor));
        Consent observedConsent = consent.get();
        var pending = view.pendingInteraction();
        pendingToolKey = pending.filter(v -> java.util.Set.of("tool", "clarification")
                        .contains(v.target().type()))
                .map(v -> toolEntryId(v.runId(), v.target().reference()))
                .orElse(null);
        pendingInteractionId = pending.map(v -> v.requestId().value()).orElse(null);
        Window window = window(
                entries,
                pending.map(value -> interaction(value, observedConsent)).orElse(null));
        entries = window.entries();
        boolean windowReduced = window.reduced();
        boolean savedLive = watched != null
                && history.items().stream()
                        .anyMatch(item -> item.kind() == CodingSessionHistoryItem.Kind.ASSISTANT
                                && item.sourceRunId()
                                        .filter(watched.value()::equals)
                                        .isPresent());
        if (savedLive) {
            savedRuns.add(watched);
            while (savedRuns.size() > 32) savedRuns.remove(savedRuns.iterator().next());
            liveText = "";
        }
        Live live = live();
        boolean syncing = pending.filter(interaction -> viewRunMismatch(interaction, observed))
                        .isPresent()
                || handoffPending
                || (observed != null && !observed.equals(watched))
                || (live != null
                        && client.findRun(watched)
                                .map(value -> value.status().isTerminal())
                                .orElse(false));
        if (syncing) entries = entries.stream().map(this::unavailable).toList();
        var active = view.activeRun()
                .map(value -> new Run(value.runId().value(), value.status().name(), value.version()))
                .orElse(null);
        var summary = view.summary();
        var model = view.model().model();
        var facts = new Session(
                sessionId.value(),
                safe(summary.displayName(), 256),
                summary.revision(),
                summary.status().name(),
                active,
                model.id(),
                safe(model.displayName(), 512),
                safe(context, 4096),
                view.model().revision(),
                view.model().available());
        boolean running =
                view.activeRun().map(value -> !value.status().isTerminal()).orElse(false);
        boolean activeSession = summary.status() == io.haifa.agent.core.session.AgentSessionStatus.ACTIVE;
        List<Action> actions = new ArrayList<>();
        if (running)
            actions.add(new Action(
                    "cancel",
                    new Target(sessionId.value(), active.id(), null, active.revision()),
                    null,
                    activeSession && !syncing,
                    syncing ? "SOURCE_REFRESHING" : (activeSession ? null : "SESSION_INACTIVE")));
        boolean submit = activeSession && !running && !syncing;
        actions.add(new Action(
                "submit",
                new Target(sessionId.value(), null, null, null),
                null,
                submit,
                submit ? null : (syncing ? "SOURCE_REFRESHING" : (running ? "RUN_ACTIVE" : "SESSION_INACTIVE"))));
        Snapshot result = new Snapshot(
                "snapshot",
                scope,
                revision,
                syncing ? "refreshing" : "ready",
                facts,
                entries,
                new History(
                        windowReduced || history.earlierHistoryAvailable(),
                        windowReduced
                                ? window.before()
                                : (history.nextBeforeSequence().isPresent()
                                        ? Long.valueOf(
                                                history.nextBeforeSequence().getAsLong())
                                        : null),
                        !windowReduced
                                && entries.stream()
                                        .filter(entry -> entry.sequence() != null)
                                        .noneMatch(Entry::truncated)),
                live,
                observedConsent,
                actions,
                deliveryNotices(List.of(
                        "Only safe visible text and bounded tool summaries are projected; non-text content is unavailable.",
                        "Transient preview is separate. Live text requires a streaming-capable credential redactor.")));
        boolean reduced = false;
        while (bytes(result) > SNAPSHOT_BYTES) {
            List<Entry> smaller = new ArrayList<>(result.entries());
            int removable = -1;
            for (int index = 0; index < smaller.size(); index++) {
                if (!smaller.get(index).kind().equals("interaction")) {
                    removable = index;
                    break;
                }
            }
            boolean omittedHistory = result.history().hasEarlier();
            if (removable >= 0) {
                Long sequence = smaller.get(removable).sequence();
                if (sequence == null) {
                    deliveryWindowReduced |= smaller.remove(removable).role().equals("delivery");
                } else {
                    smaller.removeIf(entry -> sequence.equals(entry.sequence()));
                    omittedHistory = true;
                }
            } else {
                smaller = smaller.stream().map(this::collapseReview).toList();
            }
            smaller = smaller.stream().map(this::unavailable).toList();
            long before = smaller.stream()
                    .filter(e -> e.sequence() != null)
                    .mapToLong(Entry::sequence)
                    .min()
                    .orElse(history.items().stream()
                                    .mapToLong(CodingSessionHistoryItem::sequence)
                                    .max()
                                    .orElse(0)
                            + 1);
            result = new Snapshot(
                    "snapshot",
                    scope,
                    revision,
                    "refreshing",
                    facts,
                    smaller,
                    new History(omittedHistory, omittedHistory ? Long.valueOf(Math.max(1, before)) : null, false),
                    live,
                    result.consent(),
                    result.actions().stream()
                            .map(action ->
                                    new Action(action.kind(), action.target(), action.choice(), false, "VIEW_BUDGET"))
                            .toList(),
                    deliveryNotices(List.of(
                            "View window reduced to UTF-8 budget; earlier entries or review data may be incomplete.")));
            if (reduced && removable < 0 && bytes(result) > SNAPSHOT_BYTES)
                throw new IllegalStateException("CRITICAL_REVIEW_BUDGET");
            reduced = true;
        }
        return result;
    }

    private boolean viewRunMismatch(InteractionView interaction, AgentRunId run) {
        return run == null || !interaction.runId().equals(run);
    }

    private Entry collapseReview(Entry entry) {
        if (entry.interaction() == null) return entry;
        var value = entry.interaction();
        return new Entry(
                entry.id(),
                entry.kind(),
                entry.runId(),
                entry.role(),
                entry.title(),
                head(entry.text(), 8000),
                entry.status(),
                entry.sequence(),
                true,
                new Interaction(
                        value.id(),
                        value.revision(),
                        value.kind(),
                        new Input("none", 0, 0, 0, List.of(), List.of()),
                        null,
                        value.actions().stream()
                                .map(action -> new Action(
                                        action.kind(),
                                        action.target(),
                                        action.choice(),
                                        false,
                                        "REVIEW_BUDGET_EXCEEDED"))
                                .toList()),
                entry.occurredAtEpochMillis(),
                entry.toolCallId(),
                entry.interactionId(),
                entry.noticeCode(),
                entry.diagnosticId());
    }

    private Entry unavailable(Entry value) {
        if (value.interaction() == null) return value;
        var interaction = value.interaction();
        var actions = interaction.actions().stream()
                .map(action -> new Action(action.kind(), action.target(), action.choice(), false, "SOURCE_REFRESHING"))
                .toList();
        return new Entry(
                value.id(),
                value.kind(),
                value.runId(),
                value.role(),
                value.title(),
                value.text(),
                value.status(),
                value.sequence(),
                value.truncated(),
                new Interaction(
                        interaction.id(),
                        interaction.revision(),
                        interaction.kind(),
                        interaction.input(),
                        interaction.review(),
                        actions),
                value.occurredAtEpochMillis(),
                value.toolCallId(),
                value.interactionId(),
                value.noticeCode(),
                value.diagnosticId());
    }

    private Entry interaction(InteractionView value, Consent observedConsent) {
        if (!sessionId.equals(value.sessionId())) throw new IllegalArgumentException("SOURCE_SCOPE_MISMATCH");
        return projectInteraction(value, reviewState.apply(value), observedConsent.available(), redactor);
    }

    static Entry projectInteraction(
            InteractionView value, CodingReviewState completeness, boolean consentAvailable, SecretRedactor redactor) {
        boolean supported = java.util.Set.of("none", "text", "single-choice", "multi-choice")
                        .contains(value.inputContract().type().value())
                && value.kind().known();
        var contract = value.inputContract();
        Input input = new Input(
                contract.type().value(),
                contract.maximumCharacters(),
                contract.minimumSelections(),
                contract.maximumSelections(),
                contract.options().stream()
                        .map(option -> new InputOption(
                                option.id(), CodingPresentationText.safe(option.label(), 512, redactor), null))
                        .toList(),
                value.clarificationPresentation()
                        .map(p -> p.questions().stream()
                                .map(q -> new Question(
                                        CodingPresentationText.safe(q.header(), 32, redactor),
                                        CodingPresentationText.safe(q.question(), 2000, redactor),
                                        q.multiple(),
                                        q.allowCustom(),
                                        q.options().stream()
                                                .map(o -> new InputOption(
                                                        o.optionId(),
                                                        CodingPresentationText.safe(o.label(), 512, redactor),
                                                        o.description()
                                                                .map(t -> CodingPresentationText.safe(t, 512, redactor))
                                                                .orElse(null)))
                                                .toList()))
                                .toList())
                        .orElse(List.of()));
        Review review = value.approvalPresentation()
                .map(p -> new Review(
                        CodingPresentationText.safe(p.purpose(), 512, redactor),
                        p.contentType(),
                        CodingPresentationText.safe(p.content(), 16384, redactor),
                        p.environment().stream()
                                .map(f -> new ReviewFact(
                                        CodingPresentationText.safe(f.label(), 64, redactor),
                                        CodingPresentationText.safe(f.value(), 512, redactor)))
                                .toList(),
                        p.technical().stream()
                                .map(f -> new ReviewFact(
                                        CodingPresentationText.safe(f.label(), 64, redactor),
                                        CodingPresentationText.safe(f.value(), 512, redactor)))
                                .toList(),
                        p.risk().orElse(null),
                        completeness.targetComplete(),
                        completeness.contentTruncated()))
                .orElse(null);
        String prompt = CodingPresentationText.safe(value.safePrompt(), 16384, redactor);
        boolean complete = prompt.equals(redactor.redact(value.safePrompt()))
                && supported
                && (!value.kind().equals(InteractionKind.APPROVAL)
                        || (review != null && completeness.targetComplete()));
        var target = new Target(
                value.sessionId().value(),
                value.runId().value(),
                value.requestId().value(),
                value.revision());
        List<Action> actions = new ArrayList<>(value.allowedActions().stream()
                .map(action ->
                        new Action("respond", target, action.value(), complete, complete ? null : "REVIEW_UNAVAILABLE"))
                .toList());
        if (value.kind().equals(InteractionKind.APPROVAL)) {
            boolean available = complete && completeness.sessionConsentEligible() && consentAvailable;
            actions.add(new Action(
                    "allowForSession",
                    target,
                    "approve",
                    available,
                    available
                            ? null
                            : (!consentAvailable ? "SESSION_CONSENT_UNAVAILABLE" : "EXACT_APPROVAL_REQUIRED")));
        }
        return new Entry(
                "interaction-" + value.requestId().value(),
                "interaction",
                value.runId().value(),
                "interaction",
                CodingPresentationText.safe(value.title(), 256, redactor),
                prompt,
                value.state().name(),
                null,
                !complete || completeness.contentTruncated(),
                new Interaction(
                        value.requestId().value(),
                        value.revision(),
                        value.kind().value(),
                        input,
                        review,
                        actions),
                value.createdAt().toEpochMilli(),
                null,
                null,
                null,
                null);
    }

    private void watch(AgentRunId run) {
        closeHandle(events);
        closeHandle(output);
        watched = run;
        eventSequence = 0;
        outputSequence = 0;
        liveText = "";
        generation = "";
        liveTruncated = false;
        var start = eventStart.apply(run);
        deliveryWindowReduced |= start.exclusiveSequence().orElse(0) > 0;
        var tail = client.events(run, start, 128);
        for (var event : tail.items()) acceptEvent(run, event, false);
        eventSequence =
                Math.max(eventSequence, tail.nextCursor().exclusiveSequence().orElse(0));
        AutoCloseable newEvents =
                client.subscribe(run, tail.nextCursor(), event -> enqueue(() -> acceptEvent(run, event, true)));
        synchronized (handleLock) {
            if (closed()) closeHandle(newEvents);
            else events = newEvents;
        }
        AutoCloseable newOutput = client.subscribeOutput(run, RunOutputCursor.BEFORE_FIRST, event -> {
            if (event.textDelta().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32 * 1024) {
                resync("SOURCE_CHUNK_BUDGET");
                return;
            }
            enqueue(() -> {
                if (!event.runId().equals(run)) {
                    resync("SOURCE_SCOPE_MISMATCH");
                    return;
                }
                if (!run.equals(watched) || savedRuns.contains(run) || event.sequence() <= outputSequence) return;
                boolean gap = event.sequence() > outputSequence + 1;
                outputSequence = event.sequence();
                if (!generation.equals(event.generationId())
                        || !modelCall.equals(event.modelCallId())
                        || physicalAttempt != event.physicalAttempt()) {
                    generation = event.generationId();
                    modelCall = event.modelCallId();
                    physicalAttempt = event.physicalAttempt();
                    liveText = "";
                    liveTruncated = gap;
                    liveRedaction = redactor instanceof io.haifa.agent.credential.core.DefaultSecretRedactor supported
                            ? supported.openStream(4096)
                            : null;
                    liveDisplay = new CodingPresentationText.Stream();
                }
                liveTruncated |= gap;
                if (event.type() == AgentRunOutputEventType.RUN_OUTPUT_FAILED
                        || event.type() == AgentRunOutputEventType.RUN_OUTPUT_SUPERSEDED) {
                    liveText = "";
                    generation = "";
                    liveRedaction = null;
                }
                if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA) {
                    String fragment = liveDisplay.accept(event.textDelta()).replace("\r", "\n");
                    String display = liveRedaction == null ? "" : liveRedaction.accept(fragment);
                    if (liveRedaction == null) liveTruncated = true;
                    String combined = liveText + display;
                    liveTruncated |= combined.codePointCount(0, combined.length()) > 8000;
                    liveText = head(combined, 8000);
                }
                if (current != null) {
                    if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED) publish();
                    else publishLive();
                }
            });
        });
        synchronized (handleLock) {
            if (closed()) closeHandle(newOutput);
            else output = newOutput;
        }
    }

    private void acceptEvent(AgentRunId run, AgentRunEvent event, boolean publish) {
        if (!event.runId().equals(run) || !event.sessionId().equals(sessionId)) {
            resync("SOURCE_SCOPE_MISMATCH");
            return;
        }
        if (!run.equals(watched) || event.sequence() <= eventSequence) return;
        eventSequence = event.sequence();
        if (event.payload() instanceof RunEventPayloads.ToolLifecycle tool) {
            var entry = new Entry(
                    toolEntryId(run, tool.toolCallId()),
                    "tool",
                    run.value(),
                    "tool",
                    safe(tool.displayName(), 256),
                    safe(tool.targetSummary(), 8000),
                    tool.status(),
                    null,
                    true,
                    null,
                    event.occurredAt().toEpochMilli(),
                    tool.toolCallId(),
                    null,
                    null,
                    null);
            tools.put(entry.id(), entry);
            while (tools.size() > 32) tools.remove(tools.keySet().iterator().next());
        } else if (event.payload() instanceof RunEventPayloads.DeliveryLifecycle lifecycle) {
            if (delivery.size() == DELIVERY_LIMIT) {
                delivery.removeFirst();
                deliveryWindowReduced = true;
            }
            delivery.addLast(deliveryEntry(event, lifecycle));
        }
        if (publish && current != null) {
            if (event.payload() instanceof RunEventPayloads.ToolLifecycle
                    || event.payload() instanceof RunEventPayloads.DeliveryLifecycle) publishRecentEvents();
            else if (event.payload() instanceof RunEventPayloads.RunLifecycle
                    || event.payload() instanceof RunEventPayloads.InteractionLifecycle
                    || event.payload() instanceof RunEventPayloads.MessageCommitted) publish();
        }
    }

    private Live live() {
        if (watched == null || savedRuns.contains(watched) || generation.isEmpty()) return null;
        return new Live(
                watched.value(),
                generation,
                outputSequence,
                safe(liveText, 8000),
                liveTruncated,
                modelCall,
                physicalAttempt);
    }

    private void publishLive() {
        publish(new Snapshot(
                "snapshot",
                scope,
                current.revision() + 1,
                current.hydration(),
                current.session(),
                current.entries(),
                current.history(),
                live(),
                current.consent(),
                current.actions(),
                current.notices()));
    }

    private Entry deliveryEntry(AgentRunEvent event, RunEventPayloads.DeliveryLifecycle value) {
        String code = safe(value.reasonCode(), 128);
        List<String> lines = new ArrayList<>();
        lines.add("Reason: " + code);
        if (value.attempt() > 0) lines.add("Repair: " + value.attempt());
        if (!"NONE".equals(value.limitingResource())) {
            lines.add("Limiting resource: " + value.limitingResource());
            lines.add("Usage: " + value.limitingUsed() + " / " + value.limitingLimit());
        }
        lines.add("Remaining: " + value.remainingPercent() + "%");
        if (!value.missingEvidence().isEmpty()) {
            lines.add("Missing: " + String.join(", ", value.missingEvidence()));
        }
        String body = safe(String.join("\n", lines), Integer.MAX_VALUE);
        return new Entry(
                sourceEntryId("delivery", event.runId(), event.eventId()),
                "notice",
                event.runId().value(),
                "delivery",
                "BUDGET".equals(value.phase()) ? "Budget threshold" : "Completion deferred",
                head(body, 1024),
                safe(value.status(), 64),
                null,
                body.codePointCount(0, body.length()) > 1024,
                null,
                event.occurredAt().toEpochMilli(),
                null,
                null,
                code.isEmpty() ? null : code,
                null);
    }

    private List<String> deliveryNotices(List<String> notices) {
        if (!deliveryWindowReduced || notices.contains(DELIVERY_WINDOW_NOTICE)) return notices;
        var result = new ArrayList<>(notices);
        result.add(DELIVERY_WINDOW_NOTICE);
        return result;
    }

    private static String toolEntryId(AgentRunId run, String toolCallId) {
        return sourceEntryId("tool", run, toolCallId);
    }

    private static String sourceEntryId(String kind, AgentRunId run, String sourceId) {
        String identity = run.value().length() + ":" + run.value() + sourceId.length() + ":" + sourceId;
        try {
            return kind + "-"
                    + java.util.HexFormat.of()
                            .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private Entry linkInteraction(Entry entry) {
        return new Entry(
                entry.id(),
                entry.kind(),
                entry.runId(),
                entry.role(),
                entry.title(),
                entry.text(),
                entry.status(),
                entry.sequence(),
                entry.truncated(),
                null,
                entry.occurredAtEpochMillis(),
                entry.toolCallId(),
                entry.id().equals(pendingToolKey) ? pendingInteractionId : null,
                entry.noticeCode(),
                entry.diagnosticId());
    }

    private record Window(List<Entry> entries, boolean reduced, Long before) {}

    private Window window(List<Entry> history, Entry interaction) {
        var recent = tools.values().stream()
                .skip(Math.max(0, tools.size() - 8))
                .map(this::linkInteraction)
                .toList();
        List<Entry> retained = new ArrayList<>(history);
        int capacity = 32 - recent.size() - delivery.size() - (interaction == null ? 0 : 1);
        boolean reduced = retained.size() > capacity;
        Long removed = null;
        while (retained.size() > capacity) {
            Long sequence = retained.getFirst().sequence();
            removed = sequence;
            if (sequence == null) retained.removeFirst();
            else retained.removeIf(entry -> sequence.equals(entry.sequence()));
        }
        Long before = retained.stream()
                .filter(entry -> entry.sequence() != null)
                .map(Entry::sequence)
                .findFirst()
                .orElse(removed == null ? null : removed + 1);
        retained.addAll(recent);
        retained.addAll(delivery);
        if (interaction != null) retained.add(interaction);
        return new Window(retained, reduced, before);
    }

    private void publishRecentEvents() {
        Window window = window(
                current.entries().stream()
                        .filter(e -> !e.kind().equals("tool")
                                && !e.kind().equals("interaction")
                                && !e.role().equals("delivery"))
                        .toList(),
                current.entries().stream()
                        .filter(e -> e.kind().equals("interaction"))
                        .findFirst()
                        .orElse(null));
        History history = new History(
                window.reduced() || current.history().hasEarlier(),
                window.reduced() ? window.before() : current.history().beforeSequence(),
                !window.reduced() && current.history().complete());
        publish(new Snapshot(
                "snapshot",
                scope,
                current.revision() + 1,
                current.hydration(),
                current.session(),
                window.entries(),
                history,
                current.live(),
                current.consent(),
                current.actions(),
                deliveryNotices(current.notices())));
    }

    private void publish() {
        publish(read(current.revision() + 1));
    }

    private void publish(Snapshot next) {
        io.haifa.coding.api.CodingProtocolChecks.snapshot(next);
        Snapshot sameRevision = new Snapshot(
                "snapshot",
                scope,
                current.revision(),
                next.hydration(),
                next.session(),
                next.entries(),
                next.history(),
                next.live(),
                next.consent(),
                next.actions(),
                next.notices());
        if (current.equals(sameRevision)) return;
        Update update = new Update("update", scope.viewId(), current.revision(), next.revision(), next);
        int length = bytes(update);
        synchronized (outgoing) {
            if (closed()) return;
            if (length > UPDATE_BYTES || queuedBytes + length > QUEUE_BYTES) {
                resync("VIEW_BUDGET_RESYNC");
                return;
            }
            outgoing.add(update);
            queuedBytes += length;
            outgoing.notifyAll();
        }
        current = next;
    }

    private int bytes(Object value) {
        try {
            return json.writeValueAsBytes(value).length;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("VIEW_ENCODING_FAILED");
        }
    }

    private String safe(String value, int maximum) {
        return CodingPresentationText.safe(value, maximum, redactor);
    }

    private static String head(String value, int maximum) {
        return value.substring(
                0, value.offsetByCodePoints(0, Math.min(maximum, value.codePointCount(0, value.length()))));
    }

    private void resync(String reason) {
        synchronized (outgoing) {
            outgoing.clear();
            outgoing.add(new Resync("resync", scope.viewId(), reason));
            queuedBytes = 0;
            initial.completeExceptionally(new IllegalStateException(reason));
            close();
            outgoing.notifyAll();
        }
    }

    @Override
    public Snapshot initial() {
        try {
            return initial.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("VIEW_INITIAL_INTERRUPTED");
        } catch (TimeoutException timeout) {
            close();
            throw new IllegalStateException("VIEW_INITIAL_TIMEOUT");
        } catch (ExecutionException failed) {
            throw new IllegalStateException("VIEW_INITIAL_UNAVAILABLE", failed.getCause());
        }
    }

    @Override
    public Envelope poll(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative observation timeout");
        long remaining = timeout.toNanos();
        long deadline = System.nanoTime() + remaining;
        synchronized (outgoing) {
            while (outgoing.isEmpty() && !closed() && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(outgoing, remaining);
                remaining = deadline - System.nanoTime();
            }
            Envelope value = outgoing.poll();
            if (value != null) queuedBytes = Math.max(0, queuedBytes - bytes(value));
            return value;
        }
    }

    @Override
    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeHandle(sessionHandle);
        synchronized (handleLock) {
            closeHandle(events);
            closeHandle(output);
            events = null;
            output = null;
        }
        synchronized (outgoing) {
            if (!(outgoing.peek() instanceof Resync)) {
                outgoing.clear();
                queuedBytes = 0;
            }
            outgoing.notifyAll();
        }
        publisher.shutdown();
        initial.completeExceptionally(new IllegalStateException("VIEW_CLOSED"));
    }

    private static void closeHandle(AutoCloseable value) {
        if (value != null)
            try {
                value.close();
            } catch (Exception ignored) {
                /* observation only */
            }
    }
}
