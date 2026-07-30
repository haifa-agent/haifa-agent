package io.haifa.agent.application.project.product.coding.verification;

import io.haifa.agent.context.api.ContextBuildRequest;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextProvenance;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextRole;
import io.haifa.agent.context.item.ContextSecurity;
import io.haifa.agent.context.item.TextContextContent;
import io.haifa.agent.context.source.ContextSource;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded plan/evidence projection; no executable command or host path is placed in context. */
public final class CodingVerificationContextSource implements ContextSource {
    public static final String SOURCE_ID = "coding.verification.plan";

    private final RunStateRepository runs;
    private final CodingVerificationPlanResolver plans;
    private final CodingVerificationEvidenceLedger evidence;

    public CodingVerificationContextSource(
            RunStateRepository runs, CodingVerificationPlanResolver plans, CodingVerificationEvidenceLedger evidence) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public List<ContextItem> load(ContextBuildRequest request) {
        var run = runs.find(request.runId()).orElse(null);
        if (run == null) return List.of();
        CodingVerificationPlan plan = plans.resolve(run);
        Set<CodingVerificationDimension> passed =
                evidence.reconstruct(run.id(), plan).passedDimensions();
        String dimensions = plan.dimensions().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Enum::name)
                .reduce((left, right) -> left + "|" + right)
                .orElseThrow();
        String completed = passed.stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Enum::name)
                .reduce((left, right) -> left + "|" + right)
                .orElse("NONE");
        String text = String.join(
                "\n",
                "[CODING_VERIFICATION_PLAN]",
                "planDigest=" + plan.digest(),
                "riskLevel=" + plan.riskLevel(),
                "requiredDimensions=" + dimensions,
                "passedDimensions=" + completed,
                "verificationAction=run bounded checks that cover every required dimension; on execution_run set "
                        + "verificationPlanDigest to this exact digest and verificationDimensions to only the "
                        + "dimensions actually covered by that command",
                "evidenceRule=only successful terminal Execution results can pass dimensions; a failed check remains "
                        + "failed and must be repaired or reported");
        return List.of(new ContextItem(
                new ContextItemId("coding-verification:" + run.id().value() + ":"
                        + plan.digest().substring(7, 23)),
                ContextItemType.RUNTIME_STATE,
                new TextContextContent(ContextRole.SYSTEM, text),
                Math.max(1, text.length() / 4),
                ContextPriority.CRITICAL,
                ContextRetention.MUST_KEEP,
                new ContextSecurity(Set.of("product-control", "safe-evidence"), false),
                new ContextProvenance("coding-product", run.id().value(), version(), plan.digest()),
                Map.of("verificationPlanDigest", plan.digest())));
    }
}
