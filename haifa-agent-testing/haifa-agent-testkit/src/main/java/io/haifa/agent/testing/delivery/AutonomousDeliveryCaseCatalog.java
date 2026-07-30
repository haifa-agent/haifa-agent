package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Loads and verifies the versioned public catalog and all immutable fixture digests. */
public final class AutonomousDeliveryCaseCatalog {
    public static final String EXPECTED_CATALOG_ID = "generalized-coding-v1";
    public static final String EXPECTED_PROTOCOL_VERSION = "1.0.0";

    private final String catalogId;
    private final String catalogVersion;
    private final String catalogSha256;
    private final HarnessProtocol harnessProtocol;
    private final Map<String, AutonomousDeliveryCase> cases;

    private AutonomousDeliveryCaseCatalog(
            String catalogId,
            String catalogVersion,
            String catalogSha256,
            HarnessProtocol harnessProtocol,
            Map<String, AutonomousDeliveryCase> cases) {
        this.catalogId = catalogId;
        this.catalogVersion = catalogVersion;
        this.catalogSha256 = catalogSha256;
        this.harnessProtocol = harnessProtocol;
        this.cases = Collections.unmodifiableMap(new LinkedHashMap<>(cases));
    }

    public static AutonomousDeliveryCaseCatalog loadVerified() {
        return loadVerified(new AutonomousDeliveryFixtureStore());
    }

    static AutonomousDeliveryCaseCatalog loadVerified(AutonomousDeliveryFixtureStore fixtures) {
        Objects.requireNonNull(fixtures, "fixtures must not be null");
        try {
            byte[] catalogBytes = fixtures.read(AutonomousDeliveryFixtureStore.CATALOG_RESOURCE);
            CatalogDocument document = new ObjectMapper().readValue(catalogBytes, CatalogDocument.class);
            validateDocument(document);
            if (!fixtures.digest(document.harnessProtocol().resource())
                    .equals(document.harnessProtocol().sha256())) {
                throw new IllegalArgumentException("harness protocol digest does not match catalog");
            }
            LinkedHashMap<String, AutonomousDeliveryCase> cases = new LinkedHashMap<>();
            for (AutonomousDeliveryCase testCase : document.cases()) {
                verifyCase(fixtures, testCase);
                if (cases.put(testCase.caseId(), testCase) != null) {
                    throw new IllegalArgumentException("duplicate autonomous-delivery case " + testCase.caseId());
                }
            }
            List<String> expectedIds = java.util.stream.IntStream.rangeClosed(1, 17)
                    .mapToObj(value -> "%02d".formatted(value))
                    .toList();
            if (!List.copyOf(cases.keySet()).equals(expectedIds)) {
                throw new IllegalArgumentException("catalog must contain exactly cases 01 through 17");
            }
            return new AutonomousDeliveryCaseCatalog(
                    document.catalogId(),
                    document.catalogVersion(),
                    Sha256Digests.bytes(catalogBytes),
                    document.harnessProtocol(),
                    cases);
        } catch (IOException exception) {
            throw new IllegalStateException("autonomous-delivery catalog cannot be loaded", exception);
        }
    }

    public String catalogId() {
        return catalogId;
    }

    public String catalogVersion() {
        return catalogVersion;
    }

    public String catalogSha256() {
        return catalogSha256;
    }

    public HarnessProtocol harnessProtocol() {
        return harnessProtocol;
    }

    public List<AutonomousDeliveryCase> cases() {
        return List.copyOf(cases.values());
    }

    public AutonomousDeliveryCase require(String caseId) {
        AutonomousDeliveryCase value = cases.get(caseId);
        if (value == null) {
            throw new IllegalArgumentException("unknown autonomous-delivery case: " + caseId);
        }
        return value;
    }

    private static void validateDocument(CatalogDocument document) {
        if (document.schemaVersion() != 1) {
            throw new IllegalArgumentException("unsupported autonomous-delivery catalog schema");
        }
        if (!EXPECTED_CATALOG_ID.equals(document.catalogId())) {
            throw new IllegalArgumentException("unexpected autonomous-delivery catalog id");
        }
        if (document.catalogVersion() == null || document.catalogVersion().isBlank()) {
            throw new IllegalArgumentException("catalog version must not be blank");
        }
        if (!EXPECTED_PROTOCOL_VERSION.equals(document.harnessProtocol().version())) {
            throw new IllegalArgumentException("unexpected harness protocol version");
        }
        if (document.cases() == null) {
            throw new IllegalArgumentException("catalog cases must not be null");
        }
    }

    private static void verifyCase(AutonomousDeliveryFixtureStore fixtures, AutonomousDeliveryCase testCase)
            throws IOException {
        if (!fixtures.digest(testCase.promptResource()).equals(testCase.promptSha256())) {
            throw new IllegalArgumentException("prompt digest mismatch for case " + testCase.caseId());
        }
        if (!fixtures.digest(testCase.acceptanceResource()).equals(testCase.acceptanceSha256())) {
            throw new IllegalArgumentException("acceptance digest mismatch for case " + testCase.caseId());
        }
        if (!fixtures.directoryDigest(testCase.workspaceResource()).equals(testCase.workspaceSha256())) {
            throw new IllegalArgumentException("workspace digest mismatch for case " + testCase.caseId());
        }
    }

    public record HarnessProtocol(String version, String resource, String sha256) {
        public HarnessProtocol {
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("harness protocol version must not be blank");
            }
            if (resource == null || resource.isBlank()) {
                throw new IllegalArgumentException("harness protocol resource must not be blank");
            }
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("harness protocol digest must be lowercase SHA-256");
            }
        }
    }

    private record CatalogDocument(
            int schemaVersion,
            String catalogId,
            String catalogVersion,
            HarnessProtocol harnessProtocol,
            List<AutonomousDeliveryCase> cases) {}
}
