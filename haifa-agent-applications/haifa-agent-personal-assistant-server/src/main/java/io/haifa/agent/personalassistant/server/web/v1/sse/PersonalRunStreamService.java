package io.haifa.agent.personalassistant.server.web.v1.sse;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import io.haifa.agent.personalassistant.server.web.v1.mapper.PersonalApiMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Active-run SSE projection merging durable facts with transient model output.
 *
 * <p>SSE IDs carry both source-local cursors and a process epoch. Durable and transient sequences
 * therefore never collide, and a server restart resets only the non-durable output cursor.
 */
@Service
public final class PersonalRunStreamService {
    private static final String CURSOR_VERSION = "v1";

    private final PersonalAssistantApplication application;
    private final PersonalApiMapper mapper;
    private final String processEpoch = UUID.randomUUID().toString();

    public PersonalRunStreamService(PersonalAssistantApplication application, PersonalApiMapper mapper) {
        this.application = application;
        this.mapper = mapper;
    }

    public Flux<ServerSentEvent<PersonalApiDtos.StreamEvent>> open(String runId, Optional<String> lastEventId) {
        PersonalAssistantApplication.RunView initial =
                application.run(runId).orElseThrow(() -> new IllegalArgumentException("run does not exist"));
        StreamPosition start = lastEventId
                .filter(value -> !value.isBlank())
                .map(value -> decode(runId, value))
                .orElseGet(() -> {
                    var cursor = application.initialStreamCursor(runId);
                    return new StreamPosition(cursor.durableSequence(), cursor.transientSequence());
                });
        if (terminal(initial.status())) return Flux.just(finalEvent(initial, start));

        Flux<ServerSentEvent<PersonalApiDtos.StreamEvent>> committed =
                Flux.create(sink -> subscribe(runId, start, sink), FluxSink.OverflowStrategy.ERROR);
        Flux<ServerSentEvent<PersonalApiDtos.StreamEvent>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(ignored -> ServerSentEvent.<PersonalApiDtos.StreamEvent>builder()
                        .comment("heartbeat")
                        .build());
        return committed
                .publish(shared -> Flux.merge(shared, heartbeat.takeUntilOther(shared.ignoreElements())))
                .take(Duration.ofMinutes(5));
    }

    public Flux<ServerSentEvent<PersonalApiDtos.StreamEvent>> open(String runId) {
        return open(runId, Optional.empty());
    }

    private void subscribe(
            String runId, StreamPosition start, FluxSink<ServerSentEvent<PersonalApiDtos.StreamEvent>> sink) {
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<PersonalAssistantApplication.StreamSubscription> subscription = new AtomicReference<>();
        Object emissionLock = new Object();
        long[] positions = {start.durableSequence(), start.transientSequence()};
        boolean[] subscriptionReady = {false};
        boolean[] outputActive = {false};
        String[] terminalStatus = {null};
        sink.onDispose(() -> close(subscription.getAndSet(null)));

        PersonalAssistantApplication.StreamSubscription created = application.subscribe(
                runId,
                new PersonalAssistantApplication.StreamCursor(start.durableSequence(), start.transientSequence()),
                event -> {
                    synchronized (emissionLock) {
                        if (finished.get()) return;
                        int sourceIndex = event.source() == PersonalAssistantApplication.StreamSource.DURABLE ? 0 : 1;
                        if (event.sequence() <= positions[sourceIndex]) return;
                        positions[sourceIndex] = event.sequence();
                        if ("answer.started".equals(event.type())) outputActive[0] = true;
                        if ("answer.committed".equals(event.type()) || "answer.failed".equals(event.type())) {
                            outputActive[0] = false;
                        }
                        if ("run.status".equals(event.type()) && terminal(event.value())) {
                            terminalStatus[0] = event.value();
                        }
                        StreamPosition current = new StreamPosition(positions[0], positions[1]);
                        String eventId = encode(runId, current);
                        PersonalApiDtos.StreamEvent mapped = withEventId(mapper.stream(event), eventId);
                        sink.next(ServerSentEvent.<PersonalApiDtos.StreamEvent>builder(mapped)
                                .id(eventId)
                                .event(event.type())
                                .build());
                        completeIfTerminal(
                                runId,
                                subscriptionReady[0],
                                outputActive[0],
                                terminalStatus[0],
                                positions,
                                finished,
                                sink);
                    }
                });
        subscription.set(created);
        synchronized (emissionLock) {
            subscriptionReady[0] = true;
            application.run(runId).filter(run -> terminal(run.status())).ifPresent(run -> {
                terminalStatus[0] = run.status();
            });
            completeIfTerminal(runId, true, outputActive[0], terminalStatus[0], positions, finished, sink);
            if (sink.isCancelled() || finished.get()) {
                close(subscription.getAndSet(null));
            }
        }
    }

    private void completeIfTerminal(
            String runId,
            boolean subscriptionReady,
            boolean outputActive,
            String terminalStatus,
            long[] positions,
            AtomicBoolean finished,
            FluxSink<ServerSentEvent<PersonalApiDtos.StreamEvent>> sink) {
        if (!subscriptionReady || outputActive || terminalStatus == null || !finished.compareAndSet(false, true)) {
            return;
        }
        Optional<PersonalAssistantApplication.RunView> run = application.run(runId);
        if (run.isEmpty()) {
            sink.error(new IllegalStateException("terminal run no longer exists"));
            return;
        }
        sink.next(finalEvent(run.orElseThrow(), new StreamPosition(positions[0], positions[1])));
        sink.complete();
    }

    private PersonalApiDtos.StreamEvent withEventId(PersonalApiDtos.StreamEvent event, String eventId) {
        return new PersonalApiDtos.StreamEvent(
                eventId,
                event.type(),
                event.runId(),
                event.occurredAt(),
                event.value(),
                event.activity(),
                event.source(),
                event.sequence());
    }

    private String encode(String runId, StreamPosition position) {
        String encodedRun =
                Base64.getUrlEncoder().withoutPadding().encodeToString(runId.getBytes(StandardCharsets.UTF_8));
        return String.join(
                ".",
                CURSOR_VERSION,
                Long.toString(position.durableSequence()),
                Long.toString(position.transientSequence()),
                processEpoch,
                encodedRun);
    }

    private StreamPosition decode(String expectedRunId, String value) {
        String[] parts = value.split("\\.", 5);
        if (parts.length != 5 || !CURSOR_VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("Last-Event-ID has an unsupported stream cursor");
        }
        try {
            long durable = Long.parseLong(parts[1]);
            long transientSequence = Long.parseLong(parts[2]);
            if (durable < 0 || transientSequence < 0) throw new NumberFormatException("negative");
            String runId = new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
            if (!expectedRunId.equals(runId)) {
                throw new IllegalArgumentException("Last-Event-ID belongs to another run");
            }
            return new StreamPosition(durable, processEpoch.equals(parts[3]) ? transientSequence : 0);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("Last-Event-ID")) {
                throw exception;
            }
            throw new IllegalArgumentException("Last-Event-ID is not a valid stream cursor", exception);
        }
    }

    private static boolean terminal(String status) {
        return Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT").contains(status);
    }

    private ServerSentEvent<PersonalApiDtos.StreamEvent> finalEvent(
            PersonalAssistantApplication.RunView run, StreamPosition position) {
        String eventId = encode(run.id(), position);
        var event = new PersonalApiDtos.StreamEvent(
                eventId,
                "run.final",
                run.id(),
                run.updatedAt(),
                run.status(),
                Optional.empty(),
                "snapshot",
                run.version());
        return ServerSentEvent.<PersonalApiDtos.StreamEvent>builder(event)
                .id(eventId)
                .event("run.final")
                .build();
    }

    private static void close(PersonalAssistantApplication.StreamSubscription subscription) {
        if (subscription != null) subscription.close();
    }

    private record StreamPosition(long durableSequence, long transientSequence) {}
}
