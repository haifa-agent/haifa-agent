package io.haifa.agent.personalassistant.server.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PersonalObservabilityTest {
    private static final String SECRET_PROMPT = "prompt-secret-must-not-be-logged";
    private static final String SECRET_RESPONSE = "response-secret-must-not-be-logged";
    private static final String SECRET_FAILURE = "failure-secret-must-not-be-logged";
    private static final String SECRET_COMMAND = "command-secret-must-not-be-logged";

    @Test
    void modelLogsContainOperationalMetadataButNotPayloadsOrFailureMessages() {
        LogCapture capture = attach(LoggingAgentChatModel.class);
        AgentChatRequest request = request();
        AgentChatResponse response = new AgentChatResponse(
                "response-1",
                "personal-test",
                SECRET_RESPONSE,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(12, 3),
                "",
                Map.of());

        try {
            new LoggingAgentChatModel(ignored -> response).invoke(request);
            assertThatThrownBy(() -> new LoggingAgentChatModel(ignored -> {
                                throw new ModelInvocationException(
                                        ModelErrorCategory.INVALID_REQUEST,
                                        false,
                                        400,
                                        "invalid_tool_schema",
                                        request.callId(),
                                        "model provider request failed with HTTP 400",
                                        null);
                            })
                            .invoke(request))
                    .isInstanceOf(ModelInvocationException.class);
            assertThatThrownBy(() -> new LoggingAgentChatModel(ignored -> {
                                throw new IllegalStateException(SECRET_FAILURE);
                            })
                            .invoke(request))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(formatted(capture))
                    .contains(
                            "event=model.call.started",
                            "event=model.call.completed",
                            "event=model.call.failed",
                            "run-log-1",
                            "call-log-1",
                            "inputTokens=12",
                            "outputTokens=3",
                            "category=INVALID_REQUEST",
                            "retryable=false",
                            "httpStatus=400",
                            "providerCode=invalid_tool_schema",
                            "safeMessage=model provider request failed with HTTP 400")
                    .doesNotContain(SECRET_PROMPT, SECRET_RESPONSE, SECRET_FAILURE);
        } finally {
            detach(capture);
        }
    }

    @Test
    void modelLoggingPreservesTheDelegatesStreamingImplementation() {
        AtomicBoolean streamingCalled = new AtomicBoolean();
        AgentChatResponse response = new AgentChatResponse(
                "response-1",
                "personal-test",
                SECRET_RESPONSE,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(12, 3),
                "",
                Map.of());
        var delegate = new io.haifa.agent.model.api.AgentChatModel() {
            @Override
            public AgentChatResponse invoke(AgentChatRequest request) {
                throw new AssertionError("synchronous invocation must not replace streaming");
            }

            @Override
            public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
                streamingCalled.set(true);
                return response;
            }
        };

        AgentChatResponse actual =
                new LoggingAgentChatModel(delegate).invokeStreaming(request(), ModelStreamSink.discarding());

        assertThat(actual).isSameAs(response);
        assertThat(streamingCalled).isTrue();
    }

    @Test
    void runLogsContainLifecycleMetadataButNotAnswerOrExecutionPayloads() {
        LogCapture capture = attach(PersonalRunLoggingService.class);
        PersonalAssistantApplication application = mock(PersonalAssistantApplication.class);
        AtomicReference<PersonalAssistantApplication.StreamListener> listener = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        when(application.subscribe(eq("run-log-1"), any(PersonalAssistantApplication.StreamListener.class)))
                .thenAnswer(invocation -> {
                    listener.set(invocation.getArgument(1));
                    return (PersonalAssistantApplication.StreamSubscription) () -> closed.set(true);
                });
        when(application.run("run-log-1")).thenReturn(Optional.empty());
        PersonalRunLoggingService service = new PersonalRunLoggingService(application);

        try {
            service.observe("conversation-log-1", "run-log-1", "test");
            listener.get()
                    .onEvent(new PersonalAssistantApplication.StreamEvent(
                            "event-1",
                            "answer.delta",
                            "run-log-1",
                            Instant.EPOCH,
                            SECRET_RESPONSE,
                            Optional.empty(),
                            PersonalAssistantApplication.StreamSource.TRANSIENT,
                            1));
            listener.get()
                    .onEvent(new PersonalAssistantApplication.StreamEvent(
                            "event-2",
                            "activity.committed",
                            "run-log-1",
                            Instant.EPOCH,
                            "SUCCEEDED",
                            Optional.of(new PersonalAssistantApplication.ActivityView(
                                    "activity-1",
                                    "event-2",
                                    Optional.empty(),
                                    "run-log-1",
                                    PersonalAssistantApplication.ActivityKind.TOOL,
                                    "execution_run",
                                    SECRET_COMMAND,
                                    "SUCCEEDED",
                                    Optional.empty(),
                                    Optional.of(Instant.EPOCH),
                                    Optional.of(Instant.EPOCH),
                                    Instant.EPOCH,
                                    SECRET_RESPONSE,
                                    Optional.empty(),
                                    2)),
                            PersonalAssistantApplication.StreamSource.DURABLE,
                            2));
            listener.get()
                    .onEvent(new PersonalAssistantApplication.StreamEvent(
                            "event-3",
                            "run.status",
                            "run-log-1",
                            Instant.EPOCH,
                            "COMPLETED",
                            Optional.empty(),
                            PersonalAssistantApplication.StreamSource.DURABLE,
                            3));

            assertThat(formatted(capture))
                    .contains(
                            "event=run.observation.started",
                            "event=activity.committed",
                            "capability=execution_run",
                            "event=run.status",
                            "status=COMPLETED")
                    .doesNotContain(SECRET_RESPONSE, SECRET_COMMAND);
            assertThat(closed).isTrue();
        } finally {
            service.close();
            detach(capture);
        }
    }

    @Test
    void activeRunRecoveryScanUsesTheSdkLimitAndCannotBreakStartup() {
        LogCapture capture = attach(PersonalRunLoggingService.class);
        PersonalAssistantApplication application = mock(PersonalAssistantApplication.class);
        when(application.conversations(Optional.empty(), java.util.Set.of("ACTIVE"), 100))
                .thenThrow(new IllegalStateException(SECRET_FAILURE));
        PersonalRunLoggingService service = new PersonalRunLoggingService(application);

        try {
            assertThatCode(service::recoverAndObserveActiveRuns).doesNotThrowAnyException();

            verify(application).conversations(Optional.empty(), java.util.Set.of("ACTIVE"), 100);
            assertThat(formatted(capture))
                    .contains("event=run.recovery-scan.failed", "failureType=java.lang.IllegalStateException")
                    .doesNotContain(SECRET_FAILURE);
        } finally {
            detach(capture);
        }
    }

    @Test
    void startupRecoversExecutingRunsButOnlyObservesWaitingRuns() {
        PersonalAssistantApplication application = mock(PersonalAssistantApplication.class);
        var executingConversation = mock(PersonalAssistantApplication.ConversationView.class);
        var waitingConversation = mock(PersonalAssistantApplication.ConversationView.class);
        var executingRun = mock(PersonalAssistantApplication.RunView.class);
        var waitingRun = mock(PersonalAssistantApplication.RunView.class);
        when(executingConversation.id()).thenReturn("conversation-running");
        when(executingConversation.activeRunId()).thenReturn(Optional.of("run-running"));
        when(waitingConversation.id()).thenReturn("conversation-waiting");
        when(waitingConversation.activeRunId()).thenReturn(Optional.of("run-waiting"));
        when(executingRun.status()).thenReturn("RUNNING");
        when(waitingRun.status()).thenReturn("WAITING_APPROVAL");
        when(application.conversations(Optional.empty(), java.util.Set.of("ACTIVE"), 100))
                .thenReturn(List.of(executingConversation, waitingConversation));
        when(application.run("run-running")).thenReturn(Optional.of(executingRun));
        when(application.run("run-waiting")).thenReturn(Optional.of(waitingRun));
        when(application.recover("run-running")).thenReturn(executingRun);
        when(application.subscribe(eq("run-running"), any(PersonalAssistantApplication.StreamListener.class)))
                .thenReturn(() -> {});
        when(application.subscribe(eq("run-waiting"), any(PersonalAssistantApplication.StreamListener.class)))
                .thenReturn(() -> {});
        PersonalRunLoggingService service = new PersonalRunLoggingService(application);

        try {
            service.recoverAndObserveActiveRuns();

            verify(application).recover("run-running");
            verify(application, never()).recover("run-waiting");
            verify(application).subscribe(eq("run-running"), any(PersonalAssistantApplication.StreamListener.class));
            verify(application).subscribe(eq("run-waiting"), any(PersonalAssistantApplication.StreamListener.class));
        } finally {
            service.close();
        }
    }

    private static AgentChatRequest request() {
        return new AgentChatRequest(
                new ModelCallId("call-log-1"),
                new AgentRunId("run-log-1"),
                1,
                1,
                mock(ResolvedModelSnapshot.class),
                List.of(ModelMessage.text(ModelMessageRole.USER, SECRET_PROMPT)),
                List.of(),
                256,
                Duration.ofSeconds(5),
                Map.of());
    }

    private static LogCapture attach(Class<?> owner) {
        var logger = (Logger) LoggerFactory.getLogger(owner);
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return new LogCapture(logger, originalLevel, appender);
    }

    private static void detach(LogCapture capture) {
        capture.logger().detachAppender(capture.appender());
        capture.appender().stop();
        capture.logger().setLevel(capture.originalLevel());
    }

    private static String formatted(LogCapture capture) {
        return capture.appender().list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private record LogCapture(Logger logger, Level originalLevel, ListAppender<ILoggingEvent> appender) {}
}
