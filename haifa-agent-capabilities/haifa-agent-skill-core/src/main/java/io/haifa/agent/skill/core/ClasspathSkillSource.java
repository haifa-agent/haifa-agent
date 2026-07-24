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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit package names make discovery deterministic for both exploded classes and JARs. */
public final class ClasspathSkillSource implements SkillSource {
    private final ClassLoader classLoader;
    private final String root;
    private final List<String> packageNames;
    private final SkillSourceDescriptor descriptor;
    private final SkillPackageParser parser;
    private final SkillAvailability configuredAvailability;
    private final Map<String, String> locations = new ConcurrentHashMap<>();

    public ClasspathSkillSource(
            ClassLoader classLoader,
            String root,
            List<String> packageNames,
            SkillSourceDescriptor descriptor,
            SkillPackageParser parser,
            SkillAvailability configuredAvailability) {
        this.classLoader = java.util.Objects.requireNonNull(classLoader);
        this.root = normalizeRoot(root);
        this.packageNames = packageNames.stream().sorted().toList();
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
        for (String packageName : packageNames) {
            try {
                Map<String, byte[]> files = readPackage(packageName);
                SkillPackageParseResult result = parser.parseFiles(packageName, files, descriptor);
                diagnostics.addAll(result.diagnostics());
                result.parsed().ifPresent(parsed -> {
                    SkillRegistration registration =
                            SkillRegistrations.create(parsed, descriptor, packageName, configuredAvailability);
                    registrations.add(registration);
                    locations.put(registration.coordinate().externalForm(), packageName);
                });
            } catch (IOException exception) {
                diagnostics.add(new SkillDiagnostic(
                        "SKILL_CLASSPATH_RESOURCE_MISSING",
                        SkillDiagnosticSeverity.ERROR,
                        descriptor.reference(),
                        Optional.empty(),
                        Optional.of(packageName),
                        "declared Classpath Skill resource is unavailable"));
            }
        }
        registrations.sort(Comparator.comparing(SkillRegistration::alias));
        return new SkillDiscoveryResult(registrations, diagnostics);
    }

    @Override
    public SkillContent load(FrozenSkillBinding binding, SkillVisibilityContext context) {
        if (!visible(context)
                || !descriptor.reference().equals(binding.coordinate().source())
                || !descriptor.scope().equals(binding.coordinate().scope())) {
            throw new SecurityException("frozen Skill is not visible to the current caller");
        }
        String packageName = locations.get(binding.coordinate().externalForm());
        if (packageName == null) throw new IllegalStateException("frozen Skill was not discovered from this source");
        try {
            ParsedSkillPackage parsed = parser.parseFiles(packageName, readPackage(packageName), descriptor)
                    .parsed()
                    .orElseThrow(() -> new IllegalStateException("frozen Skill package is no longer valid"));
            if (!binding.coordinate()
                            .contentDigest()
                            .equals(parsed.packageIndex().digest())
                    || !binding.resourceIndexDigest()
                            .equals(parsed.packageIndex().digest())) {
                throw new IllegalStateException("frozen Skill content has drifted");
            }
            return new SkillContent(
                    binding,
                    parsed.instructions(),
                    parsed.readableResources(),
                    Math.max(1, (parsed.instructions().length() + 3) / 4));
        } catch (IOException exception) {
            throw new IllegalStateException("frozen Skill package is unavailable", exception);
        }
    }

    private boolean visible(SkillVisibilityContext context) {
        return descriptor.scope().visibleTo(context)
                && (!descriptor.projectTrustRequired() || context.projectTrusted());
    }

    private Map<String, byte[]> readPackage(String packageName) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String skillPath = root + "/" + packageName + "/SKILL.md";
        try (var input = classLoader.getResourceAsStream(skillPath)) {
            if (input == null) throw new IOException("missing Skill resource");
            files.put("SKILL.md", input.readAllBytes());
        }
        return files;
    }

    private static String normalizeRoot(String root) {
        String value = java.util.Objects.requireNonNull(root, "root must not be null")
                .replace('\\', '/')
                .replaceAll("^/+", "")
                .replaceAll("/+$", "");
        if (value.isBlank() || value.contains("..") || value.contains(":")) {
            throw new IllegalArgumentException("invalid Classpath Skill root");
        }
        return value;
    }
}
