package io.haifa.agent.runtime.core.decision;

import io.haifa.agent.runtime.core.delegation.DelegationTool;
import java.util.List;
import java.util.Objects;

/**
 * One model response that contains at least one delegation Tool Call.
 *
 * <p>{@code requests} keeps every Tool Call of the response in model order, so the assistant message and the
 * provider correlation IDs stay intact. Execution order is fixed: all delegation calls of the response run first
 * and in parallel, then the ordinary Tool Calls run sequentially in model order.
 */
public record DelegationDecision(List<ToolRequest> requests) implements AgentDecision {
    public DelegationDecision {
        requests = List.copyOf(Objects.requireNonNull(requests, "requests must not be null"));
        if (requests.stream().noneMatch(DelegationTool::isDelegation)) {
            throw new IllegalArgumentException("a delegation decision requires at least one delegation request");
        }
    }

    /** The delegation Tool Calls of this response, in model order. */
    public List<ToolRequest> delegations() {
        return requests.stream().filter(DelegationTool::isDelegation).toList();
    }

    /** The ordinary catalog Tool Calls of this response, in model order. */
    public List<ToolRequest> tools() {
        return requests.stream()
                .filter(request -> !DelegationTool.isDelegation(request))
                .toList();
    }
}
