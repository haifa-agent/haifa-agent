package io.haifa.agent.personalassistant.application.execution;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.execution.core.tool.ScriptRuntimeResolver;
import io.haifa.agent.policy.api.ApprovalVerificationService;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.ApprovalPlatformContribution;
import io.haifa.agent.sdk.contribution.ExecutionPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.contribution.ShellPlatformContribution;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolDefinition;
import java.util.Objects;

/** Product assembly contribution for the shared execution Tool and its logical SDK capabilities. */
public record PersonalExecutionPlatform(
        ToolDefinition definition,
        ExecutionToolProvider provider,
        ExecutionPlatformContribution execution,
        ShellPlatformContribution shell,
        ApprovalPlatformContribution approval) {
    public static final ProductContributionCoordinate EXECUTION_COORDINATE =
            new ProductContributionCoordinate("haifa-personal-execution", "2.0.0");
    public static final ProductContributionCoordinate SHELL_COORDINATE =
            new ProductContributionCoordinate("haifa-personal-shell", "2.0.0");
    public static final ProductContributionCoordinate APPROVAL_COORDINATE =
            new ProductContributionCoordinate("haifa-personal-approval", "1.0.0");

    public static PersonalExecutionPlatform create(
            ExecutionToolProvider provider,
            SandboxProfile profile,
            ScriptRuntimeResolver runtimes,
            ApprovalVerificationService approvalVerification) {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(profile);
        Objects.requireNonNull(runtimes);
        String profileIdentity = profile.ref().value() + "@" + profile.ref().version();
        ToolDefinition definition = ExecutionToolDefinitionFactory.create(
                profileIdentity,
                provider.configurationIdentity(),
                provider.scratchSpecDigest(),
                profile.networkPolicy() == NetworkPolicy.ALLOW,
                false,
                runtimes.languages());
        return new PersonalExecutionPlatform(
                definition,
                provider,
                new ExecutionPlatformContribution(
                        metadata(
                                EXECUTION_COORDINATE,
                                ProductCapabilities.EXECUTION,
                                SdkConfigurationDigest.sha256(
                                        profileIdentity,
                                        profile.contentDigest().value(),
                                        runtimes.languages().toString()),
                                "Personal governed Execution Broker"),
                        profile.providerId()),
                new ShellPlatformContribution(
                        metadata(
                                SHELL_COORDINATE,
                                ProductCapabilities.SHELL,
                                SdkConfigurationDigest.sha256(
                                        runtimes.operatingSystem().name(),
                                        runtimes.languages().toString()),
                                "Personal host command and script runtime allowlist"),
                        runtimes.operatingSystem().name(),
                        runtimes.languages()),
                new ApprovalPlatformContribution(
                        metadata(
                                APPROVAL_COORDINATE,
                                ProductCapabilities.APPROVAL,
                                SdkConfigurationDigest.sha256("personal-local-principal-approval-v1"),
                                "Personal exact invocation approval verification"),
                        approvalVerification));
    }

    public String approvalPrompt(FrozenToolBinding binding, ToolCall call, boolean reauthentication) {
        if (!"execution.run".equals(binding.definition().name().value())) {
            return io.haifa.agent.sdk.contribution.ProductApprovalPromptFormatter.defaultFormatter()
                    .format(binding, call, reauthentication);
        }
        var arguments = call.arguments().values();
        String mode = safe(arguments.get("mode"));
        String language = safe(arguments.getOrDefault("language", "default-shell"));
        String purpose = safe(arguments.get("purpose"));
        String content = safe(arguments.get("content"));
        String args = safe(arguments.getOrDefault("args", java.util.List.of()));
        String timeout = safe(arguments.getOrDefault("timeoutMillis", 15_000));
        String digest = PolicyDigest.sha256Fields(java.util.List.of(mode, language, content, args, purpose, timeout));
        return (reauthentication ? "Reauthenticate and approve execution" : "Approve execution")
                + "\nMode: " + mode
                + "\nLanguage: " + language
                + "\nPurpose: " + purpose
                + "\nArgs: " + args
                + "\nTimeout: " + timeout + " ms"
                + "\nOutput: bounded and redacted"
                + "\nWorkspace: application-owned; the model cannot select cwd"
                + "\nProvider: trusted host process; no strong isolation; network disconnection is not guaranteed"
                + "\nInvocation digest: " + digest
                + "\nRisks: HIGH, PROCESS_EXECUTION, NON_IDEMPOTENT, host access; approve once or reject"
                + "\nFull content:\n" + content;
    }

    private static String safe(Object value) {
        String text = String.valueOf(value);
        String withoutAnsi = text.replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
        StringBuilder safe = new StringBuilder(withoutAnsi.length());
        withoutAnsi.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t' || !Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }

    private static SdkContributionMetadata metadata(
            ProductContributionCoordinate coordinate,
            io.haifa.agent.sdk.product.ProductCapabilityId capability,
            String digest,
            String description) {
        return new SdkContributionMetadata(
                coordinate, capability, digest, ProductProviderSuitability.PRODUCTION, description);
    }
}
