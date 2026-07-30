package io.haifa.agent.skill.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolCoordinate;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact authorization for one reviewed script and one fixed business Tool execution envelope. */
public record SkillScriptExecutionGrant(
        String id,
        int schemaVersion,
        long version,
        String packageReviewGrantId,
        TenantRef tenant,
        PrincipalRef principal,
        String productId,
        SkillTrustScope scope,
        Optional<String> projectRef,
        SkillCoordinate coordinate,
        SkillContentDigest registrationDigest,
        SkillContentDigest packageDigest,
        String scriptRelativePath,
        SkillContentDigest scriptDigest,
        ToolCoordinate toolCoordinate,
        String providerBindingReference,
        String toolCatalogDigest,
        String argumentPolicyDigest,
        String scriptRuntimeRef,
        String executionProfileDigest,
        String sandboxDigest,
        List<String> capabilities,
        List<String> networkHosts,
        Instant issuedAt,
        Optional<Instant> expiresAt,
        Optional<Instant> revokedAt,
        SkillTrustGrantState state,
        String reviewerRef,
        String reviewSourceRef,
        String reasonCode) {
    public SkillScriptExecutionGrant {
        id = SkillValues.text(id, "id", 128);
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported script grant schemaVersion");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        packageReviewGrantId = SkillValues.text(packageReviewGrantId, "packageReviewGrantId", 128);
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        productId = SkillValues.text(productId, "productId", 128);
        scope = Objects.requireNonNull(scope, "scope must not be null");
        projectRef = Objects.requireNonNull(projectRef, "projectRef must not be null")
                .map(value -> SkillValues.text(value, "projectRef", 256));
        coordinate = Objects.requireNonNull(coordinate, "coordinate must not be null");
        registrationDigest = Objects.requireNonNull(registrationDigest, "registrationDigest must not be null");
        packageDigest = Objects.requireNonNull(packageDigest, "packageDigest must not be null");
        scriptRelativePath =
                SkillValues.text(scriptRelativePath, "scriptRelativePath", 512).replace('\\', '/');
        if (scriptRelativePath.startsWith("/")
                || scriptRelativePath.contains("../")
                || scriptRelativePath.contains(":")
                || scriptRelativePath.equals("..")) {
            throw new IllegalArgumentException("scriptRelativePath must be package-relative");
        }
        scriptDigest = Objects.requireNonNull(scriptDigest, "scriptDigest must not be null");
        toolCoordinate = Objects.requireNonNull(toolCoordinate, "toolCoordinate must not be null");
        providerBindingReference = SkillValues.text(providerBindingReference, "providerBindingReference", 256);
        toolCatalogDigest = SkillValues.text(toolCatalogDigest, "toolCatalogDigest", 128);
        argumentPolicyDigest = sha256(argumentPolicyDigest, "argumentPolicyDigest");
        scriptRuntimeRef = SkillValues.text(scriptRuntimeRef, "scriptRuntimeRef", 128);
        executionProfileDigest = sha256(executionProfileDigest, "executionProfileDigest");
        sandboxDigest = sha256(sandboxDigest, "sandboxDigest");
        capabilities = safeList(capabilities, "capabilities", 64);
        networkHosts = safeList(networkHosts, "networkHosts", 64);
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        revokedAt = Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        reviewerRef = SkillValues.text(reviewerRef, "reviewerRef", 256);
        reviewSourceRef = SkillValues.text(reviewSourceRef, "reviewSourceRef", 256);
        reasonCode = SkillValues.text(reasonCode, "reasonCode", 128);
        if (!coordinate.contentDigest().equals(packageDigest)) {
            throw new IllegalArgumentException("coordinate and package digests differ");
        }
        if (expiresAt.isPresent() && !expiresAt.orElseThrow().isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        if ((state == SkillTrustGrantState.REVOKED) != revokedAt.isPresent()) {
            throw new IllegalArgumentException("revoked state and revokedAt must agree");
        }
        if (revokedAt.isPresent() && revokedAt.orElseThrow().isBefore(issuedAt)) {
            throw new IllegalArgumentException("revokedAt must not precede issuedAt");
        }
        if (scope == SkillTrustScope.PROJECT && projectRef.isEmpty()) {
            throw new IllegalArgumentException("project scope requires projectRef");
        }
        if (scope != SkillTrustScope.PROJECT && projectRef.isPresent()) {
            throw new IllegalArgumentException("only project scope may carry projectRef");
        }
    }

    public boolean activeAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return state == SkillTrustGrantState.ACTIVE
                && revokedAt.isEmpty()
                && !now.isBefore(issuedAt)
                && expiresAt.map(now::isBefore).orElse(true);
    }

    public boolean matches(
            SkillPackageReviewGrant packageGrant,
            FrozenSkillBinding skill,
            FrozenToolBinding tool,
            SkillTrustSubject subject,
            Instant now) {
        Objects.requireNonNull(packageGrant, "packageGrant must not be null");
        Objects.requireNonNull(skill, "skill must not be null");
        Objects.requireNonNull(tool, "tool must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        return activeAt(now)
                && packageReviewGrantId.equals(packageGrant.id())
                && packageGrant.matches(skill, subject, now)
                && tenant.equals(subject.tenant())
                && principal.equals(subject.principal())
                && productId.equals(subject.productId())
                && (scope != SkillTrustScope.PROJECT || projectRef.equals(subject.projectRef()))
                && coordinate.equals(skill.coordinate())
                && registrationDigest.equals(skill.registrationDigest())
                && packageDigest.equals(skill.resourceIndexDigest())
                && toolCoordinate.equals(tool.coordinate())
                && providerBindingReference.equals(tool.providerBindingReference())
                && toolCatalogDigest.equals(tool.catalogDigest());
    }

    private static String sha256(String value, String field) {
        String digest = SkillValues.text(value, field, 71).toLowerCase(java.util.Locale.ROOT);
        if (!digest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        }
        return digest;
    }

    private static List<String> safeList(List<String> values, String field, int maximum) {
        List<String> result = Objects.requireNonNull(values, field + " must not be null").stream()
                .map(value -> SkillValues.text(value, field, 256))
                .sorted()
                .distinct()
                .toList();
        if (result.size() > maximum) throw new IllegalArgumentException(field + " exceeds maximum entries");
        return result;
    }
}
