package io.haifa.agent.personalassistant.server.web.v1.sse;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import io.haifa.agent.personalassistant.server.web.v1.mapper.PersonalApiMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/** Active-run WebFlux SSE projection with cancellation cleanup and non-blocking heartbeat. */
@Service
public final class PersonalRunStreamService {
    private final PersonalAssistantApplication application;
    private final PersonalApiMapper mapper;

    public PersonalRunStreamService(PersonalAssistantApplication application, PersonalApiMapper mapper) {
        this.application = application;
        this.mapper = mapper;
    }

    public Flux<ServerSentEvent<PersonalApiDtos.StreamEvent>> open(String runId) {
        PersonalAssistantApplication.RunView initial =
                application.run(runId).orElseThrow(() -> new IllegalArgumentException("run does not exist"));
        if (terminal(initial.status())) return Flux.just(finalEvent(initial));
        Flux<ServerSentEvent<PersonalApiDtos.StreamEvent>> committed = Flux.create(
                sink -> {
                    AtomicBoolean finished = new AtomicBoolean();
                    AtomicReference<PersonalAssistantApplication.StreamSubscription> subscription =
                            new AtomicReference<>();
                    sink.onDispose(() -> {
                        var current = subscription.getAndSet(null);
                        if (current != null) current.close();
                    });
                    subscription.set(application.subscribe(runId, event -> {
                        if (finished.get()) return;
                        sink.next(ServerSentEvent.<PersonalApiDtos.StreamEvent>builder(mapper.stream(event))
                                .id(Long.toString(event.sequence()))
                                .event(event.type())
                                .build());
                        if ("run.status".equals(event.type())
                                && terminal(event.value())
                                && finished.compareAndSet(false, true)) {
                            sink.next(ServerSentEvent.<PersonalApiDtos.StreamEvent>builder(mapper.stream(event))
                                    .id(Long.toString(event.sequence()))
                                    .event("run.final")
                                    .build());
                            sink.complete();
                        }
                    }));
                    application.run(runId).filter(run -> terminal(run.status())).ifPresent(run -> {
                        if (finished.compareAndSet(false, true)) {
                            sink.next(finalEvent(run));
                            sink.complete();
                        }
                    });
                },
                FluxSink.OverflowStrategy.ERROR);
        Flux<ServerSentEvent<PersonalApiDtos.StreamEvent>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(ignored -> ServerSentEvent.<PersonalApiDtos.StreamEvent>builder()
                        .comment("heartbeat")
                        .build());
        return committed
                .publish(shared -> Flux.merge(shared, heartbeat.takeUntilOther(shared.ignoreElements())))
                .take(Duration.ofMinutes(5));
    }

    private static boolean terminal(String status) {
        return Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT").contains(status);
    }

    private static ServerSentEvent<PersonalApiDtos.StreamEvent> finalEvent(PersonalAssistantApplication.RunView run) {
        var event = new PersonalApiDtos.StreamEvent(
                "snapshot-final-" + run.id(),
                "run.final",
                run.id(),
                run.updatedAt(),
                run.status(),
                Optional.empty(),
                run.version());
        return ServerSentEvent.<PersonalApiDtos.StreamEvent>builder(event)
                .event("run.final")
                .build();
    }
}
