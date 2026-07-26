package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface PolicyStoreMapper {
    int insertPolicySnapshot(@Param("row") PolicySnapshotRow row);

    PolicySnapshotRow findPolicySnapshot(@Param("snapshotId") String snapshotId);

    int insertPolicyDecision(@Param("row") PolicyDecisionRow row);

    PolicyDecisionRow findPolicyDecision(@Param("decisionId") String decisionId);

    int insertApprovalRequestMetadata(@Param("row") ApprovalRequestMetadataRow row);

    ApprovalRequestMetadataRow findApprovalRequestMetadata(@Param("requestId") String requestId);

    int insertApprovalResponseMetadata(
            @Param("responseId") String responseId,
            @Param("responderTenantId") String responderTenantId,
            @Param("responderPrincipalId") String responderPrincipalId,
            @Param("responderPrincipalType") String responderPrincipalType,
            @Param("authorityOutcome") String authorityOutcome,
            @Param("authorityReasonCode") String authorityReasonCode,
            @Param("targetOutcome") String targetOutcome,
            @Param("targetReasonCode") String targetReasonCode,
            @Param("selectedReuseScope") String selectedReuseScope,
            @Param("validationDigest") String validationDigest);

    int updateApprovalResponseVerification(
            @Param("responseId") String responseId,
            @Param("outcome") String outcome,
            @Param("reasonCode") String reasonCode);

    int insertPolicyAuthorizationEvidence(@Param("row") PolicyAuthorizationEvidenceRow row);

    PolicyAuthorizationEvidenceRow findPolicyAuthorizationEvidence(@Param("decisionId") String decisionId);

    int insertApprovalGrant(@Param("row") ApprovalGrantRow row);

    ApprovalGrantRow findApprovalGrant(@Param("grantId") String grantId);

    List<ApprovalGrantRow> findApprovalGrantCandidates(
            @Param("tenantId") String tenantId,
            @Param("principalId") String principalId,
            @Param("principalType") String principalType,
            @Param("productId") String productId,
            @Param("capability") String capability,
            @Param("operation") String operation,
            @Param("targetType") String targetType);

    int consumeApprovalGrant(
            @Param("grantId") String grantId,
            @Param("expectedVersion") long expectedVersion,
            @Param("consumedAt") Instant consumedAt);

    int revokeApprovalGrant(
            @Param("grantId") String grantId,
            @Param("expectedVersion") long expectedVersion,
            @Param("revokedAt") Instant revokedAt,
            @Param("reasonCode") String reasonCode);

    int insertProjectTrust(@Param("row") ProjectTrustRow row);

    ProjectTrustRow findProjectTrust(@Param("trustId") String trustId);

    int revokeProjectTrust(
            @Param("trustId") String trustId,
            @Param("expectedVersion") long expectedVersion,
            @Param("revokedAt") Instant revokedAt,
            @Param("reasonCode") String reasonCode);
}
