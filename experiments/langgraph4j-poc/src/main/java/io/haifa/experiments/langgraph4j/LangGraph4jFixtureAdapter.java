package io.haifa.experiments.langgraph4j;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphRunnerException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

final class LangGraph4jFixtureAdapter {
    private final FixtureDefinitionCompiler compiler = new FixtureDefinitionCompiler();

    void validate(FixtureDefinition definition) {
        compiler.validate(definition);
    }

    FixtureState runSequence() throws Exception {
        CompiledGraph<FixtureState> graph = new StateGraph<>(FixtureState::new)
                .addNode("prepare", node_async(state -> Map.of("prepared", true)))
                .addNode("answer", node_async(state -> Map.of("answer", "ok")))
                .addEdge(START, "prepare")
                .addEdge("prepare", "answer")
                .addEdge("answer", END)
                .compile();
        return graph.invoke(Map.of()).orElseThrow();
    }

    FixtureState runConditional(String route) throws Exception {
        CompiledGraph<FixtureState> graph = new StateGraph<>(FixtureState::new)
                .addNode("classify", node_async(state -> Map.of("route", route)))
                .addNode("approve", node_async(state -> Map.of("decision", "approved")))
                .addNode("reject", node_async(state -> Map.of("decision", "rejected")))
                .addEdge(START, "classify")
                .addConditionalEdges(
                        "classify",
                        edge_async(state -> state.<String>value("route").orElseThrow()),
                        Map.of("approve", "approve", "reject", "reject"))
                .addEdge("approve", END)
                .addEdge("reject", END)
                .compile();
        return graph.invoke(Map.of()).orElseThrow();
    }

    FixtureState runBoundedLoop() throws Exception {
        CompiledGraph<FixtureState> graph = new StateGraph<>(FixtureState::new)
                .addNode("inspect", node_async(state -> {
                    int iteration = state.<Integer>value("iteration").orElse(0) + 1;
                    return Map.of("iteration", iteration);
                }))
                .addNode("enrich", node_async(state -> Map.of("enriched", true)))
                .addEdge(START, "inspect")
                .addConditionalEdges(
                        "inspect",
                        edge_async(state ->
                                state.<Integer>value("iteration").orElseThrow() < 2 ? "again" : "done"),
                        Map.of("again", "enrich", "done", END))
                .addEdge("enrich", "inspect")
                .compile();
        return graph.invoke(Map.of()).orElseThrow();
    }

    FixtureState runFixedAllOf() throws Exception {
        Map<String, Channel<?>> schema = Map.of("branches", Channels.appender(ArrayList::new));
        CompiledGraph<FixtureState> graph = new StateGraph<>(schema, FixtureState::new)
                .addNode("branch_a", node_async(state -> Map.of("branches", "a")))
                .addNode("branch_b", node_async(state -> Map.of("branches", "b")))
                .addNode("join", node_async(state -> Map.of("joined", true)))
                .addEdge(START, "branch_a")
                .addEdge(START, "branch_b")
                .addEdge("branch_a", "join")
                .addEdge("branch_b", "join")
                .addEdge("join", END)
                .compile();
        return graph.invoke(Map.of()).orElseThrow();
    }

    Map<String, Object> deterministicJoin(List<Map<String, Object>> branchDeltas) {
        Map<String, Object> merged = new TreeMap<>();
        for (Map<String, Object> delta : branchDeltas.stream()
                .sorted((left, right) -> String.valueOf(left.get("branch"))
                        .compareTo(String.valueOf(right.get("branch"))))
                .toList()) {
            for (Map.Entry<String, Object> entry : new TreeMap<>(delta).entrySet()) {
                if (entry.getKey().equals("branch")) {
                    continue;
                }
                Object previous = merged.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null && !previous.equals(entry.getValue())) {
                    throw new IllegalStateException("WORKFLOW_STATE_CONFLICT: " + entry.getKey());
                }
            }
        }
        return Map.copyOf(merged);
    }

    FixtureState runInterruptedAndResumed() throws Exception {
        MemorySaver saver = new MemorySaver();
        CompiledGraph<FixtureState> graph = new StateGraph<>(FixtureState::new)
                .addNode("prepare", node_async(state -> Map.of("prepared", true)))
                .addNode("apply", node_async(state -> Map.of("applied", true)))
                .addEdge(START, "prepare")
                .addEdge("prepare", "apply")
                .addEdge("apply", END)
                .compile(CompileConfig.builder()
                        .checkpointSaver(saver)
                        .interruptBefore("apply")
                        .releaseThread(false)
                        .build());
        RunnableConfig config = RunnableConfig.builder().threadId("fixture-interrupt-1").build();
        FixtureState interrupted = graph.invoke(Map.of(), config).orElseThrow();
        if (interrupted.<Boolean>value("applied").orElse(false)) {
            throw new IllegalStateException("graph did not interrupt before apply");
        }
        return graph.invoke(GraphInput.resume(), config).orElseThrow();
    }

    FixtureState runAgentNode(FakeAgentNodeGateway gateway) throws Exception {
        CompiledGraph<FixtureState> graph = new StateGraph<>(FixtureState::new)
                .addNode("agent.answer", node_async(state -> gateway.startAgentRun("agent.answer", state)))
                .addEdge(START, "agent.answer")
                .addEdge("agent.answer", END)
                .compile();
        return graph.invoke(Map.of("questionRef", "q-1")).orElseThrow();
    }

    boolean cancelRunningGraph() throws Exception {
        CompletableFuture<Map<String, Object>> pending = new CompletableFuture<>();
        AsyncNodeAction<FixtureState> waitingNode = state -> pending;
        CompiledGraph<FixtureState> graph = new StateGraph<>(FixtureState::new)
                .addNode("wait", waitingNode)
                .addEdge(START, "wait")
                .addEdge("wait", END)
                .compile();
        var generator = graph.stream(GraphInput.noArgs(), RunnableConfig.builder().build());
        CompletableFuture<?> consumption = generator.forEachAsync(output -> {});
        boolean accepted = generator.cancel(true);
        consumption.handle((ignored, failure) -> null).get(2, TimeUnit.SECONDS);
        return accepted && generator.isCancelled();
    }

    FixtureFailure runFailingNode() throws Exception {
        CompiledGraph<FixtureState> graph = new StateGraph<>(FixtureState::new)
                .addNode("fail", node_async(state -> {
                    throw new IllegalStateException("provider detail must stay internal");
                }))
                .addEdge(START, "fail")
                .addEdge("fail", END)
                .compile();
        try {
            graph.invoke(Map.of());
        } catch (Exception failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof GraphRunnerException graphFailure) {
                    return new FixtureFailure(
                            "WORKFLOW_NODE_FAILED", graphFailure.nodeId().orElse("unknown"));
                }
                current = current.getCause();
            }
        }
        throw new IllegalStateException("expected provider graph failure");
    }
}
