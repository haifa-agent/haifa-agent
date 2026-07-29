package io.haifa.agent.testing.delivery;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Reads and materializes immutable fixture resources from an exploded classpath or packaged JAR. */
public final class AutonomousDeliveryFixtureStore {
    public static final String CATALOG_RESOURCE = "fixtures/autonomous-delivery/catalog-v1.json";

    private final ClassLoader classLoader;

    public AutonomousDeliveryFixtureStore() {
        this(AutonomousDeliveryFixtureStore.class.getClassLoader());
    }

    AutonomousDeliveryFixtureStore(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader must not be null");
    }

    public byte[] read(String resource) throws IOException {
        try (InputStream input = open(resource)) {
            return input.readAllBytes();
        }
    }

    public String digest(String resource) throws IOException {
        try (InputStream input = open(resource)) {
            return Sha256Digests.stream(input);
        }
    }

    public String directoryDigest(String resourceRoot) throws IOException {
        Path temporary = Files.createTempDirectory("haifa-fixture-digest-");
        try {
            Path materialized = temporary.resolve("workspace");
            materializeDirectory(resourceRoot, materialized);
            return Sha256Digests.directory(materialized);
        } finally {
            deleteRecursively(temporary);
        }
    }

    public void materializeCase(AutonomousDeliveryCase testCase, Path destination) throws IOException {
        Objects.requireNonNull(testCase, "testCase must not be null");
        Path root = createExclusiveDirectory(destination);
        copyResource(testCase.promptResource(), root.resolve("prompt.txt"));
        copyResource(testCase.acceptanceResource(), root.resolve("acceptance.py"));
        materializeDirectory(testCase.workspaceResource(), root.resolve("base-workspace"));
        for (String relative : testCase.executableWorkspacePaths()) {
            setOwnerExecutable(root.resolve("base-workspace").resolve(relative).normalize());
        }
        verifyMaterialized(testCase, root);
    }

    public void materializeDirectory(String resourceRoot, Path destination) throws IOException {
        String prefix = normalizeResource(resourceRoot) + "/";
        Path root = createExclusiveDirectory(destination);
        URL resourceUrl = classLoader.getResource(resourceRoot);
        if (resourceUrl != null && resourceUrl.getProtocol().equals("file")) {
            materializeFileDirectory(resourceUrl, root);
            return;
        }
        URL knownResource = requireResource(CATALOG_RESOURCE);
        if (knownResource.getProtocol().equals("jar")) {
            materializeJarDirectory(knownResource, prefix, root);
            return;
        }
        throw new IOException("fixture directory is not readable from the current classpath");
    }

    private void verifyMaterialized(AutonomousDeliveryCase testCase, Path root) throws IOException {
        if (!Sha256Digests.file(root.resolve("prompt.txt")).equals(testCase.promptSha256())) {
            throw new IOException("materialized prompt digest mismatch for case " + testCase.caseId());
        }
        if (!Sha256Digests.file(root.resolve("acceptance.py")).equals(testCase.acceptanceSha256())) {
            throw new IOException("materialized acceptance digest mismatch for case " + testCase.caseId());
        }
        if (!Sha256Digests.directory(root.resolve("base-workspace")).equals(testCase.workspaceSha256())) {
            throw new IOException("materialized workspace digest mismatch for case " + testCase.caseId());
        }
    }

    private void copyResource(String resource, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try (InputStream input = open(resource)) {
            Files.copy(input, destination);
        }
    }

    private void materializeFileDirectory(URL resourceUrl, Path destination) throws IOException {
        try {
            Path source = Path.of(resourceUrl.toURI());
            try (var paths = Files.walk(source)) {
                for (Path path : paths.sorted().toList()) {
                    Path target = destination
                            .resolve(source.relativize(path).toString())
                            .normalize();
                    requireInside(destination, target);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else if (Files.isRegularFile(path)) {
                        Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                    } else {
                        throw new IOException("fixture contains unsupported filesystem entry");
                    }
                }
            }
        } catch (URISyntaxException exception) {
            throw new IOException("fixture resource URI is invalid", exception);
        }
    }

    private void materializeJarDirectory(URL knownResource, String prefix, Path destination) throws IOException {
        JarURLConnection connection = (JarURLConnection) knownResource.openConnection();
        boolean found = false;
        try (JarFile jar = connection.getJarFile()) {
            for (JarEntry entry : jar.stream()
                    .filter(value -> !value.isDirectory())
                    .filter(value -> value.getName().startsWith(prefix))
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .toList()) {
                String relative = entry.getName().substring(prefix.length());
                if (relative.isBlank()) {
                    continue;
                }
                Path target = destination.resolve(relative).normalize();
                requireInside(destination, target);
                Files.createDirectories(target.getParent());
                try (InputStream input = jar.getInputStream(entry)) {
                    Files.copy(input, target);
                }
                found = true;
            }
        }
        if (!found) {
            throw new IOException("fixture directory contains no files: " + prefix);
        }
    }

    private InputStream open(String resource) throws IOException {
        URL url = requireResource(normalizeResource(resource));
        return url.openStream();
    }

    private URL requireResource(String resource) throws IOException {
        URL url = classLoader.getResource(resource);
        if (url == null) {
            throw new IOException("fixture resource is unavailable: " + resource);
        }
        return url;
    }

    private static Path createExclusiveDirectory(Path value) throws IOException {
        Path destination = Objects.requireNonNull(value, "destination must not be null")
                .toAbsolutePath()
                .normalize();
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("destination must have a parent");
        }
        Files.createDirectories(parent);
        Files.createDirectory(destination);
        setOwnerOnlyPermissions(destination);
        return destination;
    }

    private static void setOwnerOnlyPermissions(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    directory,
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows ACL validation belongs to the Windows gate.
        }
    }

    private static void setOwnerExecutable(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("declared executable fixture file is missing");
        }
        try {
            var permissions = Files.getPosixFilePermissions(file);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows execution is performed through an explicit interpreter.
        }
    }

    private static void requireInside(Path root, Path candidate) throws IOException {
        if (!candidate.startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException("fixture entry escapes destination");
        }
    }

    private static String normalizeResource(String value) {
        if (value == null
                || value.isBlank()
                || value.startsWith("/")
                || value.contains("\\")
                || value.contains("..")
                || value.contains("//")) {
            throw new IllegalArgumentException("resource must be a safe classpath-relative path");
        }
        return value;
    }

    static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
