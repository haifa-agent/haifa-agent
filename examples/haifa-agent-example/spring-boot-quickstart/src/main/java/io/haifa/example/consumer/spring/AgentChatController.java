package io.haifa.example.consumer.spring;

import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Exposes Haifa Agent via standard Spring MVC REST and Server-Sent Events (SSE) streaming endpoints.
 */
@RestController
@RequestMapping("/api/chat")
public final class AgentChatController {
    private final HaifaAgent agent;

    public AgentChatController(HaifaAgent agent) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
    }

    public record ChatRequest(String prompt, String sessionId) {}

    public record ChatResponse(String sessionId, String runId, String answer) {}

    /**
     * Standard blocking JSON endpoint supporting both initial prompts and multi-turn continuations.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) throws Exception {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            var existingSession = agent.conversations().find(new AgentSessionId(request.sessionId()));
            if (existingSession.isPresent()) {
                var current = existingSession.get();
                var continued = agent.conversations().submit(new SubmitConversationTurnCommand(
                        current.sessionId(),
                        current.revision(),
                        "web-turn-" + System.currentTimeMillis(),
                        request.prompt()));
                var runId = continued.activeRunId().orElseThrow();
                var result = agent.runs().await(runId);
                return ResponseEntity.ok(new ChatResponse(
                        current.sessionId().value(),
                        runId.value(),
                        result.output().orElse("")));
            }
        }

        var started = agent.conversations().start(new StartConversationCommand(
                "web-chat-" + System.currentTimeMillis(),
                agent.metadata().name(),
                request.prompt()));
        var runId = started.activeRunId().orElseThrow();
        var result = agent.runs().await(runId);
        return ResponseEntity.ok(new ChatResponse(
                started.sessionId().value(),
                runId.value(),
                result.output().orElse("")));
    }

    /**
     * Server-Sent Events (SSE) streaming endpoint pushing real-time token deltas to web clients.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam("prompt") String prompt) {
        SseEmitter emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() -> {
            try {
                var started = agent.conversations().start(new StartConversationCommand(
                        "web-stream-" + System.currentTimeMillis(),
                        agent.metadata().name(),
                        prompt));
                var runId = started.activeRunId().orElseThrow();

                try (var subscription = agent.runs().subscribeOutput(runId, RunOutputCursor.BEFORE_FIRST, event -> {
                    if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("delta")
                                    .data(event.textDelta()));
                        } catch (IOException error) {
                            emitter.completeWithError(error);
                        }
                    }
                })) {
                    var result = agent.runs().await(runId);
                    emitter.send(SseEmitter.event().name("complete").data(result.output().orElse("")));
                    emitter.complete();
                }
            } catch (Exception error) {
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }
}
