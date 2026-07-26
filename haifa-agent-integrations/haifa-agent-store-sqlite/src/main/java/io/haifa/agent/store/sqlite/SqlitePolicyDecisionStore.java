package io.haifa.agent.store.sqlite;

import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequestDigest;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.PolicyDecisionRow;
import io.haifa.agent.store.sqlite.mybatis.PolicyStoreMapper;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.util.Objects;
import java.util.Optional;

public final class SqlitePolicyDecisionStore implements PolicyDecisionStore {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;

    public SqlitePolicyDecisionStore(SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
    }

    @Override
    public void save(PolicyDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        PolicyRequest request = decision.request()
                .orElseThrow(() -> new IllegalArgumentException("SQLite requires a request-bound policy decision"));
        SqlitePolicyStoreSupport.execute(unitOfWork, () -> {
            PolicyStoreMapper mapper = unitOfWork.mapper(PolicyStoreMapper.class);
            PolicyDecisionRow existing = mapper.findPolicyDecision(decision.id().value());
            if (existing != null) {
                if (!fromRow(existing).equals(decision)) {
                    throw new IllegalStateException("policy decision id is already used");
                }
                return null;
            }
            EncodedPayload payload = codecs.encode(SqliteRuntimePayloadTypes.POLICY_REQUEST, request);
            mapper.insertPolicyDecision(toRow(decision, request, payload));
            return null;
        });
    }

    @Override
    public Optional<PolicyDecision> find(PolicyDecisionId id) {
        Objects.requireNonNull(id, "id must not be null");
        return SqlitePolicyStoreSupport.execute(unitOfWork, () -> Optional.ofNullable(
                        unitOfWork.mapper(PolicyStoreMapper.class).findPolicyDecision(id.value()))
                .map(this::fromRow));
    }

    private static PolicyDecisionRow toRow(PolicyDecision decision, PolicyRequest request, EncodedPayload payload) {
        return new PolicyDecisionRow(
                decision.id().value(),
                decision.snapshot().value(),
                request.subject().tenant().tenantId(),
                request.subject().principal().principalId(),
                request.subject().principal().principalType(),
                request.subject().productId(),
                request.context().projectRef().orElse(null),
                request.context().sessionRef().orElse(null),
                request.context().runRef().orElse(null),
                request.context().attemptRef().orElse(null),
                request.action().capability(),
                request.action().operation(),
                request.resource().resourceType(),
                request.resource().resourceRef(),
                request.resource().resourceDigest().orElse(null),
                decision.effect().name(),
                decision.challenge().map(Enum::name).orElse(null),
                decision.reasonCode(),
                decision.safeExplanation(),
                decision.matchedRule().map(value -> value.ruleId()).orElse(null),
                decision.matchedRule().map(value -> value.version()).orElse(null),
                decision.requestDigest(),
                payload.schemaVersion(),
                payload.bytes(),
                payload.hash(),
                decision.decidedAt());
    }

    private PolicyDecision fromRow(PolicyDecisionRow row) {
        PolicyRequest request = codecs.decode(
                SqliteRuntimePayloadTypes.POLICY_REQUEST,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.POLICY_REQUEST.name(),
                        row.requestSchemaVersion(),
                        row.requestPayload(),
                        row.requestHash()));
        Optional<PolicyRuleRef> matchedRule = row.matchedRuleId() == null
                ? Optional.empty()
                : Optional.of(new PolicyRuleRef(row.matchedRuleId(), row.matchedRuleVersion()));
        PolicyDecision decision = new PolicyDecision(
                new PolicyDecisionId(row.decisionId()),
                Optional.of(request),
                row.requestDigest(),
                PolicyEffect.valueOf(row.effect()),
                Optional.ofNullable(row.challenge()).map(PolicyChallenge::valueOf),
                row.reasonCode(),
                row.safeExplanation(),
                new PolicySnapshotRef(row.snapshotId()),
                matchedRule,
                row.decidedAt());
        if (!PolicyRequestDigest.compute(request).equals(row.requestDigest())
                || !request.subject().tenant().tenantId().equals(row.tenantId())
                || !request.subject().principal().principalId().equals(row.principalId())
                || !request.subject().principal().principalType().equals(row.principalType())
                || !request.subject().productId().equals(row.productId())
                || !request.context().projectRef().equals(Optional.ofNullable(row.projectRef()))
                || !request.context().sessionRef().equals(Optional.ofNullable(row.sessionRef()))
                || !request.context().runRef().equals(Optional.ofNullable(row.runId()))
                || !request.context().attemptRef().equals(Optional.ofNullable(row.attemptId()))
                || !request.action().capability().equals(row.capability())
                || !request.action().operation().equals(row.operation())
                || !request.resource().resourceType().equals(row.resourceType())
                || !request.resource().resourceRef().equals(row.resourceRef())
                || !request.resource().resourceDigest().equals(Optional.ofNullable(row.resourceDigest()))) {
            throw new IllegalStateException("policy decision columns do not match request payload");
        }
        return decision;
    }
}
