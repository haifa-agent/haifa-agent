package io.haifa.agent.testing.suite;

import io.haifa.agent.testing.repository.RepositoryRevision;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Stable digest of every reviewed input that can change a Critical Path execution. */
public record SuiteExecutionPlanFingerprint(int schemaVersion, String sha256) {
    private static final String DOMAIN = "haifa-critical-path-execution-plan-v2";

    public SuiteExecutionPlanFingerprint {
        if (schemaVersion != 2) {
            throw new IllegalArgumentException("execution plan fingerprint schemaVersion must be 2");
        }
        sha256 = Objects.requireNonNull(sha256, "sha256 must not be null");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be lowercase SHA-256");
        }
    }

    public static SuiteExecutionPlanFingerprint create(
            SuiteManifest manifest,
            MatrixManifest.Combination combination,
            ResolvedAgentProfile agentProfile,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(combination, "combination must not be null");
        Objects.requireNonNull(agentProfile, "agentProfile must not be null");
        Objects.requireNonNull(productRevision, "productRevision must not be null");
        Objects.requireNonNull(testConfigRevision, "testConfigRevision must not be null");
        MessageDigest digest = sha256Digest();
        add(digest, DOMAIN);
        add(digest, productRevision.commit());
        add(digest, testConfigRevision.commit());
        add(digest, manifest.schemaVersion());
        add(digest, manifest.suiteId());
        add(digest, manifest.matrixRef());
        add(digest, manifest.budget().maxWallTimeMinutes());
        add(digest, Double.toHexString(manifest.budget().maxEstimatedCostUsd()));
        add(digest, manifest.budget().maxParallelExternalCalls());
        add(digest, combination.id());
        add(digest, combination.platform());
        add(digest, agentProfile.profileId());
        add(digest, agentProfile.manifest().compatibleAgentBaselineCommit());
        add(digest, agentProfile.manifest().configurationRef());
        add(digest, agentProfile.manifest().configurationSha256());
        add(digest, agentProfile.agentAssemblyDigest());
        add(digest, agentProfile.credentialEnvironmentNames().size());
        agentProfile.credentialEnvironmentNames().forEach(value -> add(digest, value));
        add(digest, manifest.cases().size());
        for (SuiteManifest.CaseSelection selection : manifest.cases()) {
            CriticalPathCase testCase = CriticalPathCatalog.require(selection.caseId());
            add(digest, selection.caseId());
            add(digest, selection.repetitions());
            add(digest, selection.blocking());
            add(digest, testCase.title());
            add(digest, testCase.scope().name());
            add(digest, testCase.module());
            add(digest, testCase.testSelector());
            add(digest, testCase.live());
        }
        return new SuiteExecutionPlanFingerprint(2, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void add(MessageDigest digest, Object value) {
        byte[] encoded = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
        digest.update(encoded);
    }
}
