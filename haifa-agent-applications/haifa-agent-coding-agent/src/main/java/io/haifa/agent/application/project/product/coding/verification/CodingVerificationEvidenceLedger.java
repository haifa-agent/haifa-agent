package io.haifa.agent.application.project.product.coding.verification;

import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reconstructs plan-bound verification evidence from authoritative Execution Tool results. */
public final class CodingVerificationEvidenceLedger {
    private static final Set<String> EXECUTION_TOOLS = Set.of("execution.run", "execution_run");

    private final RuntimeStateRepository state;

    public CodingVerificationEvidenceLedger(RuntimeStateRepository state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    public Snapshot reconstruct(io.haifa.agent.core.run.AgentRunId runId, CodingVerificationPlan plan) {
        Map<String, AgentStep> steps = new LinkedHashMap<>();
        state.steps(runId).forEach(step -> steps.put(step.id().value(), step));
        List<CodingVerificationEvidence> evidence = new ArrayList<>();
        state.toolCalls(runId).stream()
                .sorted(Comparator.comparing(ToolCall::requestedAt)
                        .thenComparing(value -> value.id().value()))
                .filter(call -> EXECUTION_TOOLS.contains(call.toolName()))
                .forEach(call -> collect(call, steps.get(call.stepId().value()), plan, evidence));
        return new Snapshot(evidence);
    }

    private static void collect(
            ToolCall call, AgentStep step, CodingVerificationPlan plan, List<CodingVerificationEvidence> target) {
        Map<String, Object> data = call.result()
                .map(result -> result.structuredData())
                .orElseGet(() -> java.util.Optional.ofNullable(step)
                        .flatMap(AgentStep::result)
                        .map(result -> result.data())
                        .orElse(Map.of()));
        if (!plan.digest().equals(data.get("verificationPlanDigest"))) return;
        Object rawDimensions = data.get("verificationDimensions");
        if (!(rawDimensions instanceof List<?> values)) return;
        String terminal =
                String.valueOf(data.getOrDefault("status", call.status().name()));
        boolean passed = call.status() == ToolCallStatus.COMPLETED && "SUCCEEDED".equals(terminal);
        for (Object value : values) {
            CodingVerificationDimension dimension;
            try {
                dimension = CodingVerificationDimension.valueOf(String.valueOf(value));
            } catch (IllegalArgumentException unsupported) {
                continue;
            }
            if (!plan.dimensions().contains(dimension)) continue;
            add(target, call, plan, dimension, CodingVerificationEvidenceKind.VERIFICATION_CHECK_STARTED, terminal);
            add(
                    target,
                    call,
                    plan,
                    dimension,
                    passed
                            ? CodingVerificationEvidenceKind.VERIFICATION_CHECK_PASSED
                            : CodingVerificationEvidenceKind.VERIFICATION_CHECK_FAILED,
                    terminal);
            if (!passed) continue;
            switch (dimension) {
                case FAILURE_ATOMICITY ->
                    add(target, call, plan, dimension, CodingVerificationEvidenceKind.ATOMICITY_CONFIRMED, terminal);
                case IDEMPOTENCY ->
                    add(target, call, plan, dimension, CodingVerificationEvidenceKind.IDEMPOTENCY_CONFIRMED, terminal);
                case COMPATIBILITY ->
                    add(
                            target,
                            call,
                            plan,
                            dimension,
                            CodingVerificationEvidenceKind.COMPATIBILITY_CONFIRMED,
                            terminal);
                case CONCURRENCY ->
                    add(target, call, plan, dimension, CodingVerificationEvidenceKind.CONCURRENCY_CHECKED, terminal);
                default -> {
                    // The generic passed evidence is sufficient for the remaining dimensions.
                }
            }
        }
    }

    private static void add(
            List<CodingVerificationEvidence> target,
            ToolCall call,
            CodingVerificationPlan plan,
            CodingVerificationDimension dimension,
            CodingVerificationEvidenceKind kind,
            String terminal) {
        String sourceRef = "tool-call:" + call.id().value();
        String sourceDigest = digest(kind.name() + "|" + plan.digest() + "|" + dimension.name() + "|" + sourceRef + "|"
                + call.version() + "|" + terminal);
        target.add(new CodingVerificationEvidence(
                kind,
                plan.digest(),
                dimension,
                sourceRef,
                terminal,
                kind == CodingVerificationEvidenceKind.VERIFICATION_CHECK_FAILED
                        ? "Verification check did not pass"
                        : "Verification check evidence recorded",
                call.completedAt().orElse(call.requestedAt()),
                sourceDigest));
    }

    private static String digest(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record Snapshot(List<CodingVerificationEvidence> evidence) {
        public Snapshot {
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        }

        public Set<CodingVerificationDimension> passedDimensions() {
            EnumSet<CodingVerificationDimension> result = EnumSet.noneOf(CodingVerificationDimension.class);
            evidence.stream()
                    .filter(value -> value.kind() == CodingVerificationEvidenceKind.VERIFICATION_CHECK_PASSED)
                    .map(CodingVerificationEvidence::dimension)
                    .forEach(result::add);
            return Set.copyOf(result);
        }
    }
}
