package io.haifa.agent.memory.core;

import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryCandidateDraft;
import io.haifa.agent.memory.api.MemoryPolicy;
import io.haifa.agent.memory.api.MemoryPolicyDecision;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemorySecurityLabel;
import io.haifa.agent.memory.api.MemorySourceType;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative first-version policy: explicit review is the default and sensitive data is never auto-approved. */
public final class DefaultMemoryPolicy implements MemoryPolicy {
    private static final Pattern PAYMENT = Pattern.compile("(?:\\d[ -]*?){13,19}");
    private static final Pattern PRECISE_ID = Pattern.compile("\\b\\d{6}[- ]?\\d{8}[- ]?[0-9xX]{4}\\b");
    private final boolean allowAutomaticApproval;

    public DefaultMemoryPolicy() {
        this(false);
    }

    public DefaultMemoryPolicy(boolean allowAutomaticApproval) {
        this.allowAutomaticApproval = allowAutomaticApproval;
    }

    @Override
    public String version() {
        return "memory-governance-v1";
    }

    @Override
    public MemoryPolicyDecision evaluate(MemoryCandidateDraft draft) {
        String text = draft.content().boundedText().toLowerCase(Locale.ROOT);
        Set<MemorySecurityLabel> labels = new HashSet<>(draft.scope().securityLabels());
        if (text.contains("password")
                || text.contains("api_key")
                || text.contains("api key")
                || text.contains("secret")
                || text.contains("credential")
                || text.contains("access token")
                || text.contains("bearer ")) labels.add(MemorySecurityLabel.CREDENTIAL);
        if (PAYMENT.matcher(text).find() || text.contains("cvv")) labels.add(MemorySecurityLabel.PAYMENT);
        if (PRECISE_ID.matcher(text).find() || text.contains("passport") || text.contains("social security"))
            labels.add(MemorySecurityLabel.PERSONAL_DATA);
        if (draft.sources().stream().anyMatch(source -> source.type() == MemorySourceType.TOOL_CALL)) {
            labels.add(MemorySecurityLabel.UNTRUSTED_TOOL_TEXT);
        }
        boolean sensitive = labels.stream()
                .anyMatch(label -> label == MemorySecurityLabel.CREDENTIAL
                        || label == MemorySecurityLabel.PAYMENT
                        || label == MemorySecurityLabel.PERSONAL_DATA
                        || label == MemorySecurityLabel.RESTRICTED
                        || label == MemorySecurityLabel.UNTRUSTED_TOOL_TEXT);
        return new MemoryPolicyDecision(
                sensitive,
                allowAutomaticApproval && !sensitive,
                labels,
                sensitive ? "sensitive-or-untrusted-review-required" : "explicit-review-required");
    }

    @Override
    public boolean canPropose(MemoryActor actor, MemoryScope scope) {
        return owns(actor, scope);
    }

    @Override
    public boolean canReview(MemoryActor actor, MemoryScope scope) {
        return owns(actor, scope)
                && (actor.permissions().contains("memory:review")
                        || actor.permissions().contains("memory:admin"));
    }

    @Override
    public boolean canPurge(MemoryActor actor, MemoryScope scope) {
        return owns(actor, scope)
                && (actor.permissions().contains("memory:purge")
                        || actor.permissions().contains("memory:admin"));
    }

    @Override
    public boolean canRead(MemoryQuery query, io.haifa.agent.memory.api.Memory memory) {
        return memory.scope().tenant().equals(query.tenant())
                && memory.scope().owner().equals(query.owner())
                && query.scopes().contains(memory.scope())
                && query.allowedSecurityLabels().containsAll(memory.securityLabels());
    }

    private boolean owns(MemoryActor actor, MemoryScope scope) {
        return actor.tenant().equals(scope.tenant()) && actor.principal().equals(scope.owner());
    }
}
