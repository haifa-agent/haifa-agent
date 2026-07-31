package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.testing.evidence.Sha256Digests;
import io.haifa.agent.testing.repository.RepositoryRevision;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Canonical, revision-bound live execution plan used for explicit authorization. */
final class AutonomousDeliveryExecutionPlan {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AutonomousDeliveryExecutionPlan() {}

    static Frozen freeze(
            AutonomousDeliveryCaseCatalog catalog,
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryMatrixManifest matrix,
            AutonomousDeliveryMatrixManifest.Combination combination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(suite, "suite must not be null");
        Objects.requireNonNull(matrix, "matrix must not be null");
        Objects.requireNonNull(combination, "combination must not be null");
        LinkedHashMap<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", 1);
        plan.put("catalogId", catalog.catalogId());
        plan.put("catalogVersion", catalog.catalogVersion());
        plan.put("catalogSha256", catalog.catalogSha256());
        plan.put("suiteId", suite.suiteId());
        plan.put("phase", suite.phase());
        plan.put("budget", suite.budget());
        plan.put(
                "cases",
                suite.cases().stream()
                        .map(selection -> caseEntry(catalog, selection))
                        .toList());
        plan.put("matrixId", matrix.matrixId());
        plan.put("matrixCompatibleAgentBaselineCommit", matrix.compatibleAgentBaselineCommit());
        plan.put("matrixCombination", combination);
        plan.put("productCommit", productRevision.commit());
        plan.put("testConfigCommit", testConfigRevision.commit());
        try {
            byte[] canonical = JSON.writeValueAsBytes(plan);
            return new Frozen(Map.copyOf(plan), Sha256Digests.bytes(canonical));
        } catch (IOException exception) {
            throw new IllegalStateException("execution plan could not be serialized", exception);
        }
    }

    private static Map<String, Object> caseEntry(
            AutonomousDeliveryCaseCatalog catalog, AutonomousDeliverySuiteManifest.CaseSelection selection) {
        LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
        entry.put("caseId", selection.caseId());
        entry.put("caseVersion", catalog.require(selection.caseId()).caseVersion());
        entry.put("repetitions", selection.repetitions());
        entry.put("blocking", selection.blocking());
        return java.util.Collections.unmodifiableMap(entry);
    }

    record Frozen(Map<String, Object> content, String sha256) {
        Frozen {
            content = Map.copyOf(Objects.requireNonNull(content, "content must not be null"));
            Objects.requireNonNull(sha256, "sha256 must not be null");
        }

        Map<String, Object> artifact() {
            LinkedHashMap<String, Object> artifact = new LinkedHashMap<>(content);
            artifact.put("planSha256", sha256);
            return Map.copyOf(artifact);
        }
    }
}
