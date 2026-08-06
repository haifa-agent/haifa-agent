package io.haifa.agent.personalassistant.server.web.v1.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.server.web.v1.mapper.PersonalApiMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PersonalRunStreamServiceTest {
    @Test
    void keepsDurableAndTransientSequencesIndependentAndClosesOnDisconnect() {
        PersonalAssistantApplication application = mock(PersonalAssistantApplication.class);
        AtomicReference<PersonalAssistantApplication.StreamListener> listener = new AtomicReference<>();
        AtomicReference<PersonalAssistantApplication.StreamCursor> cursor = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        when(application.run("run-1")).thenReturn(Optional.of(running("run-1")));
        when(application.initialStreamCursor("run-1")).thenReturn(new PersonalAssistantApplication.StreamCursor(0, 0));
        when(application.subscribe(
                        eq("run-1"),
                        any(PersonalAssistantApplication.StreamCursor.class),
                        any(PersonalAssistantApplication.StreamListener.class)))
                .thenAnswer(invocation -> {
                    cursor.set(invocation.getArgument(1));
                    listener.set(invocation.getArgument(2));
                    return (PersonalAssistantApplication.StreamSubscription) () -> closed.set(true);
                });
        PersonalRunStreamService service = new PersonalRunStreamService(application, new PersonalApiMapper());
        List<
                        org.springframework.http.codec.ServerSentEvent<
                                io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos.StreamEvent>>
                received = new CopyOnWriteArrayList<>();
        var disposable = service.open("run-1").subscribe(received::add);

        listener.get()
                .onEvent(new PersonalAssistantApplication.StreamEvent(
                        "transient-1",
                        "answer.delta",
                        "run-1",
                        Instant.EPOCH,
                        "hello",
                        Optional.empty(),
                        PersonalAssistantApplication.StreamSource.TRANSIENT,
                        1));
        listener.get()
                .onEvent(new PersonalAssistantApplication.StreamEvent(
                        "durable-1",
                        "run.status",
                        "run-1",
                        Instant.EPOCH,
                        "RUNNING",
                        Optional.empty(),
                        PersonalAssistantApplication.StreamSource.DURABLE,
                        1));

        assertThat(cursor.get()).isEqualTo(new PersonalAssistantApplication.StreamCursor(0, 0));
        assertThat(received).hasSize(2);
        assertThat(received).extracting(event -> event.data().source()).containsExactly("transient", "durable");
        assertThat(received).extracting(event -> event.data().sequence()).containsExactly(1L, 1L);
        assertThat(received.get(0).id()).isNotEqualTo(received.get(1).id());
        assertThat(received.get(0).id()).contains(".0.1.");
        assertThat(received.get(1).id()).contains(".1.1.");

        disposable.dispose();

        assertThat(closed).isTrue();
    }

    @Test
    void reconnectResumesBothSourceCursorsFromTheCompositeSseId() {
        PersonalAssistantApplication application = mock(PersonalAssistantApplication.class);
        AtomicReference<PersonalAssistantApplication.StreamListener> listener = new AtomicReference<>();
        List<PersonalAssistantApplication.StreamCursor> cursors = new CopyOnWriteArrayList<>();
        when(application.run("run-2")).thenReturn(Optional.of(running("run-2")));
        when(application.initialStreamCursor("run-2")).thenReturn(new PersonalAssistantApplication.StreamCursor(0, 0));
        when(application.subscribe(
                        eq("run-2"),
                        any(PersonalAssistantApplication.StreamCursor.class),
                        any(PersonalAssistantApplication.StreamListener.class)))
                .thenAnswer(invocation -> {
                    cursors.add(invocation.getArgument(1));
                    listener.set(invocation.getArgument(2));
                    return (PersonalAssistantApplication.StreamSubscription) () -> {};
                });
        PersonalRunStreamService service = new PersonalRunStreamService(application, new PersonalApiMapper());
        List<
                        org.springframework.http.codec.ServerSentEvent<
                                io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos.StreamEvent>>
                first = new CopyOnWriteArrayList<>();
        var firstConnection = service.open("run-2").subscribe(first::add);
        listener.get()
                .onEvent(new PersonalAssistantApplication.StreamEvent(
                        "transient-4",
                        "answer.delta",
                        "run-2",
                        Instant.EPOCH,
                        "late",
                        Optional.empty(),
                        PersonalAssistantApplication.StreamSource.TRANSIENT,
                        4));
        String lastEventId = first.getFirst().id();
        firstConnection.dispose();

        var secondConnection = service.open("run-2", Optional.of(lastEventId)).subscribe(ignored -> {});

        assertThat(cursors)
                .containsExactly(
                        new PersonalAssistantApplication.StreamCursor(0, 0),
                        new PersonalAssistantApplication.StreamCursor(0, 4));
        secondConnection.dispose();
    }

    @Test
    void terminalRunStatusWaitsForTransientCommitBeforeClosingTheStream() {
        PersonalAssistantApplication application = mock(PersonalAssistantApplication.class);
        AtomicReference<PersonalAssistantApplication.StreamListener> listener = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        when(application.run("run-3"))
                .thenReturn(
                        Optional.of(running("run-3")), Optional.of(running("run-3")), Optional.of(completed("run-3")));
        when(application.initialStreamCursor("run-3")).thenReturn(new PersonalAssistantApplication.StreamCursor(0, 0));
        when(application.subscribe(
                        eq("run-3"),
                        any(PersonalAssistantApplication.StreamCursor.class),
                        any(PersonalAssistantApplication.StreamListener.class)))
                .thenAnswer(invocation -> {
                    listener.set(invocation.getArgument(2));
                    return (PersonalAssistantApplication.StreamSubscription) () -> closed.set(true);
                });
        PersonalRunStreamService service = new PersonalRunStreamService(application, new PersonalApiMapper());
        List<String> eventTypes = new CopyOnWriteArrayList<>();
        service.open("run-3").subscribe(event -> eventTypes.add(event.event()));

        listener.get()
                .onEvent(new PersonalAssistantApplication.StreamEvent(
                        "transient-1",
                        "answer.started",
                        "run-3",
                        Instant.EPOCH,
                        "generation-1",
                        Optional.empty(),
                        PersonalAssistantApplication.StreamSource.TRANSIENT,
                        1));
        listener.get()
                .onEvent(new PersonalAssistantApplication.StreamEvent(
                        "durable-1",
                        "run.status",
                        "run-3",
                        Instant.EPOCH,
                        "COMPLETED",
                        Optional.empty(),
                        PersonalAssistantApplication.StreamSource.DURABLE,
                        1));

        assertThat(eventTypes).containsExactly("answer.started", "run.status");
        assertThat(closed).isFalse();

        listener.get()
                .onEvent(new PersonalAssistantApplication.StreamEvent(
                        "transient-2",
                        "answer.committed",
                        "run-3",
                        Instant.EPOCH,
                        "generation-1",
                        Optional.empty(),
                        PersonalAssistantApplication.StreamSource.TRANSIENT,
                        2));

        assertThat(eventTypes).containsExactly("answer.started", "run.status", "answer.committed", "run.final");
        assertThat(closed).isTrue();
    }

    private static PersonalAssistantApplication.RunView running(String runId) {
        return new PersonalAssistantApplication.RunView(
                runId,
                "conversation-1",
                "RUNNING",
                1,
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new PersonalAssistantApplication.UsageView(0, 0, 0, 0, 0, 0));
    }

    private static PersonalAssistantApplication.RunView completed(String runId) {
        return new PersonalAssistantApplication.RunView(
                runId,
                "conversation-1",
                "COMPLETED",
                2,
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new PersonalAssistantApplication.UsageView(0, 0, 0, 0, 0, 0));
    }
}
