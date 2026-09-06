package io.haifa.agent.project.configuration;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

public record ProjectConfiguration(
        ProjectConfigurationId id,
        ProjectConfigurationVersion version,
        WorkspaceId defaultWorkspaceId,
        String productProfileId,
        String productProfileVersion,
        Set<String> capabilities,
        Set<String> contextSources,
        Set<String> tools,
        String securityPolicyRef,
        String digest) {
    public ProjectConfiguration {
        id = Objects.requireNonNull(id, "id must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        defaultWorkspaceId = Objects.requireNonNull(defaultWorkspaceId, "defaultWorkspaceId must not be null");
        productProfileId = requireText(productProfileId, "productProfileId");
        productProfileVersion = requireText(productProfileVersion, "productProfileVersion");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        contextSources = Set.copyOf(Objects.requireNonNull(contextSources, "contextSources must not be null"));
        tools = Set.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
        securityPolicyRef = requireText(securityPolicyRef, "securityPolicyRef");
        String expected = calculateDigest(
                id,
                version,
                defaultWorkspaceId,
                productProfileId,
                productProfileVersion,
                capabilities,
                contextSources,
                tools,
                securityPolicyRef);
        digest = digest == null ? expected : requireText(digest, "digest");
        if (!digest.equals(expected)) throw new IllegalArgumentException("configuration digest does not match content");
    }

    public static ProjectConfiguration create(
            ProjectConfigurationId id,
            ProjectConfigurationVersion version,
            WorkspaceId defaultWorkspaceId,
            String productProfileId,
            String productProfileVersion,
            Set<String> capabilities,
            Set<String> contextSources,
            Set<String> tools,
            String securityPolicyRef) {
        return new ProjectConfiguration(
                id,
                version,
                defaultWorkspaceId,
                productProfileId,
                productProfileVersion,
                capabilities,
                contextSources,
                tools,
                securityPolicyRef,
                null);
    }

    /** Stable resolver key used to select exactly the configured Product Profile version. */
    public String runtimeProfileRef() {
        return productProfileId + "@" + productProfileVersion;
    }

    private static String calculateDigest(
            ProjectConfigurationId id,
            ProjectConfigurationVersion version,
            WorkspaceId workspaceId,
            String profileId,
            String profileVersion,
            Set<String> capabilities,
            Set<String> sources,
            Set<String> tools,
            String policy) {
        String canonical = String.join(
                "\n",
                id.value(),
                version.value(),
                workspaceId.value(),
                profileId,
                profileVersion,
                capabilities.stream().sorted().toList().toString(),
                sources.stream().sorted().toList().toString(),
                tools.stream().sorted().toList().toString(),
                policy);
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
