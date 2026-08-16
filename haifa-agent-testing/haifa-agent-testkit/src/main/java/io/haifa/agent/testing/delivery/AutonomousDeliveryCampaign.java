package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.run.SafeRunRoot;
import io.haifa.agent.testing.suite.ResolvedAgentProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Creates one fail-closed, repository-external autonomous-delivery campaign. */
public final class AutonomousDeliveryCampaign {
    private static final DateTimeFormatter CAMPAIGN_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final char[] SUFFIX_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random;

    public AutonomousDeliveryCampaign() {
        this(new ObjectMapper(), Clock.systemUTC(), new SecureRandom());
    }

    AutonomousDeliveryCampaign(ObjectMapper json, Clock clock, SecureRandom random) {
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    public Path initialize(
            Path runParent,
            List<Path> repositoryRoots,
            AutonomousDeliveryCaseCatalog catalog,
            AutonomousDeliveryMatrixManifest matrix,
            AutonomousDeliveryMatrixManifest.Combination matrixCombination,
            ResolvedAgentProfile agentProfile,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision)
            throws IOException {
        Objects.requireNonNull(matrix, "matrix must not be null");
        Objects.requireNonNull(matrixCombination, "matrixCombination must not be null");
        Objects.requireNonNull(agentProfile, "agentProfile must not be null");
        Objects.requireNonNull(productRevision, "productRevision must not be null")
                .requireClean("product repository");
        Objects.requireNonNull(testConfigRevision, "testConfigRevision must not be null")
                .requireClean("test-config repository");
        Path parent = SafeRunRoot.requireExternalExistingParent(runParent, repositoryRoots, "run parent");
        String campaignId = "autonomous-delivery-" + CAMPAIGN_TIME.format(now()) + "-" + randomSuffix();
        Path campaign = parent.resolve(campaignId);
        Files.createDirectory(campaign);
        SecureFilePermissions.secureDirectory(campaign);
        for (String directory :
                List.of("immutable-input", "phase-0", "phase-1", "phase-2", "phase-3", "platform-gate")) {
            Files.createDirectory(campaign.resolve(directory));
        }
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 4);
        manifest.put("campaignId", campaignId);
        manifest.put("createdAt", now().toString());
        manifest.put("catalogId", catalog.catalogId());
        manifest.put("catalogVersion", catalog.catalogVersion());
        manifest.put("catalogSha256", catalog.catalogSha256());
        manifest.put("harnessProtocolVersion", catalog.harnessProtocol().version());
        manifest.put("harnessProtocolSha256", catalog.harnessProtocol().sha256());
        manifest.put("matrixRef", matrix.matrixId());
        manifest.put("matrixCombination", matrixCombination);
        manifest.put("agentProfile", agentProfile.manifest());
        manifest.put("agentAssemblyDigest", agentProfile.agentAssemblyDigest());
        manifest.put("productRevision", productRevision);
        manifest.put("testConfigRevision", testConfigRevision);
        manifest.put("maxParallelExternalCalls", 1);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(campaign.resolve("campaign.json").toFile(), manifest);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(
                        campaign.resolve("immutable-input")
                                .resolve("catalog-snapshot.json")
                                .toFile(),
                        catalog.cases());
        return campaign;
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    private String randomSuffix() {
        StringBuilder value = new StringBuilder(8);
        for (int index = 0; index < 8; index++) {
            value.append(SUFFIX_ALPHABET[random.nextInt(SUFFIX_ALPHABET.length)]);
        }
        return value.toString();
    }
}
