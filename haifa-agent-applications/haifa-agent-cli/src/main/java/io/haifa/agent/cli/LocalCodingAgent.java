package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.tool.ProjectToolCatalog;
import io.haifa.agent.application.project.tool.ProjectToolExecutor;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.common.time.SystemTimeProvider;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.AliyunBailianProviderFactory;
import io.haifa.agent.model.openai.EnvironmentCredentialResolver;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChangeSetService;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.mutation.InMemoryWorkspaceWriteLeaseManager;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.LocalWorkspaceMutationService;
import io.haifa.agent.project.provider.local.SensitivePathPolicy;
import io.haifa.agent.project.quarantine.InMemoryQuarantineStore;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Builds an in-process Coding Agent over one explicitly selected local workspace. */
final class LocalCodingAgent implements AutoCloseable {
    private static final AgentDefinitionId DEFINITION_ID = new AgentDefinitionId("haifa-cli-coding-agent");
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final AgentRuntime runtime;
    private final InMemoryInteractionPort interactions;
    private final List<RuntimeTraceEvent> traces;
    private final CliMcpPlatform mcpPlatform;

    private LocalCodingAgent(
            IdentifierGenerator identifiers,
            TimeProvider time,
            AgentRuntime runtime,
            InMemoryInteractionPort interactions,
            List<RuntimeTraceEvent> traces,
            CliMcpPlatform mcpPlatform) {
        this.identifiers = identifiers;
        this.time = time;
        this.runtime = runtime;
        this.interactions = interactions;
        this.traces = traces;
        this.mcpPlatform = mcpPlatform;
    }

    static LocalCodingAgent create(Path workspaceRoot, CliConfiguration configuration, PrintStream output) {
        var model = new OpenAiCompatibleChatModel(
                "openai-compatible",
                "1.0.0",
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                new EnvironmentCredentialResolver(),
                false,
                4 * 1024 * 1024);
        return create(workspaceRoot, configuration, output, model);
    }

    static LocalCodingAgent create(
            Path workspaceRoot, CliConfiguration configuration, PrintStream output, AgentChatModel model) {
        try {
            workspaceRoot = workspaceRoot.toRealPath();
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("workspace must exist and be accessible");
        }
        if (!Files.isDirectory(workspaceRoot)) throw new IllegalArgumentException("workspace must be a directory");

        IdentifierGenerator identifiers = new UuidV7IdentifierGenerator();
        TimeProvider time = new SystemTimeProvider();
        PrincipalRef principal = new PrincipalRef("local-user", "user");
        CliMcpPlatform mcpPlatform = CliMcpPlatform.connect(configuration.mcpServers(), principal);
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        var locations = new LocalWorkspaceLocationStore();
        WorkspaceId workspaceId = new WorkspaceId(identifiers.nextValue());
        WorkspaceBindingId bindingId = new WorkspaceBindingId(identifiers.nextValue());
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("local:" + identifiers.nextValue());
        locations.register(locationRef, workspaceRoot);
        Set<String> configuredTools = effectiveBuiltInTools(configuration);
        boolean executionEnabled = configuredTools.contains("execution.run");
        WorkspaceCapabilitySet workspaceCapabilities = executionEnabled
                ? new WorkspaceCapabilitySet(java.util.stream.Stream.concat(
                                WorkspaceCapabilitySet.readWriteFiles().values().stream(),
                                java.util.stream.Stream.of("execution.run"))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                : WorkspaceCapabilitySet.readWriteFiles();
        WorkspacePermissionSet workspacePermissions =
                executionEnabled ? WorkspacePermissionSet.readWriteExecute() : WorkspacePermissionSet.readWrite();
        bindings.create(WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        principal,
                        workspaceCapabilities,
                        workspacePermissions,
                        LocalWorkspaceLocationStore.fingerprintFor(workspaceRoot),
                        time.now())
                .activate(time.now()));
        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId("cli-" + identifiers.nextValue()),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "local-guarded"),
                        WorkspaceRevision.initial("cli-initial"),
                        time.now())
                .activate(time.now()));

        SensitivePathPolicy sensitivePaths = SensitivePathPolicy.defaults();
        var files = new LocalWorkspaceFileService(workspaces, bindings, locations, sensitivePaths);
        var changeSets = new InMemoryFileChangeSetStore();
        var changeSetService = new FileChangeSetService(changeSets, identifiers, time);
        var mutations = new LocalWorkspaceMutationService(
                workspaces,
                bindings,
                locations,
                sensitivePaths,
                new InMemoryWorkspaceWriteLeaseManager(),
                changeSets,
                changeSetService,
                new InMemoryQuarantineStore(),
                identifiers,
                time);
        var operations = new LocalFileToolOperations(workspaces, files, mutations, identifiers);
        CliExecutionPlatform executionPlatform = executionEnabled
                ? CliExecutionPlatform.create(
                        configuration.execution(),
                        workspaces,
                        bindings,
                        locations,
                        files,
                        changeSets,
                        changeSetService,
                        identifiers,
                        time,
                        output)
                : null;
        Set<String> effectiveCapabilities = executionEnabled
                ? Set.of("file.read", "file.write", "execution.run")
                : Set.of("file.read", "file.write");
        var provider = new ProjectToolExecutor(
                (runId, ignoredPrincipal) -> new io.haifa.agent.application.project.tool.RunWorkspaceAccess(
                        workspaceId, effectiveCapabilities, "cli-local-policy"),
                operations,
                executionPlatform == null ? null : executionPlatform.operations());
        var catalog = new ProjectToolCatalog()
                .freeze(
                        Set.copyOf(configuredTools),
                        effectiveCapabilities,
                        true,
                        provider,
                        mcpPlatform.contributions());
        var interactions = new InMemoryInteractionPort();
        ResolvedModelSnapshot modelSnapshot = modelSnapshot(configuration);
        List<RuntimeTraceEvent> traces = new CopyOnWriteArrayList<>();
        var runtime = new RuntimeCoreBuilder()
                .identifierGenerator(identifiers)
                .timeProvider(time)
                .trace(traces::add)
                .interactions(interactions)
                .registerChatModel("openai-compatible", "1.0.0", model)
                .toolPlatform(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator())
                .toolApprovalPrompts((binding, call, reauthentication) -> {
                    if (!binding.definition().name().value().equals("execution.run")) {
                        return io.haifa.agent.runtime.core.interaction.ToolApprovalPromptFormatter.defaultFormatter()
                                .format(binding, call, reauthentication);
                    }
                    Map<String, Object> arguments = call.arguments().values();
                    String command = String.valueOf(arguments.get("command"));
                    String workdir = String.valueOf(arguments.getOrDefault("workdir", "."));
                    Object timeout = arguments.getOrDefault(
                            "timeoutMillis",
                            configuration.execution().defaultTimeout().toMillis());
                    String description = safeApprovalText(
                            String.valueOf(arguments.getOrDefault("description", "Run shell command")));
                    return description + "\nCommand: " + safeApprovalText(command) + "\nWorkdir: "
                            + safeApprovalText(workdir) + "\nTimeout: " + timeout + " ms\nShell: "
                            + (executionPlatform == null ? "unavailable" : executionPlatform.shellDisplayName())
                            + "\nRisk: runs on the host with the current OS user's access; this is not strong isolation.";
                })
                .toolPolicy((run, binding, request) -> switch (configuration.approval()) {
                    case AUTO -> io.haifa.agent.runtime.core.tool.ToolPolicyDecision.ALLOW;
                    case DENY ->
                        binding.definition().approvalRequirement()
                                        == io.haifa.agent.tool.api.ToolApprovalRequirement.NEVER
                                ? io.haifa.agent.runtime.core.tool.ToolPolicyDecision.ALLOW
                                : io.haifa.agent.runtime.core.tool.ToolPolicyDecision.DENY;
                    case ASK ->
                        binding.definition().approvalRequirement()
                                        == io.haifa.agent.tool.api.ToolApprovalRequirement.NEVER
                                ? io.haifa.agent.runtime.core.tool.ToolPolicyDecision.ALLOW
                                : io.haifa.agent.runtime.core.tool.ToolPolicyDecision.REQUIRE_APPROVAL;
                })
                .definitions((id, requested) -> new ResolvedDefinition(
                        id,
                        requested.orElse(new AgentDefinitionVersion(1, 0, 0)),
                        catalog.snapshot().bindings().stream()
                                .map(binding -> binding.alias().value())
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        Set.of(),
                        "You are a careful local coding agent. Inspect relevant files before editing. "
                                + "Use tools for workspace facts, preserve existing changes, and summarize completed work. "
                                + "Pass only workspace-relative paths to file tools; never pass an absolute path."))
                .profiles((profileId, overrides) -> new ResolvedProfile(
                        profileId,
                        "1.0.0",
                        AgentRunType.CHAT,
                        new AgentRunBudget(
                                1_000_000, 1_000_000, 1_000_000, configuration.maxToolCalls(), 64, 8, "USD", 1_000_000),
                        new AgentRunLimits(
                                configuration.maxIterations(),
                                4,
                                1,
                                configuration.timeout().toMillis(),
                                60_000),
                        modelSnapshot))
                .build();
        return new LocalCodingAgent(identifiers, time, runtime, interactions, traces, mcpPlatform);
    }

    AgentRunSnapshot start(String message) {
        return runtime.start(new AgentRunRequest(
                identifiers.nextValue(),
                DEFINITION_ID,
                Optional.empty(),
                "cli-coding",
                new AgentSessionId(identifiers.nextValue()),
                Optional.empty(),
                message,
                List.of(new TextPart(message, "text/plain")),
                RuntimeOverrides.NONE));
    }

    AgentRuntime runtime() {
        return runtime;
    }

    void cancel(io.haifa.agent.core.run.AgentRunId runId) {
        runtime.command(new RuntimeCommand(
                new RuntimeCommandId(identifiers.nextValue()),
                runId,
                RuntimeCommandType.CANCEL,
                RuntimeCommandArguments.NONE,
                "cli-cancel-" + runId.value(),
                time.now()));
    }

    InMemoryInteractionPort interactions() {
        return interactions;
    }

    IdentifierGenerator identifiers() {
        return identifiers;
    }

    TimeProvider time() {
        return time;
    }

    long reasoningTokens(AgentRunId runId) {
        return traces.stream()
                .filter(event ->
                        event.runId().equals(runId) && event.operation().equals("model.invoke"))
                .map(RuntimeTraceEvent::safeAttributes)
                .map(attributes -> attributes.get("reasoningTokens"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToLong(Number::longValue)
                .sum();
    }

    List<RuntimeTraceEvent> traceEvents() {
        return List.copyOf(traces);
    }

    static Set<String> effectiveBuiltInTools(CliConfiguration configuration) {
        java.util.Set<String> configuredTools = new java.util.HashSet<>(configuration.enabledTools());
        if (configuration.approval() == ApprovalMode.DENY) configuredTools.remove("execution.run");
        return Set.copyOf(configuredTools);
    }

    private static String safeApprovalText(String value) {
        String withoutAnsi = value.replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
        StringBuilder safe = new StringBuilder(withoutAnsi.length());
        withoutAnsi.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t' || !Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }

    @Override
    public void close() {
        mcpPlatform.close();
    }

    static ResolvedModelSnapshot modelSnapshot(CliConfiguration configuration) {
        CliConfiguration.Model model = configuration.model();
        if (model.providerId().equals(AliyunBailianProviderFactory.PROVIDER_ID.value())) {
            return bailianModelSnapshot(model);
        }
        return ResolvedModelSnapshot.create(
                new ModelProviderId(model.providerId()),
                "cli-v1",
                new ModelDefinitionId(model.modelId()),
                "cli-v1",
                model.modelId(),
                "openai-compatible",
                "1.0.0",
                model.endpoint(),
                new CredentialRef(model.credentialRef()),
                EnumSet.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.REASONING),
                131_072,
                8_192,
                Map.of(
                        "dialect_id", "deepseek-openai-chat",
                        "dialect_version", "1.0",
                        "thinking", "disabled"),
                Map.of("thinking", "disabled"));
    }

    private static ResolvedModelSnapshot bailianModelSnapshot(CliConfiguration.Model model) {
        var provider = AliyunBailianProviderFactory.provider(
                new AliyunBailianProviderFactory.ProviderConfiguration(
                        "cli-v1", model.workspaceId(), model.region(), new CredentialRef(model.credentialRef())),
                List.of(new AliyunBailianProviderFactory.ModelProfile(
                        new ModelDefinitionId(model.modelId()),
                        "cli-v1",
                        model.modelId(),
                        model.modelId(),
                        EnumSet.of(
                                ModelCapability.TEXT_CHAT,
                                ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT),
                        131_072,
                        8_192,
                        Map.of(
                                "thinking_profile", "none",
                                "thinking_enabled", false,
                                "supports_tool_stream", false,
                                "tool_stream", false))));
        var definition = provider.models().getFirst();
        return ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                definition.id(),
                definition.version(),
                definition.providerModelId(),
                provider.adapterType(),
                "1.0.0",
                provider.endpoint(),
                provider.credentialRef(),
                definition.capabilities(),
                definition.contextWindow(),
                definition.maxOutputTokens(),
                provider.options(),
                definition.options());
    }
}
