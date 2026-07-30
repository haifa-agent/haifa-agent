package io.haifa.agent.application.project.product.coding.verification;

import io.haifa.agent.application.project.product.coding.delivery.CodingTaskContract;
import io.haifa.agent.application.project.product.coding.delivery.CodingTaskContractResolver;
import io.haifa.agent.application.project.product.coding.delivery.CodingTaskIntent;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative risk mapper. It maps task facts to bounded dimensions and never emits executable
 * checks, permissions, paths, or Case-specific behavior.
 */
public final class CodingVerificationPlanResolver {
    private static final Set<String> DATABASE_FACTS =
            Set.of("database", "sqlite", "sql", "migration", "schema", "数据库", "迁移");
    private static final Set<String> CONCURRENCY_FACTS =
            Set.of("concurrent", "concurrency", "race", "lock", "atomic", "并发", "竞态", "锁", "原子");
    private static final Set<String> SECURITY_FACTS =
            Set.of("security", "archive", "upload", "path traversal", "normalize", "安全", "归档", "上传", "路径");
    private static final Set<String> COMPATIBILITY_FACTS =
            Set.of("api", "protocol", "compatible", "compatibility", "migration", "协议", "兼容", "迁移");

    private final RuntimeStateRepository state;
    private final CodingTaskContractResolver contracts;

    public CodingVerificationPlanResolver(RuntimeStateRepository state, CodingTaskContractResolver contracts) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.contracts = Objects.requireNonNull(contracts, "contracts must not be null");
    }

    public CodingVerificationPlan resolve(AgentRun run) {
        CodingTaskContract contract = contracts.resolve(run);
        String task = initialText(run).toLowerCase(Locale.ROOT);
        EnumSet<CodingVerificationDimension> dimensions = EnumSet.of(CodingVerificationDimension.SUCCESS_PATH);
        CodingVerificationRiskLevel risk = CodingVerificationRiskLevel.LOW;
        if (contract.intent() == CodingTaskIntent.CHANGE || contract.intent() == CodingTaskIntent.CREATE) {
            dimensions.add(CodingVerificationDimension.BOUNDARY);
            dimensions.add(CodingVerificationDimension.FAILURE_PATH);
            dimensions.add(CodingVerificationDimension.FAILURE_ATOMICITY);
            dimensions.add(CodingVerificationDimension.RESOURCE_CLEANUP);
            risk = CodingVerificationRiskLevel.MEDIUM;
        }
        if (containsAny(task, DATABASE_FACTS)) {
            dimensions.add(CodingVerificationDimension.COMPATIBILITY);
            dimensions.add(CodingVerificationDimension.IDEMPOTENCY);
            dimensions.add(CodingVerificationDimension.FAILURE_ATOMICITY);
            risk = CodingVerificationRiskLevel.HIGH;
        }
        if (containsAny(task, CONCURRENCY_FACTS)) {
            dimensions.add(CodingVerificationDimension.CONCURRENCY);
            dimensions.add(CodingVerificationDimension.FAILURE_PATH);
            risk = CodingVerificationRiskLevel.HIGH;
        }
        if (containsAny(task, SECURITY_FACTS)) {
            dimensions.add(CodingVerificationDimension.SECURITY_NORMALIZATION);
            dimensions.add(CodingVerificationDimension.FAILURE_ATOMICITY);
            dimensions.add(CodingVerificationDimension.RESOURCE_CLEANUP);
            risk = CodingVerificationRiskLevel.HIGH;
        }
        if (containsAny(task, COMPATIBILITY_FACTS)
                || !contract.acceptanceCriteriaRefs().isEmpty()) {
            dimensions.add(CodingVerificationDimension.COMPATIBILITY);
        }
        Set<String> requiredEvidence = dimensions.stream()
                .map(dimension -> "VERIFICATION_CHECK_PASSED:" + dimension.name())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return CodingVerificationPlan.freeze(
                "verification:" + contract.taskId(),
                contract.contractDigest(),
                dimensions,
                requiredEvidence,
                risk,
                contract.createdAt());
    }

    private String initialText(AgentRun run) {
        return state.messages(run.id()).stream()
                .filter(message -> message.role() == MessageRole.USER)
                .min(java.util.Comparator.comparingLong(AgentMessage::sequence))
                .stream()
                .flatMap(message -> message.contents().stream())
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private static boolean containsAny(String value, Set<String> facts) {
        return facts.stream().anyMatch(value::contains);
    }
}
