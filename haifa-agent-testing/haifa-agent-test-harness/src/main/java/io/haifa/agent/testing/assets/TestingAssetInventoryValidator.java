package io.haifa.agent.testing.assets;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** Validates versioned testing asset inventories before suite planning or execution. */
public final class TestingAssetInventoryValidator {
    private static final int SCHEMA_VERSION = 2;

    private final ObjectMapper json = new ObjectMapper();

    public void validateIfPresent(Path repositoryRoot, Path inventoryPath) throws IOException {
        Path root = Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null")
                .toAbsolutePath()
                .normalize();
        Path inventory = Objects.requireNonNull(inventoryPath, "inventoryPath must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.exists(inventory)) {
            return;
        }
        if (!inventory.startsWith(root) || !Files.isRegularFile(inventory)) {
            throw new IllegalArgumentException("asset inventory must be a regular file within its repository root");
        }

        Inventory model = json.readValue(inventory.toFile(), Inventory.class);
        validateModel(root, model);
    }

    private static void validateModel(Path repositoryRoot, Inventory inventory) throws IOException {
        if (inventory.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported testing asset inventory schema: " + inventory.schemaVersion());
        }
        requireText(inventory.repositoryId(), "repositoryId");
        List<Asset> assets = List.copyOf(Objects.requireNonNull(inventory.assets(), "assets must not be null"));
        if (assets.isEmpty()) {
            throw new IllegalArgumentException("testing asset inventory must contain at least one asset");
        }

        Set<String> assetIds = new HashSet<>();
        Set<Path> assetPaths = new HashSet<>();
        Map<Path, CoverageMode> coverageModes = new HashMap<>();
        for (Asset asset : assets) {
            requireText(asset.assetId(), "assetId");
            if (!assetIds.add(asset.assetId())) {
                throw new IllegalArgumentException("duplicate testing asset id: " + asset.assetId());
            }
            Path assetPath = resolveRepositoryPath(repositoryRoot, asset.path(), "asset " + asset.assetId());
            if (!assetPaths.add(assetPath)) {
                throw new IllegalArgumentException("duplicate testing asset path: " + asset.path());
            }
            Objects.requireNonNull(asset.kind(), "asset kind must not be null: " + asset.assetId());
            Objects.requireNonNull(asset.lifecycle(), "asset lifecycle must not be null: " + asset.assetId());
            Objects.requireNonNull(asset.disposition(), "asset disposition must not be null: " + asset.assetId());
            requireText(asset.owner(), "asset owner");
            requireText(asset.rationale(), "asset rationale");
            validateLifecycle(asset, assetPath);
            CoverageMode coverageMode = coverageMode(asset);
            validateCoverageMode(asset, assetPath, coverageMode);
            coverageModes.put(assetPath, coverageMode);
            for (String reference : safeList(asset.referencedBy())) {
                Path referencePath =
                        resolveRepositoryPath(repositoryRoot, reference, "reference for " + asset.assetId());
                if (!Files.exists(referencePath)) {
                    throw new IllegalArgumentException(
                            "testing asset reference is unavailable: " + asset.assetId() + " -> " + reference);
                }
            }
        }

        for (String coverageRoot : safeList(inventory.coverageRoots())) {
            Path root = resolveRepositoryPath(repositoryRoot, coverageRoot, "coverage root");
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("testing asset coverage root is unavailable: " + coverageRoot);
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> !containsSegment(root.relativize(path), "target"))
                        .filter(path -> !containsSegment(root.relativize(path), ".git"))
                        .toList()) {
                    boolean covered =
                            assetPaths.stream().anyMatch(asset -> covers(file, asset, coverageModes.get(asset)));
                    if (!covered) {
                        throw new IllegalArgumentException("testing asset is not inventoried: "
                                + repositoryRoot.relativize(file).toString().replace('\\', '/'));
                    }
                }
            }
        }
    }

    private static CoverageMode coverageMode(Asset asset) {
        return asset.coverageMode() == null ? CoverageMode.EXACT : asset.coverageMode();
    }

    private static void validateCoverageMode(Asset asset, Path assetPath, CoverageMode coverageMode) {
        if (coverageMode == CoverageMode.EXACT) {
            return;
        }
        if (asset.lifecycle() == Lifecycle.REMOVED || !Files.isDirectory(assetPath)) {
            throw new IllegalArgumentException(
                    "SUBTREE testing asset must be an available directory: " + asset.assetId());
        }
        if (safeList(asset.referencedBy()).isEmpty()) {
            throw new IllegalArgumentException(
                    "SUBTREE testing asset must have at least one reference: " + asset.assetId());
        }
    }

    private static boolean covers(Path file, Path asset, CoverageMode coverageMode) {
        return file.equals(asset) || (coverageMode == CoverageMode.SUBTREE && file.startsWith(asset));
    }

    private static void validateLifecycle(Asset asset, Path assetPath) {
        boolean exists = materiallyExists(assetPath);
        if (asset.lifecycle() == Lifecycle.REMOVED && exists) {
            throw new IllegalArgumentException("removed testing asset still exists: " + asset.path());
        }
        if (asset.lifecycle() != Lifecycle.REMOVED && !exists) {
            throw new IllegalArgumentException("inventoried testing asset is unavailable: " + asset.path());
        }
        if (asset.lifecycle() == Lifecycle.ACTIVE && asset.disposition() != Disposition.KEEP) {
            throw new IllegalArgumentException("active testing asset must have KEEP disposition: " + asset.assetId());
        }
        if (asset.lifecycle() == Lifecycle.REMOVED && asset.disposition() == Disposition.KEEP) {
            throw new IllegalArgumentException(
                    "removed testing asset cannot have KEEP disposition: " + asset.assetId());
        }
    }

    private static boolean materiallyExists(Path path) {
        if (Files.isRegularFile(path)) {
            return true;
        }
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (Stream<Path> children = Files.walk(path)) {
            return children.anyMatch(Files::isRegularFile);
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot inspect testing asset path: " + path, exception);
        }
    }

    private static Path resolveRepositoryPath(Path repositoryRoot, String value, String field) {
        requireText(value, field);
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(field + " must use repository-relative forward slashes: " + value);
        }
        Path relative = Path.of(value).normalize();
        if (relative.isAbsolute()
                || relative.startsWith("..")
                || relative.toString().isBlank()) {
            throw new IllegalArgumentException(field + " must stay within its repository: " + value);
        }
        Path resolved = repositoryRoot.resolve(relative).normalize();
        if (!resolved.startsWith(repositoryRoot)) {
            throw new IllegalArgumentException(field + " escapes its repository: " + value);
        }
        return resolved;
    }

    private static boolean containsSegment(Path path, String expected) {
        for (Path segment : path) {
            if (segment.toString().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public record Inventory(int schemaVersion, String repositoryId, List<String> coverageRoots, List<Asset> assets) {}

    public record Asset(
            String assetId,
            String path,
            Kind kind,
            Lifecycle lifecycle,
            Disposition disposition,
            String owner,
            List<String> referencedBy,
            String replacement,
            String rationale,
            CoverageMode coverageMode) {}

    public enum CoverageMode {
        EXACT,
        SUBTREE
    }

    public enum Kind {
        MODULE,
        TEST_CODE,
        FIXTURE,
        ENVIRONMENT,
        SUITE,
        MATRIX,
        SCRIPT,
        GUIDE,
        MANIFEST,
        README,
        DIRECTORY
    }

    public enum Lifecycle {
        ACTIVE,
        DEPRECATED,
        REMOVED
    }

    public enum Disposition {
        KEEP,
        DELETE,
        CONSOLIDATE,
        MIGRATE
    }
}
