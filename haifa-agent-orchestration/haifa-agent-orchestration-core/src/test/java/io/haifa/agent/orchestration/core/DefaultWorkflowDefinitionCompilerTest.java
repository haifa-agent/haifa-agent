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
import io.haifa.agent.orchestration.api.WorkflowStateSchema;
import java.util.List;
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
        for (WorkflowCapability capability :
                List.of(WorkflowCapability.SUBGRAPH, WorkflowCapability.DYNAMIC_FAN_OUT, WorkflowCapability.ANY_OF)) {
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
        return new WorkflowDefinition(
                new WorkflowDefinitionId("fixture"),
                new WorkflowDefinitionVersion(1),
                new WorkflowStateSchema("fixture", 1, Set.of("value"), 8, 4, 128),
                new WorkflowNodeId("a"),
                nodes,
                edges,
                WorkflowLimits.defaults(),
                capabilities);
    }
}
