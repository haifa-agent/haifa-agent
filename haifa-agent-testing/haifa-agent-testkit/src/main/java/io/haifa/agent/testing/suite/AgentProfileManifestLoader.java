package io.haifa.agent.testing.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.haifa.agent.testing.evidence.Sha256Digests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Strict loader that derives credential requirements from the referenced standard YAML. */
public final class AgentProfileManifestLoader {
    private static final String DOMAIN = "haifa-agent-profile-assembly-v2";
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public ResolvedAgentProfile load(Path configRoot, String profileId) {
        Path root = Objects.requireNonNull(configRoot, "configRoot must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("configRoot must be an existing directory");
        if (profileId == null || !profileId.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("agent profile id must be lowercase kebab-case");
        }
        Path manifestPath =
                root.resolve("agent-profiles").resolve(profileId + ".yaml").normalize();
        if (!manifestPath.startsWith(root) || !Files.isRegularFile(manifestPath)) {
            throw new IllegalArgumentException("agent profile is unavailable: " + profileId);
        }
        try {
            AgentProfileManifest manifest = yaml.readValue(manifestPath.toFile(), AgentProfileManifest.class);
            if (!manifest.profileId().equals(profileId)) {
                throw new IllegalArgumentException("agent profile file id does not match requested profile");
            }
            Path configuration = root.resolve(manifest.configurationRef()).normalize();
            if (!configuration.startsWith(root) || !Files.isRegularFile(configuration)) {
                throw new IllegalArgumentException("agent profile configuration is unavailable: " + profileId);
            }
            String configurationSha256 = Sha256Digests.file(configuration);
            if (!configurationSha256.equals(manifest.configurationSha256())) {
                throw new IllegalArgumentException("agent profile configuration digest does not match: " + profileId);
            }
            JsonNode configurationTree = yaml.readTree(configuration.toFile());
            List<String> requiredEnvironmentNames = new ArrayList<>();
            List<String> credentialNames = new ArrayList<>();
            collectEnvironmentNames(configurationTree, requiredEnvironmentNames, credentialNames);
            List<String> uniqueRequiredEnvironmentNames =
                    requiredEnvironmentNames.stream().distinct().sorted().toList();
            List<String> uniqueCredentialNames =
                    credentialNames.stream().distinct().sorted().toList();
            String assemblyDigest = sha256(String.join(
                    "\n",
                    DOMAIN,
                    manifest.profileId(),
                    manifest.compatibleAgentBaselineCommit(),
                    manifest.configurationRef(),
                    configurationSha256,
                    String.join(",", uniqueRequiredEnvironmentNames),
                    String.join(",", uniqueCredentialNames)));
            return new ResolvedAgentProfile(
                    manifest, configuration, assemblyDigest, uniqueRequiredEnvironmentNames, uniqueCredentialNames);
        } catch (IOException exception) {
            throw new IllegalArgumentException("agent profile cannot be parsed: " + profileId, exception);
        }
    }

    private static void collectEnvironmentNames(
            JsonNode node, List<String> requiredNames, List<String> credentialNames) {
        if (node == null) return;
        if (node.isTextual()) {
            String value = node.asText();
            if (value.startsWith("env://")) {
                String name = value.substring("env://".length());
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("standard configuration contains an invalid env reference");
                }
                requiredNames.add(name);
                credentialNames.add(name);
            } else if (value.startsWith("${") && value.endsWith("}")) {
                String expression = value.substring(2, value.length() - 1);
                int separator = expression.indexOf(':');
                String name = separator < 0 ? expression : expression.substring(0, separator);
                if (!name.matches("[A-Z][A-Z0-9_]*")) {
                    throw new IllegalArgumentException(
                            "standard configuration contains an invalid environment placeholder");
                }
                if (separator < 0) requiredNames.add(name);
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectEnvironmentNames(child, requiredNames, credentialNames));
        } else if (node.isObject()) {
            node.elements().forEachRemaining(child -> collectEnvironmentNames(child, requiredNames, credentialNames));
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
