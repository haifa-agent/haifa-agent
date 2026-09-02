package io.haifa.agent.testing.fixtures;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.haifa.agent.testing.evidence.Sha256Digests;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scans fixture.yaml packages and verifies one normalized digest per package. */
public final class FixturePackageCatalog {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public Map<FixtureReference, PackageDescriptor> scan(Path fixturesRoot) throws IOException {
        Path root = fixturesRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("fixture packages root must be a directory");
        LinkedHashMap<FixtureReference, PackageDescriptor> packages = new LinkedHashMap<>();
        try (var files = Files.walk(root)) {
            List<Path> manifests = files.filter(
                            path -> path.getFileName().toString().equals("fixture.yaml"))
                    .sorted()
                    .toList();
            for (Path manifestPath : manifests) {
                FixturePackageManifest manifest = readManifest(manifestPath);
                Path packageRoot = manifestPath.getParent().toAbsolutePath().normalize();
                requireContainedDirectory(packageRoot, manifest.workspace(), "workspace");
                requireContainedDirectory(packageRoot, manifest.acceptance(), "acceptance");
                String digest = digest(packageRoot);
                if (!digest.equals(manifest.contentSha256())) {
                    throw new IllegalArgumentException("fixture package digest does not match: " + manifest.id()
                            + ", expected=" + manifest.contentSha256() + ", actual=" + digest);
                }
                FixtureReference reference = new FixtureReference(manifest.id(), manifest.version());
                if (packages.put(reference, new PackageDescriptor(manifest, packageRoot, digest)) != null) {
                    throw new IllegalArgumentException("duplicate fixture package: " + reference.id());
                }
            }
        }
        return Map.copyOf(packages);
    }

    public PackageDescriptor require(Path fixturesRoot, FixtureReference reference) throws IOException {
        PackageDescriptor descriptor = scan(fixturesRoot).get(reference);
        if (descriptor == null) throw new IllegalArgumentException("fixture package is unavailable: " + reference.id());
        return descriptor;
    }

    private FixturePackageManifest readManifest(Path manifestPath) {
        try {
            return yaml.readValue(manifestPath.toFile(), FixturePackageManifest.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("fixture package manifest cannot be parsed", exception);
        }
    }

    public static String digest(Path packageRoot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes);
                var files = Files.walk(packageRoot)) {
            List<Path> content = files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.getFileName().toString().equals("fixture.yaml"))
                    .sorted(Comparator.comparing(
                            path -> packageRoot.relativize(path).toString().replace('\\', '/')))
                    .toList();
            for (Path file : content) {
                if (Files.isSymbolicLink(file))
                    throw new IllegalArgumentException("fixture packages cannot use symlinks");
                String relative = packageRoot.relativize(file).toString().replace('\\', '/');
                byte[] pathBytes = relative.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] contentBytes = Files.readAllBytes(file);
                output.writeInt(pathBytes.length);
                output.write(pathBytes);
                output.writeLong(contentBytes.length);
                output.write(contentBytes);
            }
        }
        return Sha256Digests.bytes(bytes.toByteArray());
    }

    private static void requireContainedDirectory(Path root, String relative, String field) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || !Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("fixture " + field + " must be a contained directory");
        }
    }

    public record PackageDescriptor(FixturePackageManifest manifest, Path root, String sha256) {}
}
