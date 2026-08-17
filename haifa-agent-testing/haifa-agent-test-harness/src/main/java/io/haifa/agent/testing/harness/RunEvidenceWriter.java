package io.haifa.agent.testing.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.testing.evidence.EvidenceFinalizer;
import io.haifa.agent.testing.evidence.EvidenceSecretScanner;
import io.haifa.agent.testing.repository.RepositoryRevision;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes and finalizes the single common evidence envelope for every suite. */
public final class RunEvidenceWriter {
    private final ObjectMapper json = new ObjectMapper();

    public RunEvidenceWriter() {}

    public PublishedRun write(
            ResolvedRunContext context,
            NativeResult nativeResult,
            BigDecimal budgetApproval,
            RepositoryRevision productAfter,
            RepositoryRevision testConfigAfter)
            throws Exception {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(nativeResult, "nativeResult must not be null");
        BigDecimal approved = Objects.requireNonNull(budgetApproval, "budgetApproval must not be null");
        if (approved.signum() <= 0) throw new IllegalArgumentException("budgetApproval must be positive");

        Objects.requireNonNull(productAfter, "productAfter must not be null");
        Objects.requireNonNull(testConfigAfter, "testConfigAfter must not be null");
        boolean repositoryStateStable = context.productRevision().equals(productAfter)
                && context.testConfigRevision().equals(testConfigAfter);
        EvidenceSecretScanner.Result secretScan =
                EvidenceSecretScanner.scan(nativeResult.evidenceRoot(), nativeResult.evidenceSecrets());
        writeJson(nativeResult.evidenceRoot().resolve("secret-scan.json"), secretScan);

        boolean evidencePassed = secretScan.passed() && repositoryStateStable;
        boolean successful = nativeResult.successful() && evidencePassed;
        String failureClassification = evidencePassed ? nativeResult.failureClassification() : "EVIDENCE_FAILED";
        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 2);
        envelope.put("suiteType", context.approvedDocument().suiteType());
        envelope.put("status", successful ? "PASSED" : "FAILED");
        envelope.put("nativeStatus", nativeResult.nativeStatus());
        envelope.put("failureClassification", failureClassification);
        envelope.put("successful", successful);
        envelope.put("planSha256", context.approvedDocument().plan().sha256());
        envelope.put("runnerSha256", context.approvedDocument().runnerArtifact().sha256());
        envelope.put("startedAt", nativeResult.startedAt().toEpochMilli());
        envelope.put("finishedAt", nativeResult.finishedAt().toEpochMilli());
        envelope.put("productRevision", context.productRevision());
        envelope.put("testConfigRevision", context.testConfigRevision());
        envelope.put("productRevisionAfter", productAfter);
        envelope.put("testConfigRevisionAfter", testConfigAfter);
        envelope.put("repositoryStateStable", repositoryStateStable);
        envelope.put("budgetApproval", approved.stripTrailingZeros().toPlainString());
        envelope.put("usageSummary", nativeResult.usageSummary());
        envelope.put("attachments", nativeResult.attachments());
        envelope.put("secretScanRef", "secret-scan.json");
        envelope.put("evidenceManifest", "manifest.sha256");
        envelope.put("nativeResult", nativeResult.nativeResult());
        writeJson(nativeResult.evidenceRoot().resolve("run-result.json"), envelope);
        EvidenceFinalizer.finalizeEvidence(nativeResult.evidenceRoot());

        context.productRevision().requireUnchanged(productAfter, "product repository");
        context.testConfigRevision().requireUnchanged(testConfigAfter, "test-config repository");
        return new PublishedRun(nativeResult.evidenceRoot(), successful, failureClassification);
    }

    private void writeJson(Path path, Object value) throws IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    public record NativeResult(
            Path evidenceRoot,
            Instant startedAt,
            Instant finishedAt,
            String nativeStatus,
            String failureClassification,
            boolean successful,
            Map<String, Object> usageSummary,
            List<EvidenceAttachment> attachments,
            Map<String, Object> nativeResult,
            List<String> evidenceSecrets) {
        public NativeResult {
            evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(evidenceRoot)) {
                throw new IllegalArgumentException("evidenceRoot must be an existing directory");
            }
            usageSummary = Map.copyOf(usageSummary);
            attachments = List.copyOf(attachments);
            nativeResult = Map.copyOf(nativeResult);
            evidenceSecrets = List.copyOf(evidenceSecrets);
        }
    }

    public record PublishedRun(Path evidenceRoot, boolean successful, String failureClassification) {}
}
