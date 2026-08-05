package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.persistence.ProjectPersistenceAssembly;
import io.haifa.agent.application.project.persistence.ProjectPersistenceMode;
import io.haifa.agent.application.project.persistence.ProjectPersistenceProtection;
import io.haifa.agent.application.project.policy.CodingAgentPolicyAssembly;
import io.haifa.agent.application.project.product.ProjectProductService;
import io.haifa.agent.application.project.product.TrustedProductCaller;
import io.haifa.agent.application.project.product.TrustedProductCallerProvider;
import io.haifa.agent.application.project.product.coding.CodingSessionExportService;
import io.haifa.agent.application.project.product.coding.CodingSessionService;
import io.haifa.agent.application.project.product.coding.CodingShellService;
import io.haifa.agent.application.project.product.coding.delivery.CodingCompletionPolicy;
import io.haifa.agent.application.project.product.coding.delivery.CodingDeliveryEvidenceLedger;
import io.haifa.agent.application.project.product.coding.delivery.CodingDeliveryProfile;
import io.haifa.agent.application.project.product.coding.delivery.CodingTaskModeResolver;
import io.haifa.agent.application.project.product.coding.prompt.CodingAgentPrompt;
import io.haifa.agent.application.project.skill.ProjectSkillPlatform;
import io.haifa.agent.application.project.tool.CodingToolchainEnvironmentProfile;
import io.haifa.agent.application.project.tool.ProjectToolCatalog;
import io.haifa.agent.application.project.tool.ProjectToolExecutor;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.common.time.SystemTimeProvider;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
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
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChangeSetService;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
import io.haifa.agent.project.configuration.InMemoryProjectConfigurationStore;
import io.haifa.agent.project.configuration.ProjectConfiguration;
import io.haifa.agent.project.configuration.ProjectConfigurationService;
import io.haifa.agent.project.configuration.ProjectConfigurationVersion;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.mutation.InMemoryWorkspaceWriteLeaseManager;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.LocalWorkspaceMutationService;
import io.haifa.agent.project.provider.local.SensitivePathPolicy;
import io.haifa.agent.project.quarantine.InMemoryQuarantineStore;
import io.haifa.agent.project.store.InMemoryProjectStore;
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
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.retry.RepairRetryPolicy;
import io.haifa.agent.runtime.core.skill.DefaultSkillActivationService;
import io.haifa.agent.runtime.core.skill.SkillToolCatalogContribution;
import io.haifa.agent.runtime.core.skill.SkillToolProvider;
import io.haifa.agent.runtime.core.tool.DefaultPublicToolPolicy;
import io.haifa.agent.runtime.core.tool.DefaultToolPolicyRequestAdapter;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.crypto.spec.SecretKeySpec;

/** Builds an in-process Coding Agent over one explicitly selected local workspace. */
final class LocalCodingAgent implements AutoCloseable {
    private static final AgentDefinitionId DEFINITION_ID = new AgentDefinitionId("haifa-cli-coding-agent");
    private static final Duration CLOSE_SETTLE_TIMEOUT = Duration.ofSeconds(3);
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final AgentRuntime runtime;
    private final InteractionPort interactions;
    private final List<RuntimeTraceEvent> traces;
    private final CliMcpPlatform mcpPlatform;
    private final ProjectPersistenceAssembly persistence;
    private final TenantRef tenant;
    private final PrincipalRef principal;
    private final Clock clock;
    private final ProjectId projectId;
    private final CodingSessionService codingSessions;
    private final TrustedProjectResourceCatalog resources;
    private final Optional<CodingShellService> shell;
    private final CodingSessionExportService exporter;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<AgentRunId> startedRuns = ConcurrentHashMap.newKeySet();

    private LocalCodingAgent(
            IdentifierGenerator identifiers,
            TimeProvider time,
            AgentRuntime runtime,
            InteractionPort interactions,
            List<RuntimeTraceEvent> traces,
            CliMcpPlatform mcpPlatform,
            ProjectPersistenceAssembly persistence,
            TenantRef tenant,
            PrincipalRef principal,
            Clock clock,
            ProjectId projectId,
            CodingSessionService codingSessions,
            TrustedProjectResourceCatalog resources,
            Optional<CodingShellService> shell,
            CodingSessionExportService exporter) {
        this.identifiers = identifiers;
        this.time = time;
        this.runtime = runtime;
        this.interactions = interactions;
        this.traces = traces;
        this.mcpPlatform = mcpPlatform;
        this.persistence = persistence;
        this.tenant = tenant;
        this.principal = principal;
        this.clock = clock;
        this.projectId = projectId;
        this.codingSessions = codingSessions;
        this.resources = resources;
        this.shell = shell;
        this.exporter = exporter;
    }

    static LocalCodingAgent create(Path workspaceRoot, CliConfiguration configuration, PrintStream output) {
        return createWithTrace(workspaceRoot, configuration, output, event -> {});
    }

    static LocalCodingAgent createWithTrace(
            Path workspaceRoot,
            CliConfiguration configuration,
            PrintStream output,
            Consumer<RuntimeTraceEvent> traceObserver) {
        boolean allowInsecureLoopback =
                allowInsecureLoopback(configuration, System.getenv("HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL"));
        var model = new OpenAiCompatibleChatModel(
                "openai-compatible",
                "1.0.0",
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                new EnvironmentCredentialResolver(),
                allowInsecureLoopback,
                4 * 1024 * 1024);
        return create(workspaceRoot, configuration, output, model, traceObserver);
    }

    static boolean allowInsecureLoopback(CliConfiguration configuration, String optIn) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        if (optIn == null || !Boolean.parseBoolean(optIn.trim())) {
            return false;
        }
        List<URI> endpoints = configuration.availableModels().stream()
                .map(CliConfiguration.Model::endpoint)
                .toList();
        if (endpoints.stream().allMatch(endpoint -> "https".equalsIgnoreCase(endpoint.getScheme()))) {
            return false;
        }
        boolean safe = endpoints.stream().allMatch(endpoint -> {
            if ("https".equalsIgnoreCase(endpoint.getScheme())) return true;
            String host = endpoint.getHost();
            return "http".equalsIgnoreCase(endpoint.getScheme())
                    && host != null
                    && Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
                            .contains(host.toLowerCase(Locale.ROOT));
        });
        if (!safe) {
            throw new IllegalArgumentException(
                    "HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL permits only an HTTP loopback model endpoint");
        }
        return true;
    }

    static LocalCodingAgent create(
            Path workspaceRoot, CliConfiguration configuration, PrintStream output, AgentChatModel model) {
        return create(workspaceRoot, configuration, output, model, event -> {});
    }

    static LocalCodingAgent create(
            Path workspaceRoot,
            CliConfiguration configuration,
            PrintStream output,
            AgentChatModel model,
            Consumer<RuntimeTraceEvent> traceObserver) {
        return create(
                workspaceRoot,
                configuration,
                output,
                model,
                traceObserver,
                resolveContinuationProtector(configuration));
    }

    static LocalCodingAgent create(
            Path workspaceRoot,
            CliConfiguration configuration,
            PrintStream output,
            AgentChatModel model,
            Consumer<RuntimeTraceEvent> traceObserver,
            ModelContinuationProtector continuationProtector) {
        LocalWorkspaceIdentity workspaceIdentity = LocalWorkspaceIdentity.resolve(workspaceRoot);
        workspaceRoot = workspaceIdentity.providerRoot();
        TrustedProjectResourceCatalog resources = new TrustedProjectResourceCatalog(workspaceRoot);

        IdentifierGenerator identifiers = new UuidV7IdentifierGenerator();
        TimeProvider time = new SystemTimeProvider();
        Clock clock = Clock.systemUTC();
        PrincipalRef principal = new PrincipalRef("local-user", "user");
        TenantRef tenant = new TenantRef("local");
        ProjectPersistenceAssembly persistence =
                ProjectPersistenceAssembly.open(configuration.persistence(), clock, identifiers, continuationProtector);
        try {
            validateSkillWorkspaceIsolation(
                    workspaceRoot, configuration.skills().localDirectories());
            var skillDirectories = configuration.skills().localDirectories().stream()
                    .map(directory -> new ProjectSkillPlatform.UserDirectorySource(
                            directory.id(),
                            directory.root(),
                            directory.priority(),
                            directory.parserMode(),
                            directory.origin()))
                    .toList();
            var skillPlatform = ProjectSkillPlatform.baseAndUserDirectorySkills(
                    tenant, principal, Optional.empty(), false, skillDirectories);
            CliMcpPlatform mcpPlatform = CliMcpPlatform.connect(configuration.mcpServers(), principal);
            CliWebPlatform webPlatform = CliWebPlatform.create(configuration.web(), principal);
            var projects = new InMemoryProjectStore();
            var workspaces = new InMemoryWorkspaceStore();
            var bindings = new InMemoryWorkspaceBindingStore();
            var locations = new LocalWorkspaceLocationStore();
            WorkspaceId workspaceId = workspaceIdentity.workspaceId();
            WorkspaceBindingId bindingId = workspaceIdentity.bindingId();
            WorkspaceLocationRef locationRef = workspaceIdentity.locationRef();
            locations.register(locationRef, workspaceRoot);
            Set<String> configuredTools = effectiveBuiltInTools(configuration);
            var policy = CodingAgentPolicyAssembly.create(
                    policyMode(configuration.approval()), clock, identifiers::nextValue, persistence.policy());
            boolean executionEnabled = configuredTools.contains("execution.run");
            Set<String> effectiveCapabilities = executionEnabled
                    ? Set.of("file.read", "file.write", "execution.run")
                    : Set.of("file.read", "file.write");
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
                            workspaceIdentity.rootFingerprint(),
                            time.now())
                    .activate(time.now()));
            var configurationStore = new InMemoryProjectConfigurationStore();
            var configurationService = new ProjectConfigurationService(configurationStore);
            var configurationId = workspaceIdentity.configurationId();
            var configurationVersion = new ProjectConfigurationVersion("1.0.0");
            var projectConfiguration = ProjectConfiguration.create(
                    configurationId,
                    configurationVersion,
                    workspaceId,
                    "cli-coding",
                    "1.0.0",
                    effectiveCapabilities,
                    Set.of("project-index"),
                    Set.copyOf(configuredTools),
                    "coding-agent-policy-v1");
            configurationService.publish(projectConfiguration);
            ProjectId projectId = workspaceIdentity.projectId();
            Project project = Project.create(
                            projectId,
                            tenant,
                            principal,
                            workspaceIdentity.safeDisplayName(),
                            "Haifa Coding Agent workspace",
                            new ProjectConfigurationRef(configurationId.value(), configurationVersion.value()),
                            time.now(),
                            Map.of("identityNamespace", "local-workspace-v1"))
                    .assignDefaultWorkspace(workspaceId, time.now());
            projects.create(project);
            workspaces.create(Workspace.provision(
                            workspaceId,
                            projectId,
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
                            clock,
                            policy,
                            workspaceRoot,
                            output)
                    : null;
            var provider = new ProjectToolExecutor(
                    (runId, ignoredPrincipal) -> new io.haifa.agent.application.project.tool.RunWorkspaceAccess(
                            workspaceId, effectiveCapabilities),
                    operations,
                    executionPlatform == null ? null : executionPlatform.operations());
            var skillService = new DefaultSkillActivationService(
                    persistence.ports().runs(), persistence.ports().state(), skillPlatform.contentLoader(), time);
            List<SkillToolCatalogContribution> skillTools =
                    configuration.skills().allowedAliases().isEmpty()
                            ? List.of()
                            : new SkillToolProvider(skillService).contributions();
            var catalog = new ProjectToolCatalog()
                    .freeze(
                            Set.copyOf(configuredTools),
                            effectiveCapabilities,
                            true,
                            provider,
                            mcpPlatform.contributions(),
                            webPlatform.contributions(),
                            skillTools,
                            executionPlatform == null ? null : executionPlatform.profile(),
                            CodingToolchainEnvironmentProfile.defaultScratchSpace());
            var interactions = persistence.ports().interactions();
            Map<String, ResolvedModelSnapshot> modelSnapshots = configuration.availableModels().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            CliConfiguration.Model::id, LocalCodingAgent::modelSnapshot));
            List<RuntimeTraceEvent> traces = new CopyOnWriteArrayList<>();
            var taskModes = new CodingTaskModeResolver(persistence.ports().state());
            var deliveryEvidence =
                    new CodingDeliveryEvidenceLedger(persistence.ports().state());
            var deliveryProfile = CodingDeliveryProfile.safeDefault();
            var runtime = persistence
                    .configure(new RuntimeCoreBuilder())
                    .identifierGenerator(identifiers)
                    .timeProvider(time)
                    .trace(event -> {
                        traces.add(event);
                        traceObserver.accept(event);
                    })
                    .completionPolicy(new CodingCompletionPolicy(taskModes, deliveryEvidence, deliveryProfile))
                    .repairRetry(new RepairRetryPolicy(2))
                    .registerChatModel("openai-compatible", "1.0.0", model)
                    .credentialBroker(webPlatform.credentialBroker())
                    .toolPlatform(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator())
                    .skillPlatform(skillPlatform.catalog(), skillPlatform.contentLoader())
                    .toolApprovalPrompts((binding, call, reauthentication) -> {
                        if (!binding.definition().name().value().equals("execution.run")) {
                            return io.haifa.agent.runtime.core.interaction.ToolApprovalPromptFormatter
                                    .defaultFormatter()
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
                                + "\nSecurity: "
                                + (executionPlatform == null
                                        ? "execution unavailable"
                                        : executionPlatform.securitySummary());
                    })
                    .policyStores(policy.decisionsStore(), policy.evidence())
                    .approvalVerification(policy.approvalVerification())
                    .publicToolPolicy(new DefaultPublicToolPolicy(
                            new DefaultToolPolicyRequestAdapter(
                                    "haifa-coding-agent", policyMode(configuration.approval())),
                            policy.decisions(),
                            policy.decisionsStore(),
                            policy.snapshot(),
                            () -> new io.haifa.agent.policy.api.PolicyDecisionId(identifiers.nextValue()),
                            clock))
                    .definitions((id, requested) -> new ResolvedDefinition(
                            id,
                            requested.orElse(new AgentDefinitionVersion(1, 0, 0)),
                            catalog.snapshot().bindings().stream()
                                    .map(binding -> binding.alias().value())
                                    .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                            configuration.skills().allowedAliases(),
                            Set.of(),
                            CodingAgentPrompt.current().text()
                                    + executionEnvironmentPrompt(
                                            executionPlatform == null ? "" : executionPlatform.shellDisplayName())
                                    + resources.snapshot().instructionBlock(),
                            List.of()))
                    .profiles((profileId, overrides) -> new ResolvedProfile(
                            profileId,
                            "1.0.0",
                            AgentRunType.CHAT,
                            new AgentRunBudget(
                                    1_000_000,
                                    1_000_000,
                                    1_000_000,
                                    configuration.maxToolCalls(),
                                    64,
                                    8,
                                    "USD",
                                    1_000_000),
                            new AgentRunLimits(
                                    configuration.maxIterations(),
                                    4,
                                    1,
                                    configuration.timeout().toMillis(),
                                    configuration.timeout().toMillis()),
                            Optional.ofNullable(modelSnapshots.get(profileId))
                                    .or(() -> "cli-coding".equals(profileId)
                                            ? Optional.of(modelSnapshots.get(
                                                    configuration.model().id()))
                                            : Optional.empty())
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "MODEL_SELECTION_REQUIRED: configured model is unavailable"))))
                    .build();
            persistence.attachProjection(runtime);
            TrustedProductCallerProvider callers = () -> new TrustedProductCaller(tenant, principal);
            var projectProducts = new ProjectProductService(
                    projects,
                    workspaces,
                    configurationService,
                    persistence.productSessions(),
                    persistence.projectSessionProvisioner(clock),
                    callers,
                    runtime,
                    identifiers,
                    DEFINITION_ID);
            var codingSessions = new CodingSessionService(
                    projectProducts,
                    persistence.productSessions(),
                    persistence.codingSessions(),
                    persistence.codingSessionLifecycle(),
                    persistence.codingSessionCompactor(identifiers, time),
                    callers,
                    runtime,
                    identifiers,
                    clock,
                    new CliCodingModelCatalog(configuration));
            CodingShellService shell = executionPlatform == null
                    ? null
                    : new CliCodingShellService(
                            codingSessions,
                            executionPlatform.operations(),
                            policy,
                            persistence.ports(),
                            identifiers,
                            time,
                            tenant,
                            principal,
                            projectId,
                            workspaceId,
                            configuration.execution().defaultTimeout(),
                            executionPlatform.profileDigest());
            var agent = new LocalCodingAgent(
                    identifiers,
                    time,
                    runtime,
                    interactions,
                    traces,
                    mcpPlatform,
                    persistence,
                    tenant,
                    principal,
                    clock,
                    projectId,
                    codingSessions,
                    resources,
                    Optional.ofNullable(shell),
                    new CliCodingSessionExportService(
                            workspaceRoot,
                            codingSessions,
                            persistence.ports().state(),
                            webPlatform.credentialBroker().redactor()));
            runtime.addListener(snapshot -> agent.startedRuns.add(snapshot.runId()));
            return agent;
        } catch (RuntimeException | Error exception) {
            try {
                persistence.close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    static String executionEnvironmentPrompt(String shellDisplayName) {
        if (shellDisplayName == null || shellDisplayName.isBlank()) return "";
        return "\n\nRuntime execution environment:\n"
                + "- execution_run uses "
                + shellDisplayName.strip()
                + " command syntax on this host.\n"
                + "- Generate commands for that configured shell; do not assume a POSIX shell or mix shell dialects.";
    }

    AgentRunSnapshot start(String message) {
        if (closed.get()) throw new IllegalStateException("coding agent is closed");
        AgentSessionId sessionId = new AgentSessionId(identifiers.nextValue());
        persistence.provisionUserSession(sessionId, tenant, principal, clock);
        AgentRunSnapshot accepted = runtime.start(new AgentRunRequest(
                identifiers.nextValue(),
                DEFINITION_ID,
                Optional.empty(),
                "cli-coding",
                sessionId,
                Optional.empty(),
                message,
                List.of(),
                RuntimeOverrides.NONE));
        startedRuns.add(accepted.runId());
        return accepted;
    }

    AgentRuntime runtime() {
        return runtime;
    }

    ProjectId projectId() {
        return projectId;
    }

    CodingSessionService codingSessions() {
        return codingSessions;
    }

    List<String> loadedResources() {
        return resources.snapshot().diagnostics();
    }

    List<String> reloadResources() {
        return resources.reload().diagnostics();
    }

    Optional<CodingShellService> shell() {
        return shell;
    }

    CodingSessionExportService exporter() {
        return exporter;
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

    InteractionPort interactions() {
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

    boolean executionSettled(AgentRunId runId) {
        return persistence.ports().attempts().activeFor(runId).isEmpty();
    }

    static Set<String> effectiveBuiltInTools(CliConfiguration configuration) {
        java.util.Set<String> configuredTools = new java.util.HashSet<>(configuration.enabledTools());
        if (configuration.approval() == ApprovalMode.DENY) configuredTools.remove("execution.run");
        return Set.copyOf(configuredTools);
    }

    private static io.haifa.agent.policy.api.ApprovalMode policyMode(ApprovalMode mode) {
        return io.haifa.agent.policy.api.ApprovalMode.valueOf(mode.name());
    }

    private static void validateSkillWorkspaceIsolation(
            Path workspaceRoot, List<CliConfiguration.LocalSkillDirectory> localDirectories) {
        Path realWorkspaceRoot;
        try {
            realWorkspaceRoot = workspaceRoot.toRealPath();
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("CLI workspace is unavailable", exception);
        }
        for (CliConfiguration.LocalSkillDirectory directory : localDirectories) {
            Path skillRoot;
            try {
                skillRoot = directory.root().toRealPath();
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("local Skill source is unavailable: " + directory.id(), exception);
            }
            if (realWorkspaceRoot.startsWith(skillRoot) || skillRoot.startsWith(realWorkspaceRoot)) {
                throw new IllegalArgumentException(
                        "local Skill source root must not overlap the CLI workspace: " + directory.id());
            }
        }
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
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = awaitTerminalAttempts();
        try {
            mcpPlatform.close();
        } catch (RuntimeException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        try {
            persistence.close();
        } catch (RuntimeException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
    }

    private RuntimeException awaitTerminalAttempts() {
        long deadlineMillis = System.currentTimeMillis() + CLOSE_SETTLE_TIMEOUT.toMillis();
        for (AgentRunId runId : startedRuns) {
            if (runtime.find(runId)
                    .filter(snapshot -> snapshot.status().isTerminal())
                    .isEmpty()) continue;
            while (!executionSettled(runId) && System.currentTimeMillis() < deadlineMillis) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return new IllegalStateException(
                            "interrupted while waiting for execution attempt persistence", exception);
                }
            }
            if (!executionSettled(runId)) {
                return new IllegalStateException("execution attempt did not settle before coding agent close");
            }
        }
        return null;
    }

    private static ModelContinuationProtector resolveContinuationProtector(CliConfiguration configuration) {
        if (configuration.persistence().mode() == ProjectPersistenceMode.MEMORY
                || configuration.persistence().protection() == ProjectPersistenceProtection.NONE) {
            return null;
        }
        String reference = configuration
                .persistence()
                .protectorReference()
                .orElseThrow(() -> new IllegalArgumentException("durable continuation protector is not configured"));
        String environmentName = reference.substring("env://".length());
        String encoded = System.getenv(environmentName);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("durable continuation protector secret is unavailable");
        }
        try {
            byte[] key = java.util.Base64.getDecoder().decode(encoded.trim());
            if (key.length != 32) {
                throw new IllegalArgumentException("durable continuation protector secret must be a 256-bit key");
            }
            return new AesGcmModelContinuationProtector(new SecretKeySpec(key, "AES"), new SecureRandom());
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("durable continuation protector")) {
                throw exception;
            }
            throw new IllegalArgumentException("durable continuation protector secret is invalid");
        }
    }

    static ResolvedModelSnapshot modelSnapshot(CliConfiguration configuration) {
        return modelSnapshot(configuration.model());
    }

    static ResolvedModelSnapshot modelSnapshot(CliConfiguration.Model model) {
        if (OpenAiCompatibleDialects.ALIYUN_BAILIAN.equals(model.dialectId())) {
            return bailianModelSnapshot(model);
        }
        Map<String, Object> providerOptions = new java.util.LinkedHashMap<>(OpenAiCompatibleDialects.configuredOptions(
                model.dialectId(), model.dialectVersion(), model.nativeStreaming(), model.endpoint()));
        boolean deepSeek = OpenAiCompatibleDialects.DEEPSEEK.equals(model.dialectId());
        if (deepSeek) providerOptions.put("thinking", "disabled");
        return ResolvedModelSnapshot.create(
                new ModelProviderId(model.providerId()),
                "cli-v1",
                new ModelDefinitionId(model.id()),
                "cli-v1",
                model.modelId(),
                "openai-compatible",
                "1.0.0",
                model.endpoint(),
                new CredentialRef(model.credentialRef()),
                deepSeek
                        ? EnumSet.of(
                                ModelCapability.TEXT_CHAT,
                                ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT,
                                ModelCapability.REASONING)
                        : EnumSet.of(
                                ModelCapability.TEXT_CHAT,
                                ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT),
                131_072,
                8_192,
                Map.copyOf(providerOptions),
                deepSeek ? Map.of("thinking", "disabled") : Map.of());
    }

    private static ResolvedModelSnapshot bailianModelSnapshot(CliConfiguration.Model model) {
        if (!OpenAiCompatibleDialects.VERSION_1.equals(model.dialectVersion())) {
            throw new IllegalArgumentException("unsupported aliyun-bailian-openai-chat dialect version");
        }
        var provider = AliyunBailianProviderFactory.provider(
                new AliyunBailianProviderFactory.ProviderConfiguration(
                        "cli-v1", model.workspaceId(), model.region(), new CredentialRef(model.credentialRef())),
                List.of(new AliyunBailianProviderFactory.ModelProfile(
                        new ModelDefinitionId(model.id()),
                        "cli-v1",
                        model.displayName(),
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
        Map<String, Object> providerOptions = new java.util.LinkedHashMap<>(provider.options());
        providerOptions.put(OpenAiCompatibleDialects.NATIVE_STREAMING, model.nativeStreaming());
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
                Map.copyOf(providerOptions),
                definition.options());
    }
}
