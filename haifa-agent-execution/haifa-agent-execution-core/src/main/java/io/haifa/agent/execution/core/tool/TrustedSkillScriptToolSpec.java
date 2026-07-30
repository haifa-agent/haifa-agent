package io.haifa.agent.execution.core.tool;

import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillResourceKind;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Immutable product descriptor for one fixed business Tool backed by one reviewed script. */
public record TrustedSkillScriptToolSpec(
        ToolDefinition definition,
        FrozenSkillBinding skillBinding,
        String scriptRelativePath,
        SkillContentDigest scriptDigest,
        String scriptRuntimeRef,
        String executionConfigurationDigest,
        String sandboxDigest,
        Set<String> requiredCapabilities,
        Duration timeout,
        String purpose,
        TrustedScriptArgumentMapper argumentMapper) {
    private static final Set<String> FORBIDDEN_ARGUMENT_NAMES = Set.of(
            "executable",
            "content",
            "env",
            "args",
            "argv",
            "command",
            "script",
            "scriptpath",
            "language",
            "endpoint",
            "proxy");

    public TrustedSkillScriptToolSpec {
        definition = Objects.requireNonNull(definition, "definition must not be null");
        if (!definition.providerId().equals(TrustedSkillScriptToolProvider.PROVIDER_ID)) {
            throw new IllegalArgumentException("trusted script Tool uses an unexpected provider");
        }
        if (definition.approvalRequirement() != ToolApprovalRequirement.ALWAYS) {
            throw new IllegalArgumentException("trusted script Tool must retain ALWAYS approval semantics");
        }
        Object properties = definition.inputSchema().document().get("properties");
        if (!(properties instanceof java.util.Map<?, ?> map)
                || map.keySet().stream()
                        .map(String::valueOf)
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .anyMatch(FORBIDDEN_ARGUMENT_NAMES::contains)) {
            throw new IllegalArgumentException("trusted script Tool exposes unsafe model arguments");
        }
        skillBinding = Objects.requireNonNull(skillBinding, "skillBinding must not be null");
        String normalizedScriptPath = Objects.requireNonNull(scriptRelativePath, "scriptRelativePath must not be null")
                .trim()
                .replace('\\', '/');
        if (normalizedScriptPath.startsWith("/")
                || normalizedScriptPath.contains("../")
                || normalizedScriptPath.contains(":")
                || normalizedScriptPath.equals("..")) {
            throw new IllegalArgumentException("scriptRelativePath must be package-relative");
        }
        SkillContentDigest expectedScriptDigest = Objects.requireNonNull(scriptDigest, "scriptDigest must not be null");
        boolean exactScript = skillBinding.packageIndex().resources().stream()
                .anyMatch(resource -> resource.kind() == SkillResourceKind.SCRIPT
                        && resource.relativePath().equals(normalizedScriptPath)
                        && resource.digest().equals(expectedScriptDigest));
        if (!exactScript) throw new IllegalArgumentException("script is absent from the frozen Skill binding");
        scriptRelativePath = normalizedScriptPath;
        scriptDigest = expectedScriptDigest;
        scriptRuntimeRef = requireText(scriptRuntimeRef, "scriptRuntimeRef", 128);
        executionConfigurationDigest = requireHexDigest(executionConfigurationDigest, "executionConfigurationDigest");
        sandboxDigest = requireSha256(sandboxDigest, "sandboxDigest");
        requiredCapabilities =
                Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities must not be null"));
        if (requiredCapabilities.isEmpty() || requiredCapabilities.size() > 32) {
            throw new IllegalArgumentException("requiredCapabilities must be bounded and non-empty");
        }
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(definition.timeout()) > 0) {
            throw new IllegalArgumentException("timeout exceeds the Tool definition");
        }
        purpose = requireText(purpose, "purpose", 256);
        argumentMapper = Objects.requireNonNull(argumentMapper, "argumentMapper must not be null");
        Set<String> expectedProfileReferences = Set.of(executionConfigurationDigest, "sandbox@" + sandboxDigest);
        if (!definition.resources().executionProfiles().equals(expectedProfileReferences)) {
            throw new IllegalArgumentException(
                    "Tool execution profile must bind the exact execution and sandbox digests");
        }
    }

    private static String requireText(String value, String field, int maximum) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String requireHexDigest(String value, String field) {
        String digest = requireText(value, field, 64).toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " is invalid");
        return digest;
    }

    private static String requireSha256(String value, String field) {
        String digest = requireText(value, field, 71).toLowerCase(Locale.ROOT);
        if (!digest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException(field + " is invalid");
        return digest;
    }
}
