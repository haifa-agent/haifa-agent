package io.haifa.agent.sandbox.localnative;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxCapabilities;
import io.haifa.agent.sandbox.api.SandboxFilesystemPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxWorkspaceAccess;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class LocalNativeWindowsIT {
    @Test
    void rejectsLocalNativeBeforeWorkspaceOrProcessActivity() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        LocalNativeSandboxConfiguration configuration = LocalNativeSandboxConfiguration.defaults();
        LocalNativeSandboxProvider provider = new LocalNativeSandboxProvider(
                new InMemoryWorkspaceStore(),
                new InMemoryWorkspaceBindingStore(),
                new LocalWorkspaceLocationStore(),
                () -> "must-not-be-used",
                () -> Instant.parse("2026-07-26T00:00:00Z"),
                configuration);
        SandboxProfile profile = new SandboxProfile(
                new SandboxProfileRef("windows-unsupported", "1"),
                LocalNativeSandboxProvider.PROVIDER_ID,
                configuration.digest(),
                Set.of("tool"),
                Set.of(),
                false,
                NetworkPolicy.DENY,
                new SandboxFilesystemPolicy(SandboxWorkspaceAccess.READ_WRITE, true, Set.of()),
                new SandboxCapabilities(true, true, true, false, false));

        assertThatThrownBy(() -> provider.preflight(profile))
                .isInstanceOf(LocalNativeSandboxException.class)
                .extracting(exception -> ((LocalNativeSandboxException) exception).code())
                .isEqualTo("SANDBOX_ADAPTER_UNAVAILABLE");
    }
}
