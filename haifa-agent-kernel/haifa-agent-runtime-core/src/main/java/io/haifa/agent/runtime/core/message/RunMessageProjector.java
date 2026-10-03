package io.haifa.agent.runtime.core.message;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.api.RunMessageCursor;
import io.haifa.agent.runtime.api.RunMessagePage;
import io.haifa.agent.runtime.api.RunMessageView;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.tool.ToolCallViewProjector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Safe projection of a bounded authoritative message page, without loading the Run history. */
public final class RunMessageProjector {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(RunMessageProjector.class);

    private RunMessageProjector() {}

    public static RunMessagePage page(RuntimeStateRepository state, AgentRunId runId, long after, int limit) {
        long head = state.runMessageHead(runId).orElse(0);
        if (after > head) throw new IllegalArgumentException("message cursor is ahead of the Run");
        List<AgentMessage> window = state.runMessagesAfter(runId, after, head, limit + 1);
        boolean more = window.size() > limit;
        List<AgentMessage> messages = more ? window.subList(0, limit) : window;
        long index = state.runMessageCountThrough(runId, after);
        Set<ToolCallId> ids = messages.stream()
                .flatMap(message -> message.contents().stream())
                .flatMap(part -> part instanceof ToolCallPart call
                        ? java.util.stream.Stream.of(call.toolCallId())
                        : part instanceof ToolResultPart result
                                ? java.util.stream.Stream.of(result.toolCallId())
                                : java.util.stream.Stream.empty())
                .collect(Collectors.toSet());
        Map<ToolCallId, ToolCall> calls =
                state.toolCallsByIds(ids).stream().collect(Collectors.toMap(ToolCall::id, call -> call));
        List<RunMessageView> views = new ArrayList<>();
        for (AgentMessage message : messages) {
            try {
                views.add(project(message, ++index, calls, runId));
            } catch (RuntimeException invalid) {
                LOGGER.warn(
                        "event=runtime.message-projection.failure runId={} messageId={} failureType={}",
                        runId.value(),
                        message.id().value(),
                        invalid.getClass().getSimpleName());
                throw invalid;
            }
        }
        long next = messages.isEmpty() ? after : messages.getLast().sequence();
        return new RunMessagePage(views, new RunMessageCursor(runId, next), new RunMessageCursor(runId, head), more);
    }

    private static RunMessageView project(
            AgentMessage message, long index, Map<ToolCallId, ToolCall> calls, AgentRunId runId) {
        List<String> texts = new ArrayList<>();
        List<io.haifa.agent.runtime.api.ToolCallView> tools = new ArrayList<>();
        Map<String, String> correlations = new LinkedHashMap<>();
        boolean truncated = false;
        if (message.status() == MessageStatus.REDACTED) {
            texts.add("[REDACTED]");
        } else
            for (var part : message.contents()) {
                if (part instanceof TextPart text
                        && message.visibility() == io.haifa.agent.core.message.MessageVisibility.USER_VISIBLE)
                    texts.add(ToolCallViewProjector.redactText(text.text()));
                else if (part instanceof ToolCallPart ref) {
                    ToolCall call = requireCall(
                            calls, ref.toolCallId(), ref.providerCorrelationId().value(), runId);
                    if (!call.toolName().equals(ref.toolName())
                            || !call.toolVersion().equals(ref.toolVersion()))
                        throw new IllegalStateException("message Tool reference does not match authoritative call");
                    tools.add(ToolCallViewProjector.project(call));
                    correlations.put(
                            call.id().value(), ref.providerCorrelationId().value());
                } else if (part instanceof ToolResultPart ref) {
                    ToolCall call = requireCall(
                            calls, ref.toolCallId(), ref.providerCorrelationId().value(), runId);
                    tools.add(ToolCallViewProjector.project(call));
                    correlations.put(
                            call.id().value(), ref.providerCorrelationId().value());
                    texts.add(ToolCallViewProjector.redactText(
                            call.result().map(result -> result.summary()).orElse(ref.summary())));
                    truncated |= call.result().map(result -> result.truncated()).orElse(false);
                }
            }
        return new RunMessageView(
                message.id().value(),
                message.sequence(),
                index,
                message.role().name(),
                String.join("\n", texts),
                truncated,
                tools,
                correlations,
                message.createdAt().toEpochMilli());
    }

    private static ToolCall requireCall(
            Map<ToolCallId, ToolCall> calls, ToolCallId id, String correlation, AgentRunId runId) {
        ToolCall call = calls.get(id);
        if (call == null
                || !call.runId().equals(runId)
                || !call.providerCorrelationId().value().equals(correlation))
            throw new IllegalStateException("message Tool reference is unavailable or invalid");
        return call;
    }
}
