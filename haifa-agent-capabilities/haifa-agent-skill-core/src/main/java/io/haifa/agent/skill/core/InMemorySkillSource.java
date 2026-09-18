package io.haifa.agent.skill.core;

import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillDiscoveryResult;
import io.haifa.agent.skill.api.SkillRegistration;
import io.haifa.agent.skill.api.SkillSource;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySkillSource implements SkillSource {
    private final SkillSourceDescriptor descriptor;
    private final SkillPackageParser parser;
    private final SkillAvailability configuredAvailability;
    private final Map<String, Map<String, byte[]>> packages;
    private final Map<String, String> locations = new ConcurrentHashMap<>();

    public InMemorySkillSource(
            SkillSourceDescriptor descriptor,
            SkillPackageParser parser,
            SkillAvailability configuredAvailability,
            Map<String, Map<String, byte[]>> packages) {
        this.descriptor = java.util.Objects.requireNonNull(descriptor);
        this.parser = java.util.Objects.requireNonNull(parser);
        this.configuredAvailability = java.util.Objects.requireNonNull(configuredAvailability);
        Map<String, Map<String, byte[]>> copy = new LinkedHashMap<>();
        packages.forEach((name, files) -> {
            Map<String, byte[]> fileCopy = new LinkedHashMap<>();
            files.forEach((path, bytes) -> fileCopy.put(path, bytes.clone()));
            copy.put(name, Map.copyOf(fileCopy));
        });
        this.packages = Map.copyOf(copy);
    }

    @Override
    public SkillSourceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public SkillDiscoveryResult discover(SkillDiscoveryContext context) {
        if (!visible(context.visibility())) return SkillDiscoveryResult.empty();
        List<SkillRegistration> registrations = new ArrayList<>();
        List<io.haifa.agent.skill.api.SkillDiagnostic> diagnostics = new ArrayList<>();
        locations.clear();
        packages.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            SkillPackageParseResult result = parser.parseFiles(entry.getKey(), entry.getValue(), descriptor);
            diagnostics.addAll(result.diagnostics());
            result.parsed().ifPresent(parsed -> {
                SkillRegistration registration =
                        SkillRegistrations.create(parsed, descriptor, entry.getKey(), configuredAvailability);
                registrations.add(registration);
                locations.put(registration.coordinate().externalForm(), entry.getKey());
            });
        });
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
        ParsedSkillPackage parsed = parser.parseFiles(packageName, packages.get(packageName), descriptor)
                .parsed()
                .orElseThrow(() -> new IllegalStateException("frozen Skill package is no longer valid"));
        if (!binding.coordinate().contentDigest().equals(parsed.packageIndex().digest())) {
            throw new IllegalStateException("frozen Skill content has drifted");
        }
        return new SkillContent(
                binding,
                parsed.instructions(),
                parsed.readableResources(),
                Math.max(1, (parsed.instructions().length() + 3) / 4));
    }

    private boolean visible(SkillVisibilityContext context) {
        return descriptor.scope().visibleTo(context)
                && (!descriptor.projectTrustRequired() || context.projectTrusted());
    }
}
