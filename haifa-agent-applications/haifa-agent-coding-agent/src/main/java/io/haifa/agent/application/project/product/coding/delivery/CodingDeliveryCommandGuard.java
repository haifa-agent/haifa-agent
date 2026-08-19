package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Enforces the frozen delivery-intent ceiling and evidence-backed Git delivery order before dispatch. */
public final class CodingDeliveryCommandGuard {
    private final RuntimeStateRepository state;
    private final CodingDeliveryIntentResolver intents;

    public CodingDeliveryCommandGuard(RuntimeStateRepository state, CodingDeliveryIntentResolver intents) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.intents = Objects.requireNonNull(intents, "intents must not be null");
    }

    public Decision evaluate(
            AgentRunId runId,
            String command,
            SystemGitCliCommandClassifier.Classification classification,
            String repositoryScopeDigest) {
        repositoryScopeDigest = requireScopeDigest(repositoryScopeDigest);
        var action = CodingDeliveryCommandSemantics.action(command, classification);
        if (action == CodingDeliveryCommandSemantics.Action.NONE) return Decision.allow(action);
        CodingDeliveryIntent intent = intents.resolve(runId);
        CodingDeliveryIntent required = requiredIntent(action);
        if (!intent.allows(required)) {
            return Decision.deny(
                    action, "DELIVERY_INTENT_EXCEEDED", "Command exceeds the delivery intent frozen for this Run");
        }
        if (!CodingDeliveryCommandSemantics.direct(classification)) {
            return Decision.deny(
                    action,
                    "DELIVERY_COMMAND_MUST_BE_DIRECT",
                    "Commit, push, and pull-request delivery commands must be direct and independently journaled");
        }
        Evidence evidence = evidence(runId, repositoryScopeDigest);
        if (evidence.outcomeUnknown().contains(action)
                && !evidence.verifiedAfterUnknown().contains(action)) {
            return Decision.deny(
                    action,
                    "DELIVERY_OUTCOME_VERIFICATION_REQUIRED",
                    "The previous delivery outcome is unknown; verify authoritative repository or remote facts first");
        }
        return switch (action) {
            case STAGE ->
                !CodingDeliveryCommandSemantics.exactStagePaths(command)
                        ? Decision.deny(
                                action,
                                "DELIVERY_STAGE_SCOPE_REQUIRED",
                                "Stage exact approved paths; broad staging such as git add dot, all, or update is forbidden")
                        : require(
                                action,
                                evidence,
                                "DELIVERY_TOPOLOGY_OR_REVIEW_MISSING",
                                "Inspect repository root, branch, upstream, status, diff, and validation before staging",
                                "REPOSITORY_ROOT_VERIFIED",
                                "BRANCH_VERIFIED",
                                "UPSTREAM_INSPECTED",
                                "STATUS_INSPECTED",
                                "HEAD_VERIFIED",
                                "DIFF_INSPECTION",
                                "VALIDATION_PASSED");
            case COMMIT ->
                require(
                        action,
                        evidence,
                        "DELIVERY_STAGE_MISSING",
                        "Stage exact approved paths and inspect the staged result before committing",
                        "STAGE_COMPLETED",
                        "STAGED_DIFF_INSPECTED");
            case PUSH ->
                !CodingDeliveryCommandSemantics.explicitPushTarget(command)
                        ? Decision.deny(
                                action,
                                "DELIVERY_PUSH_TARGET_REQUIRED",
                                "Push must name the exact remote and branch or refspec")
                        : requireAfter(
                                action,
                                evidence,
                                "DELIVERY_COMMIT_VERIFICATION_MISSING",
                                "Verify the committed HEAD after commit before pushing the exact branch",
                                "HEAD_VERIFIED",
                                "COMMIT_COMPLETED");
            case PULL_REQUEST -> {
                if (!classification.reasonCode().equals("GH_PR_CREATE")) {
                    yield Decision.deny(
                            action,
                            "DELIVERY_PR_ACTION_DENIED",
                            "The delivery transaction permits pull-request create and read-only view, not mutation of an existing pull request");
                }
                if (!CodingDeliveryCommandSemantics.explicitDevBase(command)) {
                    yield Decision.deny(
                            action,
                            "DELIVERY_PR_BASE_REQUIRED",
                            "Create the pull request with explicit base dev unless the trusted task contract says otherwise");
                }
                yield requireAfter(
                        action,
                        evidence,
                        "DELIVERY_REMOTE_VERIFICATION_MISSING",
                        "Verify the remote ref after push before creating a pull request",
                        "REMOTE_REF_VERIFIED",
                        "PUSH_COMPLETED");
            }
            case NONE -> Decision.allow(action);
        };
    }

    private Evidence evidence(AgentRunId runId, String repositoryScopeDigest) {
        EnumSet<CodingDeliveryCommandSemantics.Action> unknown =
                EnumSet.noneOf(CodingDeliveryCommandSemantics.Action.class);
        EnumSet<CodingDeliveryCommandSemantics.Action> verified =
                EnumSet.noneOf(CodingDeliveryCommandSemantics.Action.class);
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        Map<String, Integer> latest = new HashMap<>();
        List<ToolCall> calls = state.toolCalls(runId).stream()
                .sorted(Comparator.comparing(ToolCall::requestedAt)
                        .thenComparing(call -> call.id().value()))
                .toList();
        for (int index = 0; index < calls.size(); index++) {
            ToolCall call = calls.get(index);
            Map<String, Object> data = call.result()
                    .map(result -> result.structuredData())
                    .orElseGet(() ->
                            call.error().map(error -> error.error().details()).orElse(Map.of()));
            if (!repositoryScopeDigest.equals(data.get("deliveryRepositoryScopeDigest"))) continue;
            Object code = data.get("deliveryEvidenceCode");
            if (code instanceof String value) {
                codes.add(value);
                latest.put(value, index);
                if ((value.equals("STATUS_INSPECTED") || value.equals("STAGED_DIFF_INSPECTED"))
                        && unknown.contains(CodingDeliveryCommandSemantics.Action.STAGE)) {
                    verified.add(CodingDeliveryCommandSemantics.Action.STAGE);
                }
                if (value.equals("HEAD_VERIFIED") && unknown.contains(CodingDeliveryCommandSemantics.Action.COMMIT)) {
                    verified.add(CodingDeliveryCommandSemantics.Action.COMMIT);
                }
                if (value.equals("REMOTE_REF_VERIFIED")
                        && unknown.contains(CodingDeliveryCommandSemantics.Action.PUSH)) {
                    verified.add(CodingDeliveryCommandSemantics.Action.PUSH);
                }
                if (value.equals("PULL_REQUEST_VERIFIED")
                        && unknown.contains(CodingDeliveryCommandSemantics.Action.PULL_REQUEST)) {
                    verified.add(CodingDeliveryCommandSemantics.Action.PULL_REQUEST);
                }
            }
            String family = String.valueOf(
                    data.getOrDefault("effectiveOperationFamily", data.getOrDefault("operationFamily", "UNKNOWN")));
            String status = String.valueOf(data.getOrDefault("status", "UNKNOWN"));
            String semantic = String.valueOf(data.getOrDefault("semanticOutcome", "UNKNOWN"));
            if (family.equals("DIFF") && (status.equals("SUCCEEDED") || semantic.equals("EXPECTED_VARIANT"))) {
                codes.add("DIFF_INSPECTION");
            }
            String declared = String.valueOf(data.getOrDefault("declaredOperationFamily", family));
            if ((declared.equals("TEST") || declared.equals("BUILD")) && status.equals("SUCCEEDED")) {
                codes.add("VALIDATION_PASSED");
            }
            Object actionValue = data.get("deliveryAction");
            if (!(actionValue instanceof String name)) continue;
            CodingDeliveryCommandSemantics.Action action;
            try {
                action = CodingDeliveryCommandSemantics.Action.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if ("OUTCOME_UNKNOWN".equals(data.get("semanticOutcome"))
                    || "OUTCOME_UNKNOWN".equals(data.get("failureCategory"))) {
                unknown.add(action);
                verified.remove(action);
            }
        }
        return new Evidence(java.util.Set.copyOf(codes), Map.copyOf(latest), unknown, verified);
    }

    private static Decision require(
            CodingDeliveryCommandSemantics.Action action,
            Evidence evidence,
            String code,
            String message,
            String... required) {
        return java.util.Arrays.stream(required).allMatch(evidence.codes()::contains)
                ? Decision.allow(action)
                : Decision.deny(action, code, message);
    }

    private static Decision requireAfter(
            CodingDeliveryCommandSemantics.Action action,
            Evidence evidence,
            String code,
            String message,
            String required,
            String predecessor) {
        Integer position = evidence.latest().get(required);
        Integer previous = evidence.latest().get(predecessor);
        return position != null && previous != null && position > previous
                ? Decision.allow(action)
                : Decision.deny(action, code, message);
    }

    private static CodingDeliveryIntent requiredIntent(CodingDeliveryCommandSemantics.Action action) {
        return switch (action) {
            case STAGE, COMMIT -> CodingDeliveryIntent.LOCAL_COMMIT;
            case PUSH -> CodingDeliveryIntent.REMOTE_PUSH;
            case PULL_REQUEST -> CodingDeliveryIntent.PULL_REQUEST;
            case NONE -> CodingDeliveryIntent.WORKTREE_ONLY;
        };
    }

    private static String requireScopeDigest(String value) {
        String normalized = Objects.requireNonNull(value, "repositoryScopeDigest must not be null")
                .trim();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("repositoryScopeDigest must be a lowercase SHA-256 digest");
        }
        return normalized;
    }

    private record Evidence(
            java.util.Set<String> codes,
            Map<String, Integer> latest,
            java.util.Set<CodingDeliveryCommandSemantics.Action> outcomeUnknown,
            java.util.Set<CodingDeliveryCommandSemantics.Action> verifiedAfterUnknown) {}

    public record Decision(
            boolean allowed, CodingDeliveryCommandSemantics.Action action, String code, String safeMessage) {
        private static Decision allow(CodingDeliveryCommandSemantics.Action action) {
            return new Decision(true, action, "DELIVERY_ALLOWED", "Delivery command is within the frozen intent");
        }

        private static Decision deny(CodingDeliveryCommandSemantics.Action action, String code, String safeMessage) {
            return new Decision(false, action, code, safeMessage);
        }
    }
}
