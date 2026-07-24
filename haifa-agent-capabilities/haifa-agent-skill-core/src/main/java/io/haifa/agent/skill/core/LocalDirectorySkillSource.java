package io.haifa.agent.skill.core;

import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.skill.api.SkillDiagnostic;
import io.haifa.agent.skill.api.SkillDiagnosticSeverity;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillDiscoveryResult;
import io.haifa.agent.skill.api.SkillRegistration;
import io.haifa.agent.skill.api.SkillSource;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Reads only an application-configured root; the root is never accepted from a model or Run request. */
public final class LocalDirectorySkillSource implements SkillSource {
    private static final int MAX_DISCOVERY_DEPTH = 8;
    private static final int MAX_DISCOVERY_DIRECTORIES = 4_096;
    private static final int MAX_DISCOVERED_PACKAGES = 1_024;

    private final Path root;
    private final SkillSourceDescriptor descriptor;
    private final SkillPackageParser parser;
    private final SkillAvailability configuredAvailability;
    private final Map<String, Path> locations = new ConcurrentHashMap<>();

    public LocalDirectorySkillSource(
            Path root,
            SkillSourceDescriptor descriptor,
            SkillPackageParser parser,
            SkillAvailability configuredAvailability) {
        this.root = root.toAbsolutePath().normalize();
        this.descriptor = java.util.Objects.requireNonNull(descriptor);
        this.parser = java.util.Objects.requireNonNull(parser);
        this.configuredAvailability = java.util.Objects.requireNonNull(configuredAvailability);
    }

    @Override
    public SkillSourceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public SkillDiscoveryResult discover(SkillDiscoveryContext context) {
        if (!visible(context.visibility())) return SkillDiscoveryResult.empty();
        List<SkillRegistration> registrations = new ArrayList<>();
        List<SkillDiagnostic> diagnostics = new ArrayList<>();
        locations.clear();
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                diagnostics.add(diagnostic("SKILL_SOURCE_UNAVAILABLE", "configured Skill source is unavailable"));
                return new SkillDiscoveryResult(registrations, diagnostics);
            }
            List<Path> packages = discoverPackages(diagnostics);
            for (Path packageRoot : packages) {
                SkillPackageParseResult result = parser.parseDirectory(packageRoot, descriptor);
                diagnostics.addAll(result.diagnostics());
                result.parsed().ifPresent(parsed -> {
                    SkillRegistration registration = SkillRegistrations.create(
                            parsed,
                            descriptor,
                            root.relativize(packageRoot).toString().replace('\\', '/'),
                            configuredAvailability);
                    registrations.add(registration);
                    locations.put(registration.coordinate().externalForm(), packageRoot);
                });
            }
            return new SkillDiscoveryResult(registrations, diagnostics);
        } catch (IOException exception) {
            diagnostics.add(diagnostic("SKILL_SOURCE_READ_FAILED", "configured Skill source could not be listed"));
            return new SkillDiscoveryResult(registrations, diagnostics);
        }
    }

    private List<Path> discoverPackages(List<SkillDiagnostic> diagnostics) throws IOException {
        List<Path> packages = new ArrayList<>();
        ArrayDeque<DiscoveryDirectory> pending = new ArrayDeque<>();
        pending.add(new DiscoveryDirectory(root, 0));
        int visitedDirectories = 0;
        boolean depthDiagnosticAdded = false;
        while (!pending.isEmpty()) {
            DiscoveryDirectory current = pending.removeFirst();
            if (++visitedDirectories > MAX_DISCOVERY_DIRECTORIES) {
                diagnostics.add(diagnostic(
                        "SKILL_SOURCE_DIRECTORY_LIMIT_EXCEEDED",
                        "configured Skill source contains too many directories"));
                break;
            }
            if (current.depth() > 0) {
                Path entrypoint = current.path().resolve("SKILL.md");
                if (Files.isSymbolicLink(entrypoint)) {
                    diagnostics.add(diagnostic("SKILL_SYMLINK_REJECTED", "symbolic Skill entrypoints are not allowed"));
                    continue;
                }
                if (Files.isRegularFile(entrypoint, LinkOption.NOFOLLOW_LINKS)) {
                    if (packages.size() >= MAX_DISCOVERED_PACKAGES) {
                        diagnostics.add(diagnostic(
                                "SKILL_SOURCE_PACKAGE_LIMIT_EXCEEDED",
                                "configured Skill source contains too many packages"));
                        break;
                    }
                    packages.add(current.path());
                    continue;
                }
            }
            if (current.depth() >= MAX_DISCOVERY_DEPTH) {
                if (!depthDiagnosticAdded && hasNestedDirectory(current.path())) {
                    diagnostics.add(diagnostic(
                            "SKILL_SOURCE_DEPTH_EXCEEDED", "configured Skill source exceeds the discovery depth"));
                    depthDiagnosticAdded = true;
                }
                continue;
            }
            try (var children = Files.list(current.path())) {
                children.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> {
                            if (Files.isSymbolicLink(path)) {
                                diagnostics.add(diagnostic(
                                        "SKILL_SYMLINK_REJECTED",
                                        "symbolic entries in a configured Skill source are not allowed"));
                            } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                                pending.addLast(new DiscoveryDirectory(path, current.depth() + 1));
                            }
                        });
            }
        }
        return packages.stream()
                .sorted(Comparator.comparing(
                        path -> root.relativize(path).toString().replace('\\', '/')))
                .toList();
    }

    private static boolean hasNestedDirectory(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            return children.anyMatch(
                    path -> Files.isSymbolicLink(path) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
        }
    }

    @Override
    public SkillContent load(FrozenSkillBinding binding, SkillVisibilityContext context) {
        requireVisible(binding, context);
        Path packageRoot = locations.get(binding.coordinate().externalForm());
        if (packageRoot == null) throw new IllegalStateException("frozen Skill was not discovered from this source");
        ParsedSkillPackage parsed = parser.parseDirectory(packageRoot, descriptor)
                .parsed()
                .orElseThrow(() -> new IllegalStateException("frozen Skill package is no longer valid"));
        return exactContent(binding, parsed);
    }

    private boolean visible(SkillVisibilityContext context) {
        return descriptor.scope().visibleTo(context)
                && (!descriptor.projectTrustRequired() || context.projectTrusted());
    }

    private void requireVisible(FrozenSkillBinding binding, SkillVisibilityContext context) {
        if (!descriptor.reference().equals(binding.coordinate().source())
                || !descriptor.scope().equals(binding.coordinate().scope())
                || !visible(context)) {
            throw new SecurityException("frozen Skill is not visible to the current caller");
        }
    }

    private static SkillContent exactContent(FrozenSkillBinding binding, ParsedSkillPackage parsed) {
        if (!binding.coordinate().contentDigest().equals(parsed.packageIndex().digest())
                || !binding.resourceIndexDigest().equals(parsed.packageIndex().digest())) {
            throw new IllegalStateException("frozen Skill content has drifted");
        }
        return new SkillContent(
                binding,
                parsed.instructions(),
                parsed.readableResources(),
                Math.max(1, (parsed.instructions().length() + 3) / 4));
    }

    private SkillDiagnostic diagnostic(String code, String message) {
        return new SkillDiagnostic(
                code,
                SkillDiagnosticSeverity.ERROR,
                descriptor.reference(),
                Optional.empty(),
                Optional.empty(),
                message);
    }

    private record DiscoveryDirectory(Path path, int depth) {}
}
