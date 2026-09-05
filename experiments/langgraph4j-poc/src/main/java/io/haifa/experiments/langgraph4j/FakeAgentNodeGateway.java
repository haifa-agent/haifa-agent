package io.haifa.experiments.langgraph4j;

import java.util.Map;

@FunctionalInterface
interface FakeAgentNodeGateway {
    Map<String, Object> startAgentRun(String nodeId, FixtureState state);
}
