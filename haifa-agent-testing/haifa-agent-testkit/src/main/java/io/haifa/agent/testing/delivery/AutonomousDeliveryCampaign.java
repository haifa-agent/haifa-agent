package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
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

    public Path initialize(Path runParent, List<Path> repositoryRoots, AutonomousDeliveryCaseCatalog catalog)
            throws IOException {
        return initialize(runParent, repositoryRoots, catalog, List.of());
    }

    public Path initialize(
            Path runParent,
            List<Path> repositoryRoots,
            AutonomousDeliveryCaseCatalog catalog,
            List<Path> historicalBaselineRoots)
            throws IOException {
        Path parent = requireSafeParent(runParent, repositoryRoots);
        List<LinkedHashMap<String, Object>> baselineEntries = new java.util.ArrayList<>();
        for (Path baselineRoot : historicalBaselineRoots) {
            Path baseline = baselineRoot.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(baseline)) {
                throw new IllegalArgumentException("historical baseline must be a directory");
            }
            for (Path repositoryRoot : repositoryRoots) {
                Path repository = repositoryRoot.toAbsolutePath().normalize().toRealPath();
                if (baseline.startsWith(repository) || repository.startsWith(baseline)) {
                    throw new IllegalArgumentException("historical baseline must not overlap a Git repository");
                }
            }
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("baselineId", baseline.getFileName().toString());
            entry.put("contentSha256", Sha256Digests.historicalEvidenceDirectory(baseline));
            entry.put("digestAlgorithm", "TREE_SHA256_V1_IGNORE_DS_STORE");
            entry.put("sourceType", "READ_ONLY_HISTORICAL_CAMPAIGN");
            baselineEntries.add(entry);
        }
        String campaignId = "autonomous-delivery-" + CAMPAIGN_TIME.format(now()) + "-" + randomSuffix();
        Path campaign = parent.resolve(campaignId);
        Files.createDirectory(campaign);
        setOwnerOnly(campaign);
        for (String directory :
                List.of("immutable-input", "baseline", "phase-0", "phase-1", "phase-2", "phase-3", "comparison")) {
            Files.createDirectory(campaign.resolve(directory));
        }
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("campaignId", campaignId);
        manifest.put("createdAt", now().toString());
        manifest.put("catalogId", catalog.catalogId());
        manifest.put("catalogVersion", catalog.catalogVersion());
        manifest.put("catalogSha256", catalog.catalogSha256());
        manifest.put("harnessProtocolVersion", catalog.harnessProtocol().version());
        manifest.put("harnessProtocolSha256", catalog.harnessProtocol().sha256());
        manifest.put("maxParallelExternalCalls", 1);
        manifest.put("historicalCampaignsAreReadOnly", true);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(campaign.resolve("campaign.json").toFile(), manifest);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(
                        campaign.resolve("immutable-input")
                                .resolve("catalog-snapshot.json")
                                .toFile(),
                        catalog.cases());
        json.writerWithDefaultPrettyPrinter()
                .writeValue(
                        campaign.resolve("baseline")
                                .resolve("historical-evidence-index.json")
                                .toFile(),
                        baselineEntries);
        return campaign;
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    static Path requireSafeParent(Path value, List<Path> repositoryRoots) throws IOException {
        Path candidate = Objects.requireNonNull(value, "run parent must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("run parent must be an existing absolute directory");
        }
        Path parent = candidate.toRealPath();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (parent.getParent() == null || parent.equals(home)) {
            throw new IllegalArgumentException("run parent must not be a filesystem root or user home");
        }
        for (Path repositoryRoot : repositoryRoots) {
            Path repository = repositoryRoot.toAbsolutePath().normalize().toRealPath();
            if (parent.startsWith(repository) || repository.startsWith(parent)) {
                throw new IllegalArgumentException("run parent must not overlap a Git repository");
            }
        }
        return parent;
    }

    private String randomSuffix() {
        StringBuilder value = new StringBuilder(8);
        for (int index = 0; index < 8; index++) {
            value.append(SUFFIX_ALPHABET[random.nextInt(SUFFIX_ALPHABET.length)]);
        }
        return value.toString();
    }

    private static void setOwnerOnly(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    directory,
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows ACL isolation is verified in its platform gate.
        }
    }
}
