package io.haifa.agent.orchestration.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.orchestration.api.WorkflowCapability;
import io.haifa.agent.orchestration.api.WorkflowDefinition;
import io.haifa.agent.orchestration.api.WorkflowDefinitionId;
import io.haifa.agent.orchestration.api.WorkflowDefinitionVersion;
import io.haifa.agent.orchestration.api.WorkflowEdge;
import io.haifa.agent.orchestration.api.WorkflowErrorCode;
import io.haifa.agent.orchestration.api.WorkflowException;
import io.haifa.agent.orchestration.api.WorkflowLimits;
import io.haifa.agent.orchestration.api.WorkflowNodeDefinition;
import io.haifa.agent.orchestration.api.WorkflowNodeId;
import io.haifa.agent.orchestration.api.WorkflowNodeType;
import io.haifa.agent.orchestration.api.WorkflowStateMapping;
import io.haifa.agent.orchestration.api.WorkflowStateSchema;
import io.haifa.agent.orchestration.api.WorkflowSubgraphBinding;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultWorkflowDefinitionCompilerTest {
    private final DefaultWorkflowDefinitionCompiler compiler = new DefaultWorkflowDefinitionCompiler();

    @Test
    void digestDoesNotDependOnNodeOrEdgeDeclarationOrder() {
        WorkflowDefinition first = definition(
                List.of(
                        WorkflowNodeDefinition.action("a", "action:a"),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("a", "end")),
                Set.of(WorkflowCapability.SEQUENCE));
        WorkflowDefinition second = definition(
                List.of(
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL),
                        WorkflowNodeDefinition.action("a", "action:a")),
                List.of(WorkflowEdge.unconditional("a", "end")),
                Set.of(WorkflowCapability.SEQUENCE));

        assertThat(compiler.compile(first).reference().digest())
                .isEqualTo(compiler.compile(second).reference().digest());

        WorkflowDefinition nextVersion = new WorkflowDefinition(
                second.id(),
                new WorkflowDefinitionVersion(2),
                second.stateSchema(),
                second.entryNode(),
                second.nodes(),
                second.edges(),
                second.limits(),
                second.requiredCapabilities());
        assertThat(compiler.compile(nextVersion).reference().digest())
                .isNotEqualTo(compiler.compile(first).reference().digest());
    }

    @Test
    void rejectsEveryDeferredCapabilityFailClosed() {
        for (WorkflowCapability capability : List.of(WorkflowCapability.DYNAMIC_FAN_OUT, WorkflowCapability.ANY_OF)) {
            WorkflowDefinition definition = definition(
                    List.of(
                            WorkflowNodeDefinition.action("a", "action:a"),
                            WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                    List.of(WorkflowEdge.unconditional("a", "end")),
                    Set.of(capability));

            assertThatThrownBy(() -> compiler.compile(definition))
                    .isInstanceOfSatisfying(WorkflowException.class, exception -> assertThat(exception.code())
                            .isEqualTo(WorkflowErrorCode.UNSUPPORTED_CAPABILITY));
        }
    }

    @Test
    void compilesFrozenSubgraphAndRejectsMissingOrRecursiveDefinitions() {
        WorkflowDefinition child = definition(
                new WorkflowDefinitionId("child"),
                List.of(
                        WorkflowNodeDefinition.action("a", "child-action"),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("a", "end")),
                Set.of(WorkflowCapability.SEQUENCE));
        var childRef = compiler.compile(child).reference();
        WorkflowDefinition parent = definition(
                new WorkflowDefinitionId("parent"),
                List.of(
                        WorkflowNodeDefinition.subgraph(
                                "a",
                                new WorkflowSubgraphBinding(
                                        childRef,
                                        new WorkflowStateMapping(Map.of("value", "value"), Map.of("value", "value")))),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("a", "end")),
                Set.of(WorkflowCapability.SUBGRAPH));

        assertThat(compiler.compile(parent, List.of(child)).subgraphDefinitions())
                .containsKey(childRef);
        assertThatThrownBy(() -> compiler.compile(parent))
                .isInstanceOfSatisfying(WorkflowException.class, exception -> assertThat(exception.code())
                        .isEqualTo(WorkflowErrorCode.DEFINITION_NOT_FOUND));

        WorkflowDefinition self = definition(
                new WorkflowDefinitionId("self"),
                List.of(
                        WorkflowNodeDefinition.subgraph(
                                "a",
                                new WorkflowSubgraphBinding(
                                        new io.haifa.agent.orchestration.api.WorkflowDefinitionRef(
                                                new WorkflowDefinitionId("self"),
                                                new WorkflowDefinitionVersion(1),
                                                new io.haifa.agent.orchestration.api.WorkflowDefinitionDigest(
                                                        "0000000000000000000000000000000000000000000000000000000000000000")),
                                        new WorkflowStateMapping(Map.of("value", "value"), Map.of("value", "value")))),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("a", "end")),
                Set.of(WorkflowCapability.SUBGRAPH));
        assertThatThrownBy(() -> compiler.compile(self, List.of(self)))
                .isInstanceOfSatisfying(WorkflowException.class, exception -> assertThat(exception.code())
                        .isEqualTo(WorkflowErrorCode.DEFINITION_NOT_FOUND));
    }

    @Test
    void rejectsSubgraphSchemaDepthAndExpandedSizeViolations() {
        WorkflowDefinition leaf = definition(
                new WorkflowDefinitionId("leaf"),
                List.of(
                        WorkflowNodeDefinition.action("a", "leaf-action"),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("a", "end")),
                Set.of(WorkflowCapability.SEQUENCE));
        var leafRef = compiler.compile(leaf).reference();
        WorkflowDefinition middle = definition(
                new WorkflowDefinitionId("middle"),
                List.of(
                        WorkflowNodeDefinition.subgraph(
                                "a",
                                new WorkflowSubgraphBinding(
                                        leafRef,
                                        new WorkflowStateMapping(Map.of("value", "value"), Map.of("value", "value")))),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("a", "end")),
                Set.of(WorkflowCapability.SUBGRAPH));
        var middleRef = compiler.compile(middle, List.of(leaf)).reference();
        WorkflowDefinition root = new WorkflowDefinition(
                new WorkflowDefinitionId("root"),
                new WorkflowDefinitionVersion(1),
                middle.stateSchema(),
                new WorkflowNodeId("a"),
                List.of(
                        WorkflowNodeDefinition.subgraph(
                                "a",
                                new WorkflowSubgraphBinding(
                                        middleRef,
                                        new WorkflowStateMapping(Map.of("value", "value"), Map.of("value", "value")))),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("a", "end")),
                new WorkflowLimits(8, 2, 2, 1, 8),
                Set.of(WorkflowCapability.SUBGRAPH));

        assertThatThrownBy(() -> compiler.compile(root, List.of(middle, leaf)))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("depth");

        WorkflowDefinition tooSmall = new WorkflowDefinition(
                root.id(),
                root.version(),
                root.stateSchema(),
                root.entryNode(),
                root.nodes(),
                root.edges(),
                new WorkflowLimits(8, 2, 2, 4, 3),
                root.requiredCapabilities());
        assertThatThrownBy(() -> compiler.compile(tooSmall, List.of(middle, leaf)))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("expanded node");

        WorkflowDefinition badMapping = new WorkflowDefinition(
                root.id(),
                root.version(),
                root.stateSchema(),
                root.entryNode(),
                List.of(
                        WorkflowNodeDefinition.subgraph(
                                "a",
                                new WorkflowSubgraphBinding(
                                        leafRef,
                                        new WorkflowStateMapping(
                                                Map.of("missing", "value"), Map.of("value", "value")))),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                root.edges(),
                WorkflowLimits.defaults(),
                root.requiredCapabilities());
        assertThatThrownBy(() -> compiler.compile(badMapping, List.of(leaf)))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("mapping");
    }

    @Test
    void rejectsNestedInterruptingSubgraphInsideFixedBranch() {
        WorkflowDefinition waitingLeaf = definition(
                new WorkflowDefinitionId("waiting-leaf"),
                List.of(
                        WorkflowNodeDefinition.control("a", WorkflowNodeType.WAIT),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("a", "end")),
                Set.of(WorkflowCapability.INTERRUPTION));
        var leafRef = compiler.compile(waitingLeaf).reference();
        WorkflowDefinition middle = definition(
                new WorkflowDefinitionId("middle-wait"),
                List.of(
                        WorkflowNodeDefinition.subgraph(
                                "a",
                                new WorkflowSubgraphBinding(
                                        leafRef,
                                        new WorkflowStateMapping(Map.of("value", "value"), Map.of("value", "value")))),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("a", "end")),
                Set.of(WorkflowCapability.SUBGRAPH));
        var middleRef = compiler.compile(middle, List.of(waitingLeaf)).reference();
        WorkflowDefinition parent = definition(
                new WorkflowDefinitionId("parallel-parent"),
                List.of(
                        WorkflowNodeDefinition.control("a", WorkflowNodeType.FORK_ALL),
                        WorkflowNodeDefinition.subgraph(
                                "left",
                                new WorkflowSubgraphBinding(
                                        middleRef,
                                        new WorkflowStateMapping(Map.of("value", "value"), Map.of("value", "value")))),
                        WorkflowNodeDefinition.action("right", "right-action"),
                        WorkflowNodeDefinition.control("join", WorkflowNodeType.JOIN_ALL),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(
                        new WorkflowEdge(
                                new WorkflowNodeId("a"), new WorkflowNodeId("left"), java.util.Optional.empty(), 0),
                        new WorkflowEdge(
                                new WorkflowNodeId("a"), new WorkflowNodeId("right"), java.util.Optional.empty(), 1),
                        WorkflowEdge.unconditional("left", "join"),
                        WorkflowEdge.unconditional("right", "join"),
                        WorkflowEdge.unconditional("join", "end")),
                Set.of(WorkflowCapability.FIXED_ALL_OF, WorkflowCapability.SUBGRAPH));

        assertThatThrownBy(() -> compiler.compile(parent, List.of(middle, waitingLeaf)))
                .isInstanceOfSatisfying(WorkflowException.class, exception -> assertThat(exception.code())
                        .isEqualTo(WorkflowErrorCode.UNSUPPORTED_CAPABILITY))
                .hasMessageContaining("interrupting subgraph");
    }

    @Test
    void rejectsUnreachableNodes() {
        WorkflowDefinition definition = definition(
                List.of(
                        WorkflowNodeDefinition.action("a", "action:a"),
                        WorkflowNodeDefinition.action("orphan", "action:orphan"),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("a", "end"), WorkflowEdge.unconditional("orphan", "end")),
                Set.of(WorkflowCapability.SEQUENCE));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("unreachable");
    }

    private static WorkflowDefinition definition(
            List<WorkflowNodeDefinition> nodes, List<WorkflowEdge> edges, Set<WorkflowCapability> capabilities) {
        return definition(new WorkflowDefinitionId("fixture"), nodes, edges, capabilities);
    }

    private static WorkflowDefinition definition(
            WorkflowDefinitionId id,
            List<WorkflowNodeDefinition> nodes,
            List<WorkflowEdge> edges,
            Set<WorkflowCapability> capabilities) {
        return new WorkflowDefinition(
                id,
                new WorkflowDefinitionVersion(1),
                new WorkflowStateSchema("fixture", 1, Set.of("value"), 8, 4, 128),
                new WorkflowNodeId("a"),
                nodes,
                edges,
                WorkflowLimits.defaults(),
                capabilities);
    }
}
