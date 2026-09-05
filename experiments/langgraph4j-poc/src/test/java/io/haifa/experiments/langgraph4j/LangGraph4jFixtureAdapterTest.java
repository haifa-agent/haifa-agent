package io.haifa.experiments.langgraph4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LangGraph4jFixtureAdapterTest {
    private final LangGraph4jFixtureAdapter adapter = new LangGraph4jFixtureAdapter();

    @Test
    void sequenceUsesProviderWithoutLeakingProviderState() throws Exception {
        FixtureState result = adapter.runSequence();

        assertEquals(true, result.value("prepared").orElseThrow());
        assertEquals("ok", result.value("answer").orElseThrow());
    }

    @Test
    void conditionSelectsExactlyOneFrozenTarget() throws Exception {
        assertEquals("approved", adapter.runConditional("approve").value("decision").orElseThrow());
        assertEquals("rejected", adapter.runConditional("reject").value("decision").orElseThrow());
    }

    @Test
    void loopTerminatesAtTheFixtureBound() throws Exception {
        FixtureState result = adapter.runBoundedLoop();

        assertEquals(2, result.value("iteration").orElseThrow());
        assertEquals(true, result.value("enriched").orElseThrow());
    }

    @Test
    void fixedAllOfCompletesEveryBranch() throws Exception {
        FixtureState result = adapter.runFixedAllOf();

        assertEquals(List.of("a", "b"), result.<List<String>>value("branches").orElseThrow().stream().sorted().toList());
        assertEquals(true, result.value("joined").orElseThrow());
    }

    @Test
    void joinIsDeterministicAcrossPhysicalCompletionOrders() {
        Map<String, Object> branchA = Map.of("branch", "a", "aValue", 1);
        Map<String, Object> branchB = Map.of("branch", "b", "bValue", 2);

        assertEquals(
                adapter.deterministicJoin(List.of(branchA, branchB)),
                adapter.deterministicJoin(List.of(branchB, branchA)));
    }

    @Test
    void joinFailsClosedOnConflictingDeltas() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> adapter.deterministicJoin(List.of(
                        Map.of("branch", "a", "shared", 1), Map.of("branch", "b", "shared", 2))));

        assertTrue(error.getMessage().startsWith("WORKFLOW_STATE_CONFLICT"));
    }

    @Test
    void interruptionRequiresExplicitResume() throws Exception {
        FixtureState result = adapter.runInterruptedAndResumed();

        assertEquals(true, result.value("prepared").orElseThrow());
        assertEquals(true, result.value("applied").orElseThrow());
    }

    @Test
    void agentNodeCallsOnlyTheInjectedGateway() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        FixtureState result = adapter.runAgentNode((nodeId, state) -> {
            calls.incrementAndGet();
            assertEquals("agent.answer", nodeId);
            assertEquals("q-1", state.value("questionRef").orElseThrow());
            return Map.of("agentRunId", "agent-run-1", "answerRef", "artifact-answer-1");
        });

        assertEquals(1, calls.get());
        assertEquals("agent-run-1", result.value("agentRunId").orElseThrow());
    }

    @Test
    void cancellationIsObservableButDoesNotClaimExternalSideEffectCancellation() throws Exception {
        assertTrue(adapter.cancelRunningGraph());
    }

    @Test
    void providerFailureIsNormalizedWithoutExposingItsMessage() throws Exception {
        FixtureFailure failure = adapter.runFailingNode();

        assertEquals("WORKFLOW_NODE_FAILED", failure.code());
        assertEquals("fail", failure.nodeId());
    }

    @Test
    void unsupportedCapabilitiesFailBeforeProviderCompilation() {
        for (FixtureCapability capability : List.of(
                FixtureCapability.SUBGRAPH, FixtureCapability.DYNAMIC_FAN_OUT, FixtureCapability.ANY_OF)) {
            UnsupportedFixtureCapabilityException error = assertThrows(
                    UnsupportedFixtureCapabilityException.class,
                    () -> adapter.validate(new FixtureDefinition("unsupported", Set.of(capability))));
            assertEquals(capability, error.capability());
        }
    }

    @Test
    void supportedCapabilitiesPassTheHaifaBoundary() {
        assertFalse(Set.of(
                        FixtureCapability.SEQUENCE,
                        FixtureCapability.CONDITION,
                        FixtureCapability.BOUNDED_LOOP,
                        FixtureCapability.FIXED_ALL_OF,
                        FixtureCapability.INTERRUPTION)
                .isEmpty());
        adapter.validate(new FixtureDefinition(
                "supported",
                Set.of(
                        FixtureCapability.SEQUENCE,
                        FixtureCapability.CONDITION,
                        FixtureCapability.BOUNDED_LOOP,
                        FixtureCapability.FIXED_ALL_OF,
                        FixtureCapability.INTERRUPTION)));
    }
}
