package io.haifa.agent.cli;

import io.haifa.agent.application.project.tool.ProjectExecutionToolOperations;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.core.DefaultExecutionBroker;
import io.haifa.agent.execution.core.manifest.ManifestBudget;
import io.haifa.agent.execution.core.manifest.ManifestDiffService;
import io.haifa.agent.execution.core.manifest.WorkspaceManifestService;
import io.haifa.agent.execution.core.store.InMemoryExecutionOutputStore;
import io.haifa.agent.execution.core.store.InMemoryExecutionStore;
import io.haifa.agent.project.changeset.FileChangeSetService;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
import io.haifa.agent.project.changeset.ObservedFileChangeService;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.host.HostGuardedSandboxProvider;
import io.haifa.agent.sandbox.host.HostShell;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Owns the CLI's trusted local execution assembly without exposing Host details to the Tool adapter. */
final class CliExecutionPlatform {
    private static final SandboxProfileRef PROFILE_REF = new SandboxProfileRef("cli-host-shell", "1");
    private static final ExecutionEnvironmentRef ENVIRONMENT_REF =
            new ExecutionEnvironmentRef(java.util.List.of("cli-host-environment"));

    private final ProjectExecutionToolOperations operations;
    private final String shellDisplayName;

    private CliExecutionPlatform(ProjectExecutionToolOperations operations, String shellDisplayName) {
        this.operations = operations;
        this.shellDisplayName = shellDisplayName;
    }

    static CliExecutionPlatform create(
            CliConfiguration.Execution configuration,
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations,
            LocalWorkspaceFileService files,
            InMemoryFileChangeSetStore changeSets,
            FileChangeSetService changeSetService,
            IdentifierGenerator identifiers,
            TimeProvider time,
            PrintStream output) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        HostShell shell = shell(configuration);
        var host = new HostGuardedSandboxProvider(workspaces, bindings, locations, identifiers, time, shell);
        SandboxProfile profile = new SandboxProfile(
                PROFILE_REF, java.util.Set.of(), configuration.inheritEnvironment(), true, NetworkPolicy.ALLOW);
        Map<String, String> environment = environment(configuration);
        var manifests = new WorkspaceManifestService(
                workspaces,
                files,
                new ManifestBudget(100_000, 1024L * 1024 * 1024, 256L * 1024 * 1024),
                "cli-shell-v1");
        var observedChanges = new ObservedFileChangeService(workspaces, changeSets, changeSetService, time);
        var broker = new DefaultExecutionBroker(
                new InMemoryExecutionStore(),
                new InMemoryExecutionOutputStore(),
                ignored -> environment,
                ignored -> {},
                reference -> {
                    if (!PROFILE_REF.equals(reference)) throw new IllegalArgumentException("unknown sandbox profile");
                    return profile;
                },
                ignored -> host,
                workspaces,
                bindings,
                manifests,
                new ManifestDiffService(),
                observedChanges);
        ExecutionOutputObserver observer = new CliOutputObserver(output);
        var operations = new ProjectExecutionToolOperations(
                broker,
                identifiers,
                time,
                ENVIRONMENT_REF,
                PROFILE_REF,
                configuration.defaultTimeout(),
                configuration.maximumTimeout(),
                configuration.maxOutputBytes(),
                configuration.maxOutputLines(),
                configuration.maxProcesses(),
                observer);
        return new CliExecutionPlatform(operations, host.shellDisplayName());
    }

    ProjectExecutionToolOperations operations() {
        return operations;
    }

    String shellDisplayName() {
        return shellDisplayName;
    }

    private static HostShell shell(CliConfiguration.Execution configuration) {
        Path configured = configuration.shellPath();
        if (configured != null) {
            return configuration.shell().equals("powershell")
                    ? HostShell.powerShell(configured)
                    : HostShell.bash(configured);
        }
        if (configuration.shell().equals("auto")) {
            try {
                return HostShell.auto();
            } catch (IllegalStateException exception) {
                throw new IllegalArgumentException("execution.shell auto found no supported host shell");
            }
        }
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        if (configuration.shell().equals("bash")) {
            if (windows) throw new IllegalArgumentException("execution.shell bash requires an absolute shellPath");
            return HostShell.bash(Path.of("/bin/bash"));
        }
        if (!windows) throw new IllegalArgumentException("execution.shell powershell requires an absolute shellPath");
        return new HostShell(
                "PowerShell",
                java.util.List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command"));
    }

    private static Map<String, String> environment(CliConfiguration.Execution configuration) {
        var values = new LinkedHashMap<String, String>();
        configuration.inheritEnvironment().stream().sorted().forEach(name -> {
            String value = System.getenv(name);
            if (value != null) values.put(name, value);
        });
        return Map.copyOf(values);
    }

    private static final class CliOutputObserver implements ExecutionOutputObserver {
        private final PrintStream output;
        private final StringBuilder pending = new StringBuilder();
        private long lastFlushNanos = System.nanoTime();

        private CliOutputObserver(PrintStream output) {
            this.output = Objects.requireNonNull(output, "output must not be null");
        }

        @Override
        public synchronized void onOutput(io.haifa.agent.execution.api.ProcessOutputChunk chunk) {
            String text =
                    new String(chunk.bytes(), StandardCharsets.UTF_8).replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
            StringBuilder safe = new StringBuilder(text.length());
            text.codePoints().forEach(codePoint -> {
                if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t' || !Character.isISOControl(codePoint)) {
                    safe.appendCodePoint(codePoint);
                }
            });
            pending.append(safe);
            long now = System.nanoTime();
            if (chunk.endOfStream() || now - lastFlushNanos >= 100_000_000L) flush(now);
        }

        private void flush(long now) {
            if (!pending.isEmpty()) output.print(pending);
            output.flush();
            pending.setLength(0);
            lastFlushNanos = now;
        }
    }
}
