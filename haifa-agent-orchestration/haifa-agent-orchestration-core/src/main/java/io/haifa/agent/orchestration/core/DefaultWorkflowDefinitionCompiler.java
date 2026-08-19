package io.haifa.agent.orchestration.core;

import io.haifa.agent.orchestration.api.CompiledWorkflowDefinition;
import io.haifa.agent.orchestration.api.WorkflowCapability;
import io.haifa.agent.orchestration.api.WorkflowDefinition;
import io.haifa.agent.orchestration.api.WorkflowDefinitionCompiler;
import io.haifa.agent.orchestration.api.WorkflowDefinitionDigest;
import io.haifa.agent.orchestration.api.WorkflowDefinitionRef;
import io.haifa.agent.orchestration.api.WorkflowEdge;
import io.haifa.agent.orchestration.api.WorkflowErrorCode;
import io.haifa.agent.orchestration.api.WorkflowException;
import io.haifa.agent.orchestration.api.WorkflowNodeDefinition;
import io.haifa.agent.orchestration.api.WorkflowNodeId;
import io.haifa.agent.orchestration.api.WorkflowNodeType;
import io.haifa.agent.orchestration.api.WorkflowStateMapping;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates the M1 graph subset and creates a stable content-addressed definition. */
public final class DefaultWorkflowDefinitionCompiler implements WorkflowDefinitionCompiler {
    public static final Set<WorkflowCapability> SUPPORTED_CAPABILITIES = Set.copyOf(EnumSet.of(
            WorkflowCapability.SEQUENCE,
            WorkflowCapability.CONDITION,
            WorkflowCapability.BOUNDED_LOOP,
            WorkflowCapability.FIXED_ALL_OF,
            WorkflowCapability.INTERRUPTION,
            WorkflowCapability.SUBGRAPH));

    @Override
    public CompiledWorkflowDefinition compile(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        validate(definition);
        rejectUnresolvedSubgraphs(definition);
        WorkflowDefinitionDigest digest = digest(definition);
        return new CompiledWorkflowDefinition(
                new WorkflowDefinitionRef(definition.id(), definition.version(), digest),
                definition,
                definition.requiredCapabilities());
    }

    /** Compiles one root and its complete, statically frozen subgraph definition set. */
    public CompiledWorkflowDefinition compile(WorkflowDefinition root, List<WorkflowDefinition> subgraphDefinitions) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(subgraphDefinitions, "subgraphDefinitions must not be null");
        Map<WorkflowDefinitionRef, WorkflowDefinition> catalog = new HashMap<>();
        for (WorkflowDefinition definition : subgraphDefinitions) {
            Objects.requireNonNull(definition, "subgraph definition must not be null");
            WorkflowDefinitionRef reference = reference(definition);
            if (catalog.put(reference, definition) != null) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "duplicate subgraph definition reference");
            }
        }
        validate(root);
        validateSubgraphs(
                root,
                catalog,
                new ArrayDeque<>(),
                0,
                new int[] {root.nodes().size(), branchCount(root)},
                root.limits());
        return new CompiledWorkflowDefinition(
                reference(root), root, root.requiredCapabilities(), reachableSubgraphs(root, catalog));
    }

    private static Map<WorkflowDefinitionRef, WorkflowDefinition> reachableSubgraphs(
            WorkflowDefinition root, Map<WorkflowDefinitionRef, WorkflowDefinition> catalog) {
        Map<WorkflowDefinitionRef, WorkflowDefinition> reachable = new HashMap<>();
        ArrayDeque<WorkflowDefinition> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            WorkflowDefinition current = pending.remove();
            current.nodes().stream()
                    .filter(node -> node.type() == WorkflowNodeType.SUBGRAPH)
                    .map(node -> node.subgraphBinding().orElseThrow().definition())
                    .forEach(reference -> {
                        WorkflowDefinition child = catalog.get(reference);
                        if (child != null && reachable.putIfAbsent(reference, child) == null) {
                            pending.add(child);
                        }
                    });
        }
        return Map.copyOf(reachable);
    }

    private static void validateSubgraphs(
            WorkflowDefinition definition,
            Map<WorkflowDefinitionRef, WorkflowDefinition> catalog,
            ArrayDeque<WorkflowDefinitionRef> path,
            int depth,
            int[] expandedNodes,
            io.haifa.agent.orchestration.api.WorkflowLimits rootLimits) {
        WorkflowDefinitionRef current = reference(definition);
        if (path.contains(current)) {
            throw failure(WorkflowErrorCode.INVALID_DEFINITION, "recursive subgraph reference is not allowed");
        }
        path.addLast(current);
        for (WorkflowNodeDefinition node : definition.nodes()) {
            if (node.type() != WorkflowNodeType.SUBGRAPH) continue;
            if (!definition.requiredCapabilities().contains(WorkflowCapability.SUBGRAPH)) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "SUBGRAPH node requires SUBGRAPH capability");
            }
            var binding = node.subgraphBinding().orElseThrow();
            WorkflowDefinition child = catalog.get(binding.definition());
            if (child == null) {
                throw failure(WorkflowErrorCode.DEFINITION_NOT_FOUND, "subgraph definition was not registered");
            }
            validate(child);
            validateMapping(definition, child, binding.stateMapping());
            if (isFixedBranchNode(definition, node.id()) && mayInterrupt(child, catalog, new HashSet<>())) {
                throw failure(
                        WorkflowErrorCode.UNSUPPORTED_CAPABILITY,
                        "interrupting subgraph inside fixed parallel branch is not supported");
            }
            int childDepth = depth + 1;
            if (childDepth > rootLimits.maximumSubgraphDepth()) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "maximum subgraph depth exceeded");
            }
            expandedNodes[0] = Math.addExact(expandedNodes[0], child.nodes().size());
            if (expandedNodes[0] > rootLimits.maximumExpandedNodes()) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "maximum expanded node count exceeded");
            }
            expandedNodes[1] = Math.addExact(expandedNodes[1], branchCount(child));
            if (expandedNodes[1] > rootLimits.maximumExpandedBranches()) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "maximum expanded branch count exceeded");
            }
            validateSubgraphs(child, catalog, path, childDepth, expandedNodes, rootLimits);
        }
        path.removeLast();
    }

    private static boolean mayInterrupt(
            WorkflowDefinition definition,
            Map<WorkflowDefinitionRef, WorkflowDefinition> catalog,
            Set<WorkflowDefinitionRef> visited) {
        WorkflowDefinitionRef reference = reference(definition);
        if (!visited.add(reference)) return false;
        if (definition.nodes().stream().anyMatch(node -> node.type() == WorkflowNodeType.WAIT)) return true;
        return definition.nodes().stream()
                .filter(node -> node.type() == WorkflowNodeType.SUBGRAPH)
                .map(node -> catalog.get(node.subgraphBinding().orElseThrow().definition()))
                .filter(Objects::nonNull)
                .anyMatch(child -> mayInterrupt(child, catalog, visited));
    }

    private static int branchCount(WorkflowDefinition definition) {
        Set<WorkflowNodeId> forks = definition.nodes().stream()
                .filter(node -> node.type() == WorkflowNodeType.FORK_ALL)
                .map(WorkflowNodeDefinition::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return Math.toIntExact(definition.edges().stream()
                .filter(edge -> forks.contains(edge.source()))
                .count());
    }

    private static boolean isFixedBranchNode(WorkflowDefinition definition, WorkflowNodeId target) {
        Map<WorkflowNodeId, WorkflowNodeDefinition> nodes = new HashMap<>();
        definition.nodes().forEach(node -> nodes.put(node.id(), node));
        Map<WorkflowNodeId, List<WorkflowEdge>> outgoing = new HashMap<>();
        definition.edges().forEach(edge -> outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>())
                .add(edge));
        return definition.nodes().stream()
                .filter(node -> node.type() == WorkflowNodeType.FORK_ALL)
                .flatMap(fork -> outgoing.getOrDefault(fork.id(), List.of()).stream())
                .anyMatch(edge -> branchContains(edge.target(), target, nodes, outgoing, new HashSet<>()));
    }

    private static boolean branchContains(
            WorkflowNodeId current,
            WorkflowNodeId target,
            Map<WorkflowNodeId, WorkflowNodeDefinition> nodes,
            Map<WorkflowNodeId, List<WorkflowEdge>> outgoing,
            Set<WorkflowNodeId> visited) {
        if (current.equals(target)) return true;
        if (!visited.add(current) || nodes.get(current).type() == WorkflowNodeType.JOIN_ALL) return false;
        return outgoing.getOrDefault(current, List.of()).stream()
                .anyMatch(edge -> branchContains(edge.target(), target, nodes, outgoing, visited));
    }

    private static void validateMapping(
            WorkflowDefinition parent, WorkflowDefinition child, WorkflowStateMapping mapping) {
        if (!parent.stateSchema().allowedKeys().containsAll(mapping.inputs().keySet())
                || !child.stateSchema()
                        .allowedKeys()
                        .containsAll(mapping.inputs().values())
                || !child.stateSchema()
                        .allowedKeys()
                        .containsAll(mapping.outputs().keySet())
                || !parent.stateSchema()
                        .allowedKeys()
                        .containsAll(mapping.outputs().values())) {
            throw failure(WorkflowErrorCode.INVALID_DEFINITION, "subgraph state mapping is incompatible with schema");
        }
    }

    private static void rejectUnresolvedSubgraphs(WorkflowDefinition definition) {
        if (definition.nodes().stream().anyMatch(node -> node.type() == WorkflowNodeType.SUBGRAPH)) {
            throw failure(
                    WorkflowErrorCode.DEFINITION_NOT_FOUND,
                    "subgraph compilation requires the complete frozen definition set");
        }
    }

    private static void validate(WorkflowDefinition definition) {
        Set<WorkflowCapability> unsupported = definition.requiredCapabilities().isEmpty()
                ? EnumSet.noneOf(WorkflowCapability.class)
                : EnumSet.copyOf(definition.requiredCapabilities());
        unsupported.removeAll(SUPPORTED_CAPABILITIES);
        if (!unsupported.isEmpty()) {
            throw failure(
                    WorkflowErrorCode.UNSUPPORTED_CAPABILITY, "unsupported workflow capabilities: " + unsupported);
        }
        if (definition.nodes().isEmpty()
                || definition.nodes().size() > definition.limits().maximumNodes()) {
            throw failure(WorkflowErrorCode.INVALID_DEFINITION, "workflow node count is invalid");
        }
        Map<WorkflowNodeId, WorkflowNodeDefinition> nodes = new HashMap<>();
        for (WorkflowNodeDefinition node : definition.nodes()) {
            if (nodes.put(node.id(), node) != null) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "duplicate workflow node id");
            }
        }
        if (!nodes.containsKey(definition.entryNode())) {
            throw failure(WorkflowErrorCode.INVALID_DEFINITION, "entry node does not exist");
        }
        long terminalCount = definition.nodes().stream()
                .filter(node -> node.type() == WorkflowNodeType.TERMINAL)
                .count();
        if (terminalCount != 1) {
            throw failure(WorkflowErrorCode.INVALID_DEFINITION, "workflow must have exactly one terminal node");
        }
        Map<WorkflowNodeId, List<WorkflowEdge>> outgoing = new HashMap<>();
        for (WorkflowEdge edge : definition.edges()) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "edge references an unknown node");
            }
            outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>())
                    .add(edge);
        }
        validateNodeShapes(definition, nodes, outgoing);
        validateFixedForks(definition, nodes, outgoing);
        assertAllNodesReachable(definition.entryNode(), nodes.keySet(), outgoing);
        if (hasCycle(definition.entryNode(), outgoing, new HashSet<>(), new HashSet<>())
                && !definition.requiredCapabilities().contains(WorkflowCapability.BOUNDED_LOOP)) {
            throw failure(WorkflowErrorCode.INVALID_DEFINITION, "cyclic workflow must declare BOUNDED_LOOP capability");
        }
    }

    private static void validateFixedForks(
            WorkflowDefinition definition,
            Map<WorkflowNodeId, WorkflowNodeDefinition> nodes,
            Map<WorkflowNodeId, List<WorkflowEdge>> outgoing) {
        definition.nodes().stream()
                .filter(node -> node.type() == WorkflowNodeType.FORK_ALL)
                .forEach(fork -> {
                    Set<WorkflowNodeId> joins = new HashSet<>();
                    outgoing.getOrDefault(fork.id(), List.of()).stream()
                            .map(WorkflowEdge::target)
                            .map(target -> resolveFixedBranchJoin(target, nodes, outgoing, new HashSet<>()))
                            .forEach(joins::add);
                    if (joins.size() != 1) {
                        throw failure(
                                WorkflowErrorCode.INVALID_DEFINITION,
                                "fixed branches must converge on one JOIN_ALL node");
                    }
                });
    }

    private static WorkflowNodeId resolveFixedBranchJoin(
            WorkflowNodeId current,
            Map<WorkflowNodeId, WorkflowNodeDefinition> nodes,
            Map<WorkflowNodeId, List<WorkflowEdge>> outgoing,
            Set<WorkflowNodeId> visited) {
        if (!visited.add(current)) {
            throw failure(
                    WorkflowErrorCode.INVALID_DEFINITION, "fixed branch must not contain a cycle before JOIN_ALL");
        }
        WorkflowNodeDefinition node = nodes.get(current);
        if (node.type() == WorkflowNodeType.JOIN_ALL) {
            return current;
        }
        if (node.type() != WorkflowNodeType.ACTION
                && node.type() != WorkflowNodeType.AGENT_RUN
                && node.type() != WorkflowNodeType.SUBGRAPH) {
            throw failure(
                    WorkflowErrorCode.INVALID_DEFINITION,
                    "fixed branch may only contain action, agent, or subgraph nodes before JOIN_ALL");
        }
        List<WorkflowEdge> edges = outgoing.getOrDefault(current, List.of());
        if (edges.size() != 1 || edges.getFirst().conditionId().isPresent()) {
            throw failure(
                    WorkflowErrorCode.INVALID_DEFINITION, "fixed branch must have one unconditional path to JOIN_ALL");
        }
        return resolveFixedBranchJoin(edges.getFirst().target(), nodes, outgoing, visited);
    }

    private static void validateNodeShapes(
            WorkflowDefinition definition,
            Map<WorkflowNodeId, WorkflowNodeDefinition> nodes,
            Map<WorkflowNodeId, List<WorkflowEdge>> outgoing) {
        for (WorkflowNodeDefinition node : definition.nodes()) {
            List<WorkflowEdge> edges = outgoing.getOrDefault(node.id(), List.of());
            if (node.type() == WorkflowNodeType.TERMINAL && !edges.isEmpty()) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "terminal node has outgoing edges");
            }
            if (node.type() != WorkflowNodeType.TERMINAL && edges.isEmpty()) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "non-terminal node has no outgoing edge");
            }
            if (node.type() == WorkflowNodeType.FORK_ALL) {
                if (!definition.requiredCapabilities().contains(WorkflowCapability.FIXED_ALL_OF)) {
                    throw failure(WorkflowErrorCode.INVALID_DEFINITION, "FORK_ALL requires FIXED_ALL_OF capability");
                }
                if (edges.size() < 2
                        || edges.size() > definition.limits().maximumParallelBranches()
                        || edges.stream().anyMatch(edge -> edge.conditionId().isPresent())
                        || edges.stream()
                                        .map(WorkflowEdge::branchOrdinal)
                                        .distinct()
                                        .count()
                                != edges.size()) {
                    throw failure(
                            WorkflowErrorCode.INVALID_DEFINITION,
                            "FORK_ALL requires bounded, uniquely ordered unconditional branches");
                }
            } else if (edges.size() > 1) {
                if (!definition.requiredCapabilities().contains(WorkflowCapability.CONDITION)
                        || edges.stream().anyMatch(edge -> edge.conditionId().isEmpty())) {
                    throw failure(
                            WorkflowErrorCode.INVALID_DEFINITION,
                            "multiple outgoing edges require explicit conditions");
                }
            }
            if (node.type() == WorkflowNodeType.WAIT
                    && !definition.requiredCapabilities().contains(WorkflowCapability.INTERRUPTION)) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "WAIT requires INTERRUPTION capability");
            }
            if (node.type() == WorkflowNodeType.SUBGRAPH
                    && !definition.requiredCapabilities().contains(WorkflowCapability.SUBGRAPH)) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "SUBGRAPH requires SUBGRAPH capability");
            }
            if (node.type() == WorkflowNodeType.JOIN_ALL
                    && edges.size() != 1
                    && node.type() != WorkflowNodeType.TERMINAL) {
                throw failure(WorkflowErrorCode.INVALID_DEFINITION, "JOIN_ALL must have one successor");
            }
        }
        long forks = nodes.values().stream()
                .filter(node -> node.type() == WorkflowNodeType.FORK_ALL)
                .count();
        long joins = nodes.values().stream()
                .filter(node -> node.type() == WorkflowNodeType.JOIN_ALL)
                .count();
        if (forks != joins) {
            throw failure(WorkflowErrorCode.INVALID_DEFINITION, "every fixed fork must have a corresponding join");
        }
    }

    private static void assertAllNodesReachable(
            WorkflowNodeId entry, Set<WorkflowNodeId> allNodes, Map<WorkflowNodeId, List<WorkflowEdge>> outgoing) {
        Set<WorkflowNodeId> visited = new HashSet<>();
        ArrayDeque<WorkflowNodeId> pending = new ArrayDeque<>();
        pending.add(entry);
        while (!pending.isEmpty()) {
            WorkflowNodeId next = pending.remove();
            if (visited.add(next)) {
                outgoing.getOrDefault(next, List.of()).stream()
                        .map(WorkflowEdge::target)
                        .forEach(pending::add);
            }
        }
        if (!visited.equals(allNodes)) {
            throw failure(WorkflowErrorCode.INVALID_DEFINITION, "workflow contains unreachable nodes");
        }
    }

    private static boolean hasCycle(
            WorkflowNodeId node,
            Map<WorkflowNodeId, List<WorkflowEdge>> outgoing,
            Set<WorkflowNodeId> visiting,
            Set<WorkflowNodeId> visited) {
        if (visiting.contains(node)) {
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }
        visiting.add(node);
        for (WorkflowEdge edge : outgoing.getOrDefault(node, List.of())) {
            if (hasCycle(edge.target(), outgoing, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(node);
        return false;
    }

    private static String canonical(WorkflowDefinition definition) {
        StringBuilder value = new StringBuilder();
        append(value, definition.id().value());
        append(value, definition.version().value());
        append(value, definition.stateSchema().schemaId());
        append(value, definition.stateSchema().version());
        append(value, definition.stateSchema().maximumValues());
        append(value, definition.stateSchema().maximumDepth());
        append(value, definition.stateSchema().maximumStringLength());
        append(value, definition.entryNode().value());
        append(value, definition.limits().maximumIterationsPerNode());
        append(value, definition.limits().maximumNodes());
        append(value, definition.limits().maximumParallelBranches());
        append(value, definition.limits().maximumSubgraphDepth());
        append(value, definition.limits().maximumExpandedNodes());
        append(value, definition.limits().maximumExpandedBranches());
        append(value, "schema-keys");
        append(value, definition.stateSchema().allowedKeys().size());
        definition.stateSchema().allowedKeys().stream().sorted().forEach(key -> append(value, key));
        append(value, "capabilities");
        append(value, definition.requiredCapabilities().size());
        definition.requiredCapabilities().stream().sorted().forEach(capability -> append(value, capability.name()));
        append(value, "nodes");
        append(value, definition.nodes().size());
        definition.nodes().stream()
                .sorted(Comparator.comparing(WorkflowNodeDefinition::id))
                .forEach(node -> {
                    append(value, node.id().value());
                    append(value, node.type().name());
                    append(value, node.targetReference().orElse(""));
                    node.subgraphBinding()
                            .ifPresentOrElse(
                                    binding -> {
                                        append(value, binding.definition().id().value());
                                        append(
                                                value,
                                                binding.definition().version().value());
                                        append(
                                                value,
                                                binding.definition().digest().value());
                                        append(value, binding.failurePolicy().name());
                                        append(value, "subgraph-inputs");
                                        append(
                                                value,
                                                binding.stateMapping().inputs().size());
                                        binding.stateMapping().inputs().entrySet().stream()
                                                .sorted(Map.Entry.comparingByKey())
                                                .forEach(entry -> {
                                                    append(value, entry.getKey());
                                                    append(value, entry.getValue());
                                                });
                                        append(value, "subgraph-outputs");
                                        append(
                                                value,
                                                binding.stateMapping().outputs().size());
                                        binding.stateMapping().outputs().entrySet().stream()
                                                .sorted(Map.Entry.comparingByKey())
                                                .forEach(entry -> {
                                                    append(value, entry.getKey());
                                                    append(value, entry.getValue());
                                                });
                                    },
                                    () -> append(value, "no-subgraph"));
                });
        append(value, "edges");
        append(value, definition.edges().size());
        definition.edges().stream()
                .sorted(Comparator.comparing(WorkflowEdge::source)
                        .thenComparing(WorkflowEdge::branchOrdinal)
                        .thenComparing(WorkflowEdge::target)
                        .thenComparing(edge -> edge.conditionId().orElse("")))
                .forEach(edge -> {
                    append(value, edge.source().value());
                    append(value, edge.target().value());
                    append(value, edge.conditionId().orElse(""));
                    append(value, edge.branchOrdinal());
                });
        return value.toString();
    }

    private static void append(StringBuilder target, Object value) {
        String encoded = String.valueOf(value);
        target.append(encoded.length()).append(':').append(encoded);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static WorkflowDefinitionDigest digest(WorkflowDefinition definition) {
        return new WorkflowDefinitionDigest(sha256(canonical(definition)));
    }

    private static WorkflowDefinitionRef reference(WorkflowDefinition definition) {
        return new WorkflowDefinitionRef(definition.id(), definition.version(), digest(definition));
    }

    private static WorkflowException failure(WorkflowErrorCode code, String message) {
        return new WorkflowException(code, "compile", message);
    }
}
