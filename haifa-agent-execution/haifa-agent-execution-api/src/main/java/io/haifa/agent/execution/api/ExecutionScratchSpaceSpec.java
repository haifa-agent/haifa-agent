package io.haifa.agent.execution.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Product-neutral logical scratch request. Physical paths remain exclusively owned by the selected
 * Sandbox Provider.
 */
public record ExecutionScratchSpaceSpec(
        boolean required, Set<String> rootEnvironmentNames, List<ExecutionScratchBinding> childBindings) {
    private static final Set<String> FORBIDDEN_NAMES =
            Set.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "SSH_AUTH_SOCK", "DOCKER_HOST", "KUBECONFIG");

    public ExecutionScratchSpaceSpec {
        Objects.requireNonNull(rootEnvironmentNames, "rootEnvironmentNames must not be null");
        Objects.requireNonNull(childBindings, "childBindings must not be null");
        if (rootEnvironmentNames.isEmpty() || rootEnvironmentNames.size() > 16 || childBindings.size() > 16) {
            throw new IllegalArgumentException("scratch environment binding count is out of range");
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        rootEnvironmentNames.stream()
                .sorted()
                .map(ExecutionScratchSpaceSpec::requireEnvironmentName)
                .forEach(roots::add);
        LinkedHashSet<String> childNames = new LinkedHashSet<>();
        for (ExecutionScratchBinding binding : childBindings) {
            Objects.requireNonNull(binding, "child binding must not be null");
            if (roots.contains(binding.environmentName()) || !childNames.add(binding.environmentName())) {
                throw new IllegalArgumentException("scratch environment names must be unique");
            }
        }
        rootEnvironmentNames = Set.copyOf(roots);
        childBindings = childBindings.stream()
                .sorted(java.util.Comparator.comparing(ExecutionScratchBinding::environmentName))
                .toList();
    }

    public static ExecutionScratchSpaceSpec genericRequired() {
        return new ExecutionScratchSpaceSpec(true, Set.of("TMPDIR", "TMP", "TEMP"), List.of());
    }

    public Set<String> environmentNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>(rootEnvironmentNames);
        childBindings.stream().map(ExecutionScratchBinding::environmentName).forEach(names::add);
        return Set.copyOf(names);
    }

    public String canonicalDigest() {
        List<String> fields = new ArrayList<>();
        fields.add("execution-scratch-space-v1");
        fields.add(Boolean.toString(required));
        rootEnvironmentNames.stream().sorted().forEach(name -> fields.add("root:" + name));
        childBindings.forEach(
                binding -> fields.add("child:" + binding.environmentName() + ":" + binding.relativeDirectory()));
        StringBuilder canonical = new StringBuilder();
        fields.forEach(value ->
                canonical.append(value.length()).append(':').append(value).append(';'));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String requireEnvironmentName(String value) {
        String normalized = Objects.requireNonNull(value, "environmentName must not be null")
                .trim();
        String upper = normalized.toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")
                || FORBIDDEN_NAMES.contains(upper)
                || looksLikeSecretName(upper)) {
            throw new IllegalArgumentException("scratch environment name is denied");
        }
        return normalized;
    }

    private static boolean looksLikeSecretName(String name) {
        return name.contains("API_KEY")
                || name.contains("ACCESS_KEY")
                || name.contains("PRIVATE_KEY")
                || name.contains("PASSWORD")
                || name.contains("SECRET")
                || name.contains("TOKEN")
                || name.contains("CREDENTIAL");
    }
}
