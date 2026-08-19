package io.haifa.experiments.langgraph4j;

import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;

final class FixtureState extends AgentState {
    FixtureState(Map<String, Object> initialData) {
        super(initialData);
    }
}
