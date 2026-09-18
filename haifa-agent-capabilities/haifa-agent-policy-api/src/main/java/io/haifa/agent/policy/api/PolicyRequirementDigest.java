package io.haifa.agent.policy.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Secret-free equivalence digest for one frozen approval requirement; never an authority or key. */
public final class PolicyRequirementDigest {
    private PolicyRequirementDigest() {}

    public static String compute(PolicyRequest request, PolicyRuleSet rules) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        List<String> fields = new ArrayList<>();
        fields.add("policy-requirement-v1");
        fields.add(rules.contentDigest());
        fields.add(request.subject().tenant().tenantId());
        fields.add(request.subject().productId());
        fields.add(request.context().projectRef().orElse(""));
        fields.add(request.context().sessionRef().orElse(""));
        fields.add(request.context().securityConfigurationDigest().orElse(""));
        fields.add(request.action().capability());
        fields.add(request.action().operation());
        fields.add(request.resource().resourceType());
        fields.add(request.resource().resourceRef());
        fields.add(request.resource().resourceDigest().orElse(""));
        fields.add(request.risk().level().name());
        request.risk().sideEffects().stream().map(Enum::name).sorted().forEach(fields::add);
        fields.add(Boolean.toString(request.risk().credentialRequired()));
        fields.add(request.risk().networkTargetSummary().orElse(""));
        return "sha256:" + PolicyDigest.sha256Fields(fields);
    }
}
