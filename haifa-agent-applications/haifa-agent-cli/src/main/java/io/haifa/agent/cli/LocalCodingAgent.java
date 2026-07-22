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
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
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
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Builds an in-process Coding Agent over one explicitly selected local workspace. */
final class LocalCodingAgent implements AutoCloseable {
    private static final AgentDefinitionId DEFINITION_ID = new AgentDefinitionId("haifa-cli-coding-agent");
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final AgentRuntime runtime;
    private final InMemoryInteractionPort interactions;
    private final CliMcpPlatform mcpPlatform;

    private LocalCodingAgent(
            IdentifierGenerator identifiers,
            TimeProvider time,
            AgentRuntime runtime,
            InMemoryInteractionPort interactions,
            CliMcpPlatform mcpPlatform) {
        this.identifiers = identifiers;
        this.time = time;
        this.runtime = runtime;
        this.interactions = interactions;
        this.mcpPlatform = mcpPlatform;
    }

    static LocalCodingAgent create(Path workspaceRoot, CliConfiguration configuration) {
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
        bindings.create(WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        principal,
                        WorkspaceCapabilitySet.readWriteFiles(),
                        WorkspacePermissionSet.readWrite(),
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
        var mutations = new LocalWorkspaceMutationService(
                workspaces,
                bindings,
                locations,
                sensitivePaths,
                new InMemoryWorkspaceWriteLeaseManager(),
                changeSets,
                new FileChangeSetService(changeSets, identifiers, time),
                new InMemoryQuarantineStore(),
                identifiers,
                time);
        var operations = new LocalFileToolOperations(workspaces, files, mutations, identifiers);
        var provider = new ProjectToolExecutor(
                (runId, ignoredPrincipal) -> new io.haifa.agent.application.project.tool.RunWorkspaceAccess(
                        workspaceId, configuration.enabledTools(), "cli-local-policy"),
                operations);
        var catalog = new ProjectToolCatalog()
                .freeze(
                        configuration.enabledTools(),
                        Set.of("file.read", "file.write"),
                        true,
                        provider,
                        mcpPlatform.contributions());
        var interactions = new InMemoryInteractionPort();
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
        ResolvedModelSnapshot modelSnapshot = modelSnapshot(configuration);
        var runtime = new RuntimeCoreBuilder()
                .identifierGenerator(identifiers)
                .timeProvider(time)
                .interactions(interactions)
                .registerChatModel("openai-compatible", "1.0.0", model)
                .toolPlatform(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator())
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
                .definitions(
                        (id, requested) -> new ResolvedDefinition(
                                id,
                                requested.orElse(new AgentDefinitionVersion(1, 0, 0)),
                                catalog.snapshot().bindings().stream()
                                        .map(binding -> binding.alias().value())
                                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                                Set.of(),
                                "You are a careful local coding agent. Inspect relevant files before editing. "
                                        + "Use tools for workspace facts, preserve existing changes, and summarize completed work."))
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
        return new LocalCodingAgent(identifiers, time, runtime, interactions, mcpPlatform);
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

    InMemoryInteractionPort interactions() {
        return interactions;
    }

    IdentifierGenerator identifiers() {
        return identifiers;
    }

    TimeProvider time() {
        return time;
    }

    @Override
    public void close() {
        mcpPlatform.close();
    }

    private static ResolvedModelSnapshot modelSnapshot(CliConfiguration configuration) {
        CliConfiguration.Model model = configuration.model();
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
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.STRUCTURED_OUTPUT),
                131_072,
                8_192,
                Map.of("thinking", "disabled"),
                Map.of("thinking", "disabled"));
    }
}
