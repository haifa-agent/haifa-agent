package io.haifa.agent.runtime.core.context;

import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Single linear implementation of the Tool Call/Result atomic grouping contract. */
public final class AtomicMessageGroups {
    private AtomicMessageGroups() {}

    public record Result(List<List<AgentMessage>> groups, long candidateScans) {
        public Result {
            groups = groups.stream().map(List::copyOf).toList();
            if (candidateScans < 0) throw new IllegalArgumentException("candidateScans must not be negative");
        }
    }

    public static Result group(List<AgentMessage> source) {
        List<List<AgentMessage>> groups = new ArrayList<>();
        long candidateScans = 0L;
        int index = 0;
        while (index < source.size()) {
            AgentMessage message = source.get(index);
            Set<ToolCallId> calls = new HashSet<>();
            for (var content : message.contents()) {
                if (content instanceof ToolCallPart call) calls.add(call.toolCallId());
            }

            int end = index;
            if (!calls.isEmpty()) {
                Set<ToolCallId> results = new HashSet<>();
                for (int candidate = index + 1; candidate < source.size(); candidate++) {
                    candidateScans++;
                    for (var content : source.get(candidate).contents()) {
                        if (content instanceof ToolResultPart result && calls.contains(result.toolCallId())) {
                            results.add(result.toolCallId());
                            end = candidate;
                        }
                    }
                    if (results.containsAll(calls)) break;
                }
                if (!results.containsAll(calls)) {
                    index = end + 1;
                    continue;
                }
            }
            groups.add(List.copyOf(source.subList(index, end + 1)));
            index = end + 1;
        }
        return new Result(groups, candidateScans);
    }
}
