package io.haifa.agent.testing.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixturePackageCatalogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void registersOnePackageAndCoversOrdinaryFilesWithOneDigest() throws Exception {
        Path packageRoot = temporaryDirectory.resolve("sample");
        Files.createDirectories(packageRoot.resolve("workspace"));
        Files.createDirectories(packageRoot.resolve("acceptance"));
        Files.writeString(packageRoot.resolve("workspace/input.txt"), "safe input");
        Files.writeString(packageRoot.resolve("acceptance/check.txt"), "safe check");
        String digest = FixturePackageCatalog.digest(packageRoot);
        Files.writeString(packageRoot.resolve("fixture.yaml"), manifest(digest));

        var packages = new FixturePackageCatalog().scan(temporaryDirectory);

        assertEquals(1, packages.size());
        assertEquals(digest, packages.get(new FixtureReference("sample-v1", 1)).sha256());
    }

    @Test
    void rejectsDigestDriftAndPathEscape() throws Exception {
        Path packageRoot = temporaryDirectory.resolve("sample");
        Files.createDirectories(packageRoot.resolve("workspace"));
        Files.createDirectories(packageRoot.resolve("acceptance"));
        Files.writeString(packageRoot.resolve("workspace/input.txt"), "first");
        Files.writeString(packageRoot.resolve("fixture.yaml"), manifest(FixturePackageCatalog.digest(packageRoot)));
        Files.writeString(packageRoot.resolve("workspace/input.txt"), "changed");

        assertThrows(IllegalArgumentException.class, () -> new FixturePackageCatalog().scan(temporaryDirectory));

        Files.writeString(
                packageRoot.resolve("fixture.yaml"),
                manifest(FixturePackageCatalog.digest(packageRoot)).replace("workspace: workspace", "workspace: ../"));
        assertThrows(IllegalArgumentException.class, () -> new FixturePackageCatalog().scan(temporaryDirectory));
    }

    private static String manifest(String digest) {
        return """
                schemaVersion: 1
                id: sample-v1
                version: 1
                workspace: workspace
                acceptance: acceptance
                license: repository-owned
                contentSha256: %s
                """
                .formatted(digest);
    }
}
