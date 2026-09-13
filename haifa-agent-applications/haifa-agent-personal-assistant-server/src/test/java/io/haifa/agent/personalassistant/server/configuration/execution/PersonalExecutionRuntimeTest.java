package io.haifa.agent.personalassistant.server.configuration.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.personalassistant.application.policy.PersonalAssistantPolicyRules;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalExecutionRuntimeTest {
    @TempDir
    Path root;

    @Test
    void defaultPersonalExecutionAssemblyPropagatesNoneScratchAndUnboundedProcesses() throws Exception {
        Path productData = Files.createDirectory(root.resolve("personal-assembly-data"));
        var executionProps = new PersonalAssistantProperties.Execution(15000, 30000, 65536, 1000, true, "", "");
        var policy = new PolicyPlatformContribution(
                new SdkContributionMetadata(
                        new ProductContributionCoordinate("pa-policy", "1.0.0"),
                        ProductCapabilities.POLICY,
                        "sha256:" + "0".repeat(64),
                        ProductProviderSuitability.PRODUCTION,
                        "Personal Assistant policy"),
                PersonalAssistantPolicyRules.conservative(),
                new DefaultPolicyDecisionService());
        var platform = PersonalExecutionRuntime.create(
                productData,
                new TenantRef("tenant-1"),
                new PrincipalRef("user-1", "user"),
                executionProps,
                Clock.systemUTC(),
                RuntimePersistencePorts.inMemory(),
                policy);

        var toolDef = platform.definition();
        assertThat(toolDef.inputSchema().document())
                .containsEntry(
                        "x-haifa-scratch-spec-digest",
                        ExecutionScratchSpaceSpec.none().canonicalDigest());

        var config = platform.provider().configuration();
        assertThat(config.scratchSpace().isEmpty()).isTrue();
        assertThat(config.maximumProcesses()).isEmpty();
    }

    @Test
    void windowsHostUserEnvironmentKeepsPythonUserSiteOutsideExecutionWorkspace() throws Exception {
        Path userProfile = Files.createDirectory(root.resolve("user-profile"));
        Path appData = Files.createDirectories(userProfile.resolve("AppData/Roaming"));
        Path localAppData = Files.createDirectories(userProfile.resolve("AppData/Local"));
        Path productData = Files.createDirectory(root.resolve("personal-data"));
        Path workspace = Files.createDirectory(productData.resolve("execution-workspace"));
        Path scratch = Files.createDirectory(root.resolve("scratch"));

        var resolved = PersonalExecutionRuntime.resolveHostEnvironment(
                Map.of(
                        "USERPROFILE", userProfile.toString(),
                        "APPDATA", appData.toString(),
                        "LOCALAPPDATA", localAppData.toString(),
                        "PATH", "tools",
                        "SystemRoot", root.resolve("Windows").toString(),
                        "PATHEXT", ".EXE;.CMD"),
                "Windows 11",
                userProfile,
                productData,
                workspace,
                scratch);

        Path pythonUserSite = Path.of(resolved.environment().get("APPDATA"))
                .resolve("Python/Python311/site-packages")
                .normalize();
        assertThat(Path.of(resolved.environment().get("HOME"))).isEqualTo(userProfile.toRealPath());
        assertThat(resolved.environment())
                .containsEntry("USERPROFILE", userProfile.toRealPath().toString())
                .containsEntry("APPDATA", appData.toRealPath().toString())
                .containsEntry("LOCALAPPDATA", localAppData.toRealPath().toString());
        assertThat(pythonUserSite.startsWith(workspace)).isFalse();
        assertThat(workspace.resolve("~")).doesNotExist();
    }
}
