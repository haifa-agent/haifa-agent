package io.haifa.agent.orchestration.langgraph4j;

import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;

/** LangGraph4j state is deliberately opaque and never crosses the Adapter boundary. */
final class ProviderWorkflowState extends AgentState {
    ProviderWorkflowState(Map<String, Object> data) {
        super(data);
    }
}
