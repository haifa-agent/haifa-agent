package io.haifa.agent.runtime.core.compaction;

import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates and adjusts compaction cutoff points across message turns to ensure
 * tool calls and their corresponding tool results are never severed.
 */
public final class CutoffPointValidator {

    private CutoffPointValidator() {}

    /**
     * Adjusts the proposed cutoff index so that neither side of the split has an incomplete
     * tool call / tool result pair.
     *
     * @param messages all candidate messages ordered chronologically
     * @param requestedCutoff the proposed split index where messages[0, requestedCutoff) are compacted
     *                        and messages[requestedCutoff, size) are retained in the active tail
     * @return a safe split index &lt;= requestedCutoff
     */
    public static int validateCutoff(List<AgentMessage> messages, int requestedCutoff) {
        if (messages == null || messages.isEmpty() || requestedCutoff <= 0) {
            return 0;
        }
        if (requestedCutoff >= messages.size()) {
            return messages.size();
        }

        int safeCutoff = requestedCutoff;

        while (safeCutoff > 0) {
            // If safeCutoff lands directly on a TOOL message, the retained tail would start with a tool result
            if (messages.get(safeCutoff).role() == MessageRole.TOOL) {
                safeCutoff--;
                continue;
            }

            // Collect all tool calls and tool results on the compacted side [0, safeCutoff)
            Set<ToolCallId> leftCalls = new HashSet<>();
            Set<ToolCallId> leftResults = new HashSet<>();
            for (int i = 0; i < safeCutoff; i++) {
                AgentMessage m = messages.get(i);
                for (var content : m.contents()) {
                    if (content instanceof ToolCallPart tcp) {
                        leftCalls.add(tcp.toolCallId());
                    } else if (content instanceof ToolResultPart trp) {
                        leftResults.add(trp.toolCallId());
                    }
                }
            }

            // Identify calls on the left that have no corresponding results on the left
            Set<ToolCallId> unclosedCalls = new HashSet<>(leftCalls);
            unclosedCalls.removeAll(leftResults);

            if (unclosedCalls.isEmpty()) {
                return safeCutoff;
            }

            // Find the earliest assistant message that initiated an unclosed tool call and roll back to before it
            int rollbackIndex = safeCutoff - 1;
            int earliestAssistant = -1;
            while (rollbackIndex >= 0) {
                AgentMessage m = messages.get(rollbackIndex);
                boolean containsUnclosed = false;
                for (var content : m.contents()) {
                    if (content instanceof ToolCallPart tcp && unclosedCalls.contains(tcp.toolCallId())) {
                        containsUnclosed = true;
                        break;
                    }
                }
                if (containsUnclosed) {
                    earliestAssistant = rollbackIndex;
                }
                rollbackIndex--;
            }

            if (earliestAssistant >= 0) {
                safeCutoff = earliestAssistant;
            } else {
                safeCutoff--;
            }
        }

        return 0;
    }
}
