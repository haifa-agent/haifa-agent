package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.persistence.ProjectPersistenceAssembly;
import io.haifa.agent.application.project.persistence.ProjectPersistenceMode;
import io.haifa.agent.application.project.persistence.ProjectPersistenceProtection;
import io.haifa.agent.application.project.policy.CodingAgentPolicyAssembly;
import io.haifa.agent.application.project.product.ProjectProductService;
import io.haifa.agent.application.project.product.TrustedProductCaller;
import io.haifa.agent.application.project.product.TrustedProductCallerProvider;
import io.haifa.agent.application.project.product.coding.CodingModelState;
import io.haifa.agent.application.project.product.coding.CodingSessionExportService;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryService;
import io.haifa.agent.application.project.product.coding.CodingSessionService;
import io.haifa.agent.application.project.product.coding.CodingShellService;
import io.haifa.agent.application.project.product.coding.CodingWorkspaceView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.delivery.CodingRunOutcomeProjectionService;
import io.haifa.agent.application.project.product.coding.prompt.CodingAgentPrompt;
import io.haifa.agent.application.project.skill.ProjectSkillPlatform;
import io.haifa.agent.application.project.tool.ProjectToolCatalog;
import io.haifa.agent.application.project.tool.ProjectToolExecutor;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginCoordinator;
import io.haifa.agent.auth.localmodel.ExternalLoginMethod;
import io.haifa.agent.auth.localmodel.ExternalLoginRegistry;
import io.haifa.agent.auth.localmodel.LocalModelAuthReference;
import io.haifa.agent.auth.localmodel.LocalModelAuthStore;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import io.haifa.agent.auth.localmodel.LocalModelCredentialResolver;
import io.haifa.agent.auth.localmodel.StoredApiKeyCredential;
import io.haifa.agent.auth.localmodel.WindowsLocalModelAuthStore;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityExternalLoginMethod;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityLocalCompatibilityRegistrationFactory;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityProjectRegistry;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityTokenClient;
import io.haifa.agent.auth.localmodel.codex.CodexDeviceLoginOperation;
import io.haifa.agent.auth.localmodel.codex.CodexExternalLoginMethod;
import io.haifa.agent.auth.localmodel.codex.CodexLocalCompatibilityRegistrationFactory;
import io.haifa.agent.auth.localmodel.codex.CodexTokenClient;
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
import io.haifa.agent.execution.api.ToolOutputPreviewPublisher;
import io.haifa.agent.model.anthropic.AnthropicMessagesModel;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelParameterResolutionRequest;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ModelReasoningPolicy;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.core.DefaultModelParameterResolver;
import io.haifa.agent.model.core.PackagedModelCatalog;
import io.haifa.agent.model.gemini.GeminiGenerateContentModel;
import io.haifa.agent.model.openai.AliyunBailianProviderFactory;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import io.haifa.agent.model.openai.responses.OpenAiResponsesDialects;
import io.haifa.agent.model.openai.responses.OpenAiResponsesModel;
import io.haifa.agent.project.configuration.ProjectConfiguration;
import io.haifa.agent.project.configuration.ProjectConfigurationVersion;
import io.haifa.agent.project.core.configuration.InMemoryProjectConfigurationStore;
import io.haifa.agent.project.core.configuration.ProjectConfigurationService;
import io.haifa.agent.project.core.ledger.InMemorySessionChangeLedger;
import io.haifa.agent.project.core.mutation.InMemoryWorkspaceWriteLeaseManager;
import io.haifa.agent.project.core.store.InMemoryProjectStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.core.workspace.WorkspaceService;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceFileService;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.HostWorkspaceMutationService;
import io.haifa.agent.project.hostworkspace.HostWorkspacePathSafety;
import io.haifa.agent.project.hostworkspace.SensitivePathPolicy;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryStatus;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryView;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.ApprovalPresentation;
import io.haifa.agent.runtime.api.ApprovalPrompt;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.model.ModelAdapterKey;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.retry.CompletionRepairPolicy;
import io.haifa.agent.runtime.core.skill.DefaultSkillActivationService;
import io.haifa.agent.runtime.core.skill.SkillToolCatalogContribution;
import io.haifa.agent.runtime.core.skill.SkillToolProvider;
import io.haifa.agent.runtime.core.tool.DefaultPublicToolPolicy;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.awt.Desktop;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.crypto.spec.SecretKeySpec;

/** Builds an in-process Coding Agent over one explicitly selected local workspace. */
final class LocalCodingAgent implements AutoCloseable {
    private static final AgentDefinitionId DEFINITION_ID = new AgentDefinitionId("haifa-cli-coding-agent");
    private static final Duration CLOSE_SETTLE_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_APPROVAL_CONTENT_LENGTH = 16_000;
    private static final int MAX_APPROVAL_TITLE_LENGTH = 256;
    private static final int MAX_PURPOSE_LENGTH = 512;
    private static final int MAX_CONTENT_TYPE_LENGTH = 64;
    private static final int MAX_FACT_VALUE_LENGTH = 512;
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
    private final CodingSessionHistoryService sessionHistory;
    private final TrustedProjectResourceCatalog resources;
    private final AuthorizedWorkspaceProvisioning workspaceProvisioning;
    private final Optional<CodingShellService> shell;
    private final Optional<CliExecutionPlatform> executionPlatform;
    private final CodingSessionExportService exporter;
    private final CodingRunOutcomeProjectionService outcomes;
    private final CodingAuthenticationClient authentication;
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
            CodingSessionHistoryService sessionHistory,
            TrustedProjectResourceCatalog resources,
            AuthorizedWorkspaceProvisioning workspaceProvisioning,
            Optional<CodingShellService> shell,
            Optional<CliExecutionPlatform> executionPlatform,
            CodingSessionExportService exporter,
            CodingRunOutcomeProjectionService outcomes,
            CodingAuthenticationClient authentication) {
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
        this.sessionHistory = sessionHistory;
        this.resources = resources;
        this.workspaceProvisioning =
                Objects.requireNonNull(workspaceProvisioning, "workspaceProvisioning must not be null");
        this.shell = shell;
        this.executionPlatform = executionPlatform;
        this.exporter = exporter;
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.authentication = Objects.requireNonNull(authentication, "authentication must not be null");
    }

    static LocalCodingAgent create(Path workspaceRoot, CliConfiguration configuration, PrintStream output) {
        return createWithTrace(workspaceRoot, configuration, output, event -> {});
    }

    static LocalCodingAgent createWithTrace(
            Path workspaceRoot,
            CliConfiguration configuration,
            PrintStream output,
            Consumer<RuntimeTraceEvent> traceObserver) {
        return createWithTrace(workspaceRoot, configuration, output, traceObserver, System::getenv, System.getenv());
    }

    static LocalCodingAgent createWithTrace(
            Path workspaceRoot,
            CliConfiguration configuration,
            PrintStream output,
            Consumer<RuntimeTraceEvent> traceObserver,
            Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        return createWithTrace(workspaceRoot, configuration, output, traceObserver, environment::get, environment);
    }

    static LocalCodingAgent createWithTrace(
            Path workspaceRoot,
            CliConfiguration configuration,
            PrintStream output,
            Consumer<RuntimeTraceEvent> traceObserver,
            Function<String, String> environmentResolver,
            Map<String, String> executionEnvironment) {
        Objects.requireNonNull(environmentResolver, "environmentResolver must not be null");
        boolean allowInsecureLoopback =
                allowInsecureLoopback(configuration, environmentResolver.apply("HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL"));
        var http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var json = new ObjectMapper();
        Clock authClock = Clock.systemUTC();
        var authStore = WindowsLocalModelAuthStore.defaultStore(json);
        var codexRegistration = CodexLocalCompatibilityRegistrationFactory.create(environmentResolver);
        var antigravityRegistration = AntigravityLocalCompatibilityRegistrationFactory.create(environmentResolver);
        var antigravityProjects = new AntigravityProjectRegistry();
        List<ExternalLoginMethod> authMethods = new ArrayList<>();
        codexRegistration.ifPresent(registration -> authMethods.add(new CodexExternalLoginMethod(
                registration,
                new CodexTokenClient(http, json, authClock, Duration.ofSeconds(30), registration),
                http,
                json,
                SecureRandom::new,
                Duration.ofMinutes(5),
                CodexDeviceLoginOperation.Sleeper.system())));
        antigravityRegistration.ifPresent(registration -> authMethods.add(new AntigravityExternalLoginMethod(
                registration,
                new AntigravityTokenClient(http, json, authClock, Duration.ofSeconds(30), registration),
                http,
                json,
                SecureRandom::new,
                Duration.ofMinutes(5),
                projection -> antigravityProjects.record(
                        new CredentialRef("model-auth://google-antigravity/default"), projection))));
        var authRegistry = new ExternalLoginRegistry(authMethods);
        var credentials = new LocalModelCredentialResolver(
                environmentResolver, authStore, authRegistry, authClock, Duration.ofMinutes(5));
        var authIdentifiers = new UuidV7IdentifierGenerator();
        var authCoordinator = authMethods.isEmpty()
                ? Optional.<ExternalLoginCoordinator>empty()
                : Optional.of(new ExternalLoginCoordinator(
                        authRegistry,
                        authStore,
                        () -> new ExternalLoginAttemptId(authIdentifiers.nextValue()),
                        authClock,
                        Executors.newFixedThreadPool(2, runnable -> {
                            Thread thread = new Thread(runnable, "haifa-local-model-auth");
                            thread.setDaemon(true);
                            return thread;
                        }),
                        LocalCodingAgent::openBrowser,
                        8));
        var authenticationService =
                new LocalModelAuthenticationService(authStore, authCoordinator, credentials, environmentResolver);
        var authentication = new CliCodingAuthenticationClient(
                authenticationService,
                configuration.model().credentialRef(),
                configuration.model().providerId(),
                configuration.availableModels().stream()
                        .map(CliConfiguration.Model::credentialRef)
                        .toList(),
                antigravityRegistration.isPresent());
        var chat = new OpenAiCompatibleChatModel(
                "openai-compatible",
                "1.0.0",
                http,
                json,
                credentials,
                allowInsecureLoopback,
                configuration.modelMaxResponseBytes());
        var responses = new OpenAiResponsesModel(
                http,
                json,
                credentials,
                allowInsecureLoopback,
                configuration.modelMaxResponseBytes(),
                ref -> authenticationService
                        .findExternalAccountId(
                                ref, io.haifa.agent.auth.localmodel.codex.CodexExternalLoginMethod.METHOD_ID)
                        .map(io.haifa.agent.model.openai.responses.CodexAccountIdentity::new));
        var anthropic = new AnthropicMessagesModel(
                http, json, credentials, allowInsecureLoopback, configuration.modelMaxResponseBytes());
        var gemini = new GeminiGenerateContentModel(
                http,
                json,
                credentials,
                allowInsecureLoopback,
                configuration.modelMaxResponseBytes(),
                false,
                antigravityProjects::resolve);
        return create(
                workspaceRoot,
                configuration,
                output,
                Map.of(
                        new ModelAdapterKey(ModelApiStyles.OPENAI_CHAT_ADAPTER, "1.0.0"), chat,
                        new ModelAdapterKey(ModelApiStyles.OPENAI_RESPONSES_ADAPTER, "1.0.0"), responses,
                        new ModelAdapterKey(ModelApiStyles.ANTHROPIC_MESSAGES_ADAPTER, "1.0.0"), anthropic,
                        new ModelAdapterKey(ModelApiStyles.GOOGLE_GEMINI_ADAPTER, "1.0.0"), gemini),
                traceObserver,
                resolveContinuationProtector(configuration, environmentResolver),
                environmentResolver,
                executionEnvironment,
                authStore,
                authentication,
                model -> connectionState(authenticationService, model));
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
                resolveContinuationProtector(configuration, System::getenv));
    }

    static LocalCodingAgent create(
            Path workspaceRoot,
            CliConfiguration configuration,
            PrintStream output,
            AgentChatModel model,
            Consumer<RuntimeTraceEvent> traceObserver,
            ModelContinuationProtector continuationProtector) {
        ResolvedModelSnapshot selected = modelSnapshot(configuration);
        return create(
                workspaceRoot,
                configuration,
                output,
                Map.of(new ModelAdapterKey(selected.adapterType(), selected.adapterVersion()), model),
                traceObserver,
                continuationProtector,
                System::getenv,
                System.getenv(),
                null,
                CodingAuthenticationClient.unavailable(),
                ignored -> CodingModelState.Connection.CONNECTED);
    }

    private static LocalCodingAgent create(
            Path workspaceRoot,
            CliConfiguration configuration,
            PrintStream output,
            Map<ModelAdapterKey, AgentChatModel> modelAdapters,
            Consumer<RuntimeTraceEvent> traceObserver,
            ModelContinuationProtector continuationProtector,
            Function<String, String> environmentResolver,
            Map<String, String> executionEnvironment,
            io.haifa.agent.auth.localmodel.LocalModelAuthStore authStore,
            CodingAuthenticationClient authentication,
            Function<CliConfiguration.Model, CodingModelState.Connection> connectionState) {
        Objects.requireNonNull(environmentResolver, "environmentResolver must not be null");
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
            validateAllowedSkills(configuration.skills(), skillPlatform);
            CliMcpPlatform mcpPlatform = CliMcpPlatform.connect(configuration.mcpServers(), principal);
            CliWebPlatform webPlatform = CliWebPlatform.create(configuration.web(), principal, environmentResolver);
            var projects = new InMemoryProjectStore();
            var workspaces = new InMemoryWorkspaceStore();
            var locations = new HostWorkspaceLocationStore();
            WorkspaceId workspaceId = workspaceIdentity.workspaceId();
            var signalDiscovery =
                    CliWorkspaceSignalDiscovery.discoverWithSignals(workspaceRoot, System.getProperty("os.name", ""));
            locations.register(workspaceId, workspaceRoot);
            Set<String> configuredTools = effectiveBuiltInTools(configuration);
            var policy = CodingAgentPolicyAssembly.create(
                    policyMode(configuration.approval()), configuration.approvalThreshold());
            boolean executionEnabled = configuredTools.contains("execution_run");
            Set<String> effectiveCapabilities = executionEnabled
                    ? Set.of("file_read", "file_write", "execution_run")
                    : Set.of("file_read", "file_write");
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
            workspaces.create(
                    Workspace.provision(workspaceId, projectId, WorkspaceRevision.initial("cli-initial"), time.now())
                            .activate(time.now()));

            SensitivePathPolicy sensitivePaths = SensitivePathPolicy.defaults();
            var files = new HostWorkspaceFileService(workspaces, locations, sensitivePaths);
            WorkspaceService workspaceService = new WorkspaceService(projects, workspaces, time);
            Path realRoot;
            try {
                realRoot = workspaceRoot.toRealPath();
            } catch (IOException e) {
                realRoot = workspaceRoot.toAbsolutePath().normalize();
            }
            AuthorizedHostDirectory initialDir = AuthorizedHostDirectory.of(workspaceId, realRoot);
            HostWorkspaceScope initialScope = HostWorkspaceScope.initial(initialDir);
            AuthorizedWorkspaceProvisioning provisioning = new AuthorizedWorkspaceProvisioning(
                    projectId,
                    workspaces,
                    locations,
                    workspaceService,
                    tenant,
                    principal,
                    time,
                    initialScope,
                    persistence.authorizedDirectories(),
                    workspaceIdentity.safeDisplayName());
            var executionCanonicalizer =
                    new io.haifa.agent.application.project.tool.CodingExecutionToolRequestCanonicalizer();
            PublicToolPolicy publicToolPolicy = workspaceAccessPolicy(
                    new DefaultPublicToolPolicy(
                            new io.haifa.agent.application.project.policy.CodingExecutionPolicyRequestAdapter(
                                    policyMode(configuration.approval())),
                            policy.evaluator(),
                            policy.rules()),
                    provisioning,
                    tenant,
                    principal);
            var runtimeExecutionVerifier = new io.haifa.agent.runtime.core.tool.RuntimeToolExecutionVerifier(
                    persistence.ports().runs(),
                    persistence.ports().state(),
                    persistence.ports().interactions(),
                    executionCanonicalizer,
                    publicToolPolicy);
            var sessionLedger = new InMemorySessionChangeLedger();
            var mutations = new HostWorkspaceMutationService(
                    workspaces, locations, sensitivePaths, new InMemoryWorkspaceWriteLeaseManager(), identifiers, time);
            CliExecutionPlatform executionPlatform = executionEnabled
                    ? CliExecutionPlatform.create(
                            configuration.execution(),
                            workspaces,
                            locations,
                            files,
                            identifiers,
                            time,
                            workspaceRoot,
                            output,
                            executionEnvironment != null ? executionEnvironment : System.getenv(),
                            provisioning,
                            tenant,
                            principal,
                            runtimeExecutionVerifier,
                            deniedEnvironmentNames(configuration))
                    : null;
            var operations = new LocalFileToolOperations(
                    workspaces,
                    files,
                    mutations,
                    identifiers,
                    time,
                    provisioning,
                    sessionLedger,
                    configuredTools.contains("workspace_attach"),
                    tenant,
                    principal);
            TrustedWorkspaceEnvironmentCatalog workspaceEnvironment = new TrustedWorkspaceEnvironmentCatalog(
                    workspaceRoot,
                    signalDiscovery,
                    resources.snapshot(),
                    TrustedWorkspaceEnvironmentCatalog.EnvironmentFacts.capture(
                            executionPlatform == null ? "unavailable" : executionPlatform.shellDisplayName(),
                            executionPlatform != null,
                            executionPlatform == null ? "UNAVAILABLE" : "ALLOW",
                            configuration.execution().maximumTimeout()));
            var interactions = persistence.ports().interactions();
            io.haifa.agent.application.project.tool.RunWorkspaceAccessResolver workspaceAccessResolver =
                    (runId, requestedPrincipal) -> {
                        if (!principal.equals(requestedPrincipal)) {
                            throw new SecurityException("WORKSPACE_ACCESS_OWNER_MISMATCH");
                        }
                        var current = provisioning.requireAuthorized(
                                tenant, principal, workspaceId, WorkspaceAccessMode.READ);
                        Set<String> currentCapabilities = current.mode() == WorkspaceAccessMode.READ
                                ? Set.of("file_read")
                                : effectiveCapabilities;
                        return new io.haifa.agent.application.project.tool.RunWorkspaceAccess(
                                workspaceId, currentCapabilities);
                    };
            var provider = new ProjectToolExecutor(
                    workspaceAccessResolver,
                    operations,
                    executionPlatform == null ? null : executionPlatform.operations());
            var skillService = new DefaultSkillActivationService(
                    persistence.ports().runs(), persistence.ports().state(), skillPlatform.contentLoader(), time);
            List<SkillToolCatalogContribution> skillTools =
                    configuration.skills().allowedAliases().isEmpty()
                            ? List.of()
                            : new SkillToolProvider(skillService).contributions();
            var catalog = new ProjectToolCatalog(configuration.execution().maximumTimeout())
                    .freeze(
                            Set.copyOf(configuredTools),
                            effectiveCapabilities,
                            true,
                            provider,
                            mcpPlatform.contributions(),
                            webPlatform.contributions(),
                            skillTools,
                            executionPlatform == null ? null : executionPlatform.profile());
            Set<String> disclosedToolAliases = catalog.snapshot().bindings().stream()
                    .map(binding -> binding.alias().value())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            boolean workspaceAttachmentDisclosed = catalog.snapshot().bindings().stream()
                    .anyMatch(binding -> binding.definition().name().value().equals("workspace_attach"));
            Map<String, ResolvedModelSnapshot> modelSnapshots = configuration.availableModels().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            CliConfiguration.Model::id, m -> modelSnapshot(m, authStore)));
            List<RuntimeTraceEvent> traces = new CopyOnWriteArrayList<>();
            var outcomeProjection = new CodingRunOutcomeProjectionService(
                    persistence.ports().events(), persistence.ports().runs());
            var runtimeBuilder = persistence
                    .configure(new RuntimeCoreBuilder())
                    .identifierGenerator(identifiers)
                    .timeProvider(time)
                    .trace(event -> {
                        traces.add(event);
                        traceObserver.accept(event);
                    })
                    .failureDiagnostics(CliFailureDiagnosticSink.forPersistence(configuration.persistence()))
                    .completionRepair(new CompletionRepairPolicy(2));
            modelAdapters.forEach((key, adapter) ->
                    runtimeBuilder.registerChatModel(key.adapterType(), key.adapterVersion(), adapter));
            var runtime = runtimeBuilder
                    .credentialBroker(webPlatform.credentialBroker())
                    .toolRequestCanonicalizer(executionCanonicalizer)
                    .toolPlatform(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator())
                    .skillPlatform(skillPlatform.catalog(), skillPlatform.contentLoader())
                    .toolApprovalPrompts((binding, call, reauthentication) -> {
                        String toolName = binding.definition().name().value();
                        if (toolName.equals("workspace_attach")) {
                            return ApprovalPrompt.of(workspaceAttachmentApprovalPrompt(
                                    call.arguments().values()));
                        }
                        if (!toolName.equals("execution_run")) {
                            return io.haifa.agent.runtime.core.interaction.ToolApprovalPromptFormatter
                                    .defaultFormatter()
                                    .format(binding, call, reauthentication);
                        }
                        Map<String, Object> arguments = call.arguments().values();
                        String command = String.valueOf(arguments.get("command"));
                        String workspaceRef = String.valueOf(arguments.get("workspaceRef"));
                        String relativeWorkdir = String.valueOf(arguments.get("relativeWorkdir"));
                        String timeout = arguments.containsKey("timeoutMillis")
                                ? arguments.get("timeoutMillis") + " ms"
                                : "product maximum "
                                        + configuration
                                                .execution()
                                                .maximumTimeout()
                                                .toMillis()
                                        + " ms (capped by the Run deadline)";
                        String description = safeApprovalText(
                                String.valueOf(arguments.getOrDefault("description", "Run shell command")));
                        String shell = executionPlatform == null ? "unavailable" : executionPlatform.shellDisplayName();
                        String displayShell =
                                executionPlatform == null ? "Shell" : executionPlatform.shellDisplayName();
                        String security = executionPlatform == null
                                ? "execution unavailable"
                                : executionPlatform.securitySummary();
                        String prompt = description + "\nCommand: " + safeApprovalText(command) + "\nWorkspace: "
                                + safeApprovalText(workspaceRef) + "\nRelative workdir: "
                                + safeApprovalText(relativeWorkdir) + "\nTimeout: " + timeout + "\nShell: " + shell
                                + "\nSecurity: " + security;
                        String presentationContent = boundedApprovalContent(safeApprovalText(command));
                        return new ApprovalPrompt(
                                prompt,
                                presentationContent.isBlank()
                                        ? Optional.empty()
                                        : Optional.of(new ApprovalPresentation(
                                                boundedApprovalValue(
                                                        "执行 " + displayShell + " 命令", MAX_APPROVAL_TITLE_LENGTH),
                                                description.isBlank()
                                                        ? "Agent 请求执行命令以继续任务。"
                                                        : boundedApprovalValue(description, MAX_PURPOSE_LENGTH),
                                                boundedApprovalValue(displayShell, MAX_CONTENT_TYPE_LENGTH),
                                                presentationContent,
                                                List.of(
                                                        new ApprovalPresentation.Fact("执行位置", "本机环境"),
                                                        new ApprovalPresentation.Fact("工作目录", "当前项目"),
                                                        new ApprovalPresentation.Fact("网络访问", "未请求"),
                                                        new ApprovalPresentation.Fact("说明", "本次批准仅适用于这一次执行")),
                                                List.of(
                                                        new ApprovalPresentation.Fact(
                                                                "工作区",
                                                                nonBlankApprovalFact(safeApprovalText(workspaceRef))),
                                                        new ApprovalPresentation.Fact(
                                                                "相对工作目录",
                                                                nonBlankApprovalFact(
                                                                        safeApprovalText(relativeWorkdir))),
                                                        new ApprovalPresentation.Fact(
                                                                "超时", nonBlankApprovalFact(timeout)),
                                                        new ApprovalPresentation.Fact(
                                                                "Shell", nonBlankApprovalFact(shell)),
                                                        new ApprovalPresentation.Fact(
                                                                "安全", nonBlankApprovalFact(security))),
                                                Optional.empty())));
                    })
                    .approvalVerification(policy.approvalVerification())
                    .publicToolPolicy(publicToolPolicy)
                    .definitions((id, requested) -> new ResolvedDefinition(
                            id,
                            requested.orElse(new AgentDefinitionVersion(1, 0, 0)),
                            disclosedToolAliases,
                            configuration.skills().allowedAliases(),
                            Set.of(),
                            CodingAgentPrompt.forWorkspaceAttachment(workspaceAttachmentDisclosed)
                                            .text()
                                    + executionEnvironmentPrompt(
                                            executionPlatform == null ? "" : executionPlatform.shellDisplayName())
                                    + workspaceEnvironment
                                            .snapshot(resources.snapshot())
                                            .promptBlock()
                                    + resources.snapshot().instructionBlock()
                                    + CodingWorkspacePathsPrompt.render(workspacePathEntries(provisioning)),
                            List.of()))
                    .profiles((profileId, overrides) -> new ResolvedProfile(
                            profileId,
                            "1.0.0",
                            AgentRunType.CHAT,
                            AgentRunBudget.disabled(),
                            new AgentRunLimits(
                                    configuration.maxIterations(),
                                    4,
                                    1,
                                    configuration.timeout().toMillis(),
                                    configuration.timeout().toMillis(),
                                    configuration.maxModelCalls(),
                                    configuration.maxToolCalls(),
                                    8),
                            resolveCurrentSnapshot(profileId, configuration, authStore)
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "MODEL_SELECTION_REQUIRED: configured model is unavailable")),
                            Map.of(),
                            Map.of(
                                    RuntimeControlOptions.MAX_REASONING_BYTES,
                                    512 * 1024,
                                    RuntimeControlOptions.MAX_REASONING_DURATION_MILLIS,
                                    Math.min(
                                            Duration.ofMinutes(5).toMillis(),
                                            configuration.timeout().toMillis()))))
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
                    new CliCodingModelCatalog(configuration, connectionState));
            var sessionHistory = new CodingSessionHistoryService(
                    codingSessions,
                    persistence.ports().state(),
                    runtime,
                    webPlatform.credentialBroker().redactor());
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
                            configuration.execution().maximumTimeout(),
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
                    sessionHistory,
                    resources,
                    provisioning,
                    Optional.ofNullable(shell),
                    Optional.ofNullable(executionPlatform),
                    new CliCodingSessionExportService(
                            workspaceRoot,
                            codingSessions,
                            persistence.ports().state(),
                            webPlatform.credentialBroker().redactor()),
                    outcomeProjection,
                    authentication);
            runtime.addListener(snapshot -> agent.startedRuns.add(snapshot.runId()));
            return agent;
        } catch (RuntimeException | Error exception) {
            try {
                if (authentication instanceof AutoCloseable closeable) closeable.close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            } catch (Exception closeFailure) {
                exception.addSuppressed(new IllegalStateException("authentication close failed", closeFailure));
            }
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
        return "\n\nRuntime execution guidance:\n"
                + "- execution_run uses "
                + shellDisplayName.strip()
                + " command syntax on this host.\n"
                + "- Generate commands for that configured shell; do not assume a POSIX shell or mix shell dialects.\n"
                + "- execution_run can invoke any non-interactive CLI available through the inherited PATH. Discover "
                + "command availability with the configured shell when uncertain, and adapt when a command is missing.\n"
                + "- Use OS CLI commands for scalable repository discovery and inspection. Prefer rg --files for file "
                + "discovery and rg for text search because they are fast; if rg is unavailable, use an appropriate "
                + "alternative for the configured shell. Choose the exact command and options for the task rather than "
                + "expecting a dedicated search wrapper.\n"
                + "- execution_run returns a completed result for every normal process exit. "
                + "Inspect the exit code and bounded output to decide what it means for the current command. "
                + "Do not treat a non-zero exit as a platform failure or repeat the same command without a new diagnostic hypothesis. "
                + "For literal rg searches, prefer rg -F -- <text>; use regex only when intended.\n"
                + "- Keep command output bounded and relevant. Narrow an overly broad query before repeating it.\n"
                + "- System git, gh, wrappers, and customer scripts run through the same generic execution path as "
                + "any other command. Read the real exit code and bounded output to decide what a result means.\n"
                + "- Commands that read, echo, override, or redirect host authentication material are rejected "
                + "before dispatch. Verify an unknown outcome from authoritative state before issuing any new "
                + "command; never blindly replay a dispatched call.";
    }

    AgentRunSnapshot start(String message) {
        if (closed.get()) throw new IllegalStateException("coding agent is closed");
        AgentSessionId sessionId = new AgentSessionId(identifiers.nextValue());
        persistence.provisionUserSession(sessionId, tenant, principal, Map.of(), clock);
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

    CodingRunOutcomeProjectionService outcomes() {
        return outcomes;
    }

    CodingAuthenticationClient authentication() {
        return authentication;
    }

    CodingSessionHistoryService sessionHistory() {
        return sessionHistory;
    }

    List<String> loadedResources() {
        return resources.snapshot().diagnostics();
    }

    List<String> reloadResources() {
        return resources.reload().diagnostics();
    }

    List<CodingWorkspaceView> workspaceViews() {
        return workspaceViews(workspaceProvisioning, tenant, principal);
    }

    private static List<CodingWorkspaceView> workspaceViews(
            AuthorizedWorkspaceProvisioning provisioning, TenantRef tenant, PrincipalRef principal) {
        String initialRef = provisioning.initialWorkspaceId().value();
        return provisioning.directoryViews().stream()
                .filter(view -> view.status() == AuthorizedDirectoryStatus.ACTIVE)
                .map(view -> new CodingWorkspaceView(
                        view.workspaceRef(),
                        view.safeDisplayName(),
                        view.mode().name(),
                        enumLabel(view.status()),
                        !view.workspaceRef().equals(initialRef)))
                .toList();
    }

    static List<CodingWorkspacePathsPrompt.Entry> workspacePathEntries(AuthorizedWorkspaceProvisioning provisioning) {
        HostWorkspaceScope scope = provisioning.scope();
        WorkspaceId initialWorkspaceId = provisioning.initialWorkspaceId();
        Map<String, AuthorizedDirectoryView> registryByRef = provisioning.directoryViews().stream()
                .filter(view -> view.status() == AuthorizedDirectoryStatus.ACTIVE)
                .collect(java.util.stream.Collectors.toMap(
                        AuthorizedDirectoryView::workspaceRef, Function.identity(), (first, second) -> first));

        List<CodingWorkspacePathsPrompt.Entry> entries = new ArrayList<>();
        for (AuthorizedHostDirectory directory : scope.allowedDirectories()) {
            WorkspaceId workspaceId = directory.workspaceId();
            AuthorizedDirectoryView directoryView = registryByRef.get(workspaceId.value());
            if (directoryView == null) {
                continue;
            }
            Path path = directory.realPath();
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || HostWorkspacePathSafety.isUnsafeNode(path)) {
                continue;
            }
            Path realPath;
            try {
                realPath = path.toRealPath();
            } catch (IOException exception) {
                continue;
            }
            if (!realPath.equals(path) || HostWorkspacePathSafety.isUnsafeNode(realPath)) {
                continue;
            }
            boolean current = workspaceId.equals(initialWorkspaceId);
            entries.add(new CodingWorkspacePathsPrompt.Entry(
                    workspaceId.value(),
                    realPath.normalize().toAbsolutePath().toString(),
                    directoryView.mode(),
                    current));
        }
        return entries;
    }

    void revokeWorkspace(String workspaceRef) {
        String normalized = requireWorkspaceRef(workspaceRef);
        CodingWorkspaceView workspace = workspaceViews().stream()
                .filter(candidate -> candidate.workspaceRef().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("WORKSPACE_NOT_FOUND"));
        if (!workspace.revocable()) {
            throw new IllegalStateException("WORKSPACE_NOT_REVOCABLE");
        }
        workspaceProvisioning.revoke(new WorkspaceId(normalized));
    }

    Optional<CodingShellService> shell() {
        return shell;
    }

    ToolOutputPreviewPublisher previewPublisher() {
        return executionPlatform
                .map(CliExecutionPlatform::previewPublisher)
                .orElseGet(ToolOutputPreviewPublisher::noop);
    }

    CodingSessionExportService exporter() {
        return exporter;
    }

    void cancel(io.haifa.agent.core.run.AgentRunId runId) {
        cancel(runId, io.haifa.agent.runtime.api.RunCancellation.userRequest());
    }

    void cancel(io.haifa.agent.core.run.AgentRunId runId, io.haifa.agent.runtime.api.RunCancellation cancellation) {
        runtime.command(new RuntimeCommand(
                new RuntimeCommandId(identifiers.nextValue()),
                runId,
                RuntimeCommandType.CANCEL,
                cancellation.arguments(),
                "cli-cancel-" + cancellation.type().name().toLowerCase(java.util.Locale.ROOT) + "-" + runId.value(),
                time.now()));
    }

    void timeout(io.haifa.agent.core.run.AgentRunId runId) {
        runtime.command(new RuntimeCommand(
                new RuntimeCommandId(identifiers.nextValue()),
                runId,
                RuntimeCommandType.TIMEOUT,
                RuntimeCommandArguments.NONE,
                "cli-timeout-" + runId.value(),
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

    private static String enumLabel(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String requireWorkspaceRef(String workspaceRef) {
        String normalized = Objects.requireNonNull(workspaceRef, "workspaceRef must not be null")
                .trim();
        if (normalized.isEmpty()
                || normalized.length() > 256
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("WORKSPACE_REF_INVALID");
        }
        return normalized;
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
        if (configuration.approval() == ApprovalMode.DENY) {
            configuredTools.remove("execution_run");
        }
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

    private static void validateAllowedSkills(
            CliConfiguration.Skills configuredSkills, ProjectSkillPlatform skillPlatform) {
        List<String> unavailable = configuredSkills.allowedAliases().stream()
                .filter(alias -> skillPlatform
                        .catalog()
                        .findByAlias(new SkillAlias(alias))
                        .isEmpty())
                .sorted()
                .toList();
        if (unavailable.isEmpty()) return;
        String diagnosticCodes = skillPlatform.catalog().snapshot().diagnostics().stream()
                .map(io.haifa.agent.skill.api.SkillDiagnostic::code)
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String diagnostics = diagnosticCodes.isEmpty() ? "" : "; diagnostics=" + diagnosticCodes;
        throw new IllegalArgumentException(
                "configured allowed Skills are unavailable: " + String.join(",", unavailable) + diagnostics);
    }

    private static String boundedApprovalContent(String value) {
        return boundedApprovalValue(value, MAX_APPROVAL_CONTENT_LENGTH);
    }

    private static String nonBlankApprovalFact(String value) {
        String bounded = boundedApprovalValue(value, MAX_FACT_VALUE_LENGTH);
        return bounded.isBlank() ? "—" : bounded;
    }

    private static String boundedApprovalValue(String value, int maximumLength) {
        if (value.length() <= maximumLength) return value;
        String marker = "...[truncated]";
        int end = Math.max(0, maximumLength - marker.length());
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + marker;
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

    static String workspaceAttachmentApprovalPrompt(Map<String, Object> arguments) {
        return "Attach additional workspace directory\nPath: "
                + attachmentApprovalArgument(arguments, "path")
                + "\nMode: "
                + attachmentApprovalArgument(arguments, "mode")
                + "\nScope: this local Coding Agent's authorized directories; each root is persisted locally and remains revocable.";
    }

    private static String attachmentApprovalArgument(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) return "<missing>";
        if (text.length() > 4096) return "<too long>";
        String safe = safeApprovalText(text);
        return safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0 || safe.indexOf('\t') >= 0 ? "<invalid>" : safe;
    }

    private static boolean openBrowser(URI uri) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false;
        try {
            Desktop.getDesktop().browse(uri);
            return true;
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = awaitTerminalAttempts();
        try {
            if (authentication instanceof AutoCloseable closeable) closeable.close();
        } catch (RuntimeException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        } catch (Exception exception) {
            RuntimeException closeFailure = new IllegalStateException("authentication close failed", exception);
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
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

    private static ModelContinuationProtector resolveContinuationProtector(
            CliConfiguration configuration, Function<String, String> environment) {
        if (configuration.persistence().mode() == ProjectPersistenceMode.MEMORY
                || configuration.persistence().protection() == ProjectPersistenceProtection.NONE) {
            return null;
        }
        String reference = configuration
                .persistence()
                .protectorReference()
                .orElseThrow(() -> new IllegalArgumentException("durable continuation protector is not configured"));
        String environmentName = reference.substring("env://".length());
        String encoded = environment.apply(environmentName);
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

    static String reasoningSummary(CliConfiguration configuration) {
        Map<String, Object> options = modelSnapshot(configuration).invocationOptions();
        String mode = String.valueOf(options.getOrDefault("thinking", "disabled"));
        Object effort = options.get("reasoning_effort");
        return effort == null ? "Reasoning: mode=" + mode : "Reasoning: mode=" + mode + ", effort=" + effort;
    }

    private static CodingModelState.Connection connectionState(
            LocalModelAuthenticationService authenticationService, CliConfiguration.Model model) {
        CredentialRef credentialRef = new CredentialRef(model.credentialRef());
        if (authenticationService.connectionRequired(credentialRef)) {
            return CodingModelState.Connection.LOGIN_REQUIRED;
        }
        return authenticationService.connections().stream()
                .filter(connection -> connection.connectionId().value().equals(credentialRef.value()))
                .map(connection -> switch (connection.status()) {
                    case REAUTH_REQUIRED -> CodingModelState.Connection.REAUTH_REQUIRED;
                    case AUTHENTICATED, RATE_LIMITED -> CodingModelState.Connection.CONNECTED;
                })
                .findFirst()
                .orElse(CodingModelState.Connection.CONNECTED);
    }

    static ResolvedModelSnapshot modelSnapshot(CliConfiguration.Model model) {
        return modelSnapshot(model, null);
    }

    static ResolvedModelSnapshot modelSnapshot(CliConfiguration.Model model, LocalModelAuthStore authStore) {
        if (OpenAiCompatibleDialects.ALIYUN_BAILIAN.equals(model.dialect())) {
            return resolveCatalogParameters(model, bailianModelSnapshot(model, authStore));
        }
        Map<String, Object> providerOptions = new java.util.LinkedHashMap<>();
        if (ModelApiStyles.OPENAI_CHAT_COMPLETIONS.equals(model.style())) {
            providerOptions.putAll(OpenAiCompatibleDialects.configuredOptions(model.dialect(), model.endpoint()));
        }
        Map<String, Object> invocationOptions = new java.util.LinkedHashMap<>();
        if (model.capabilities().contains(io.haifa.agent.model.api.ModelCapability.REASONING)) {
            invocationOptions.put("thinking", model.reasoningMode().name().toLowerCase(Locale.ROOT));
            if (model.reasoningEffort() != null) {
                invocationOptions.put(
                        "reasoning_effort", model.reasoningEffort().name().toLowerCase(Locale.ROOT));
            } else if (OpenAiResponsesDialects.ALIYUN_BAILIAN.equals(model.dialect())
                    && model.reasoningMode() != ModelReasoningMode.DISABLED) {
                invocationOptions.put("reasoning_effort", "high");
            }
        }
        if (OpenAiResponsesDialects.OPENAI_CODEX.equals(model.dialect())) {
            providerOptions.put("codex_originator", model.originator());
            providerOptions.put("codex_user_agent", model.userAgent());
        }
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId(model.providerId()),
                "cli-v1",
                new ModelDefinitionId(model.id()),
                "cli-v1",
                model.modelId(),
                ModelApiStyles.adapterType(model.style()),
                "1.0.0",
                model.style(),
                model.dialect(),
                model.endpoint(),
                new CredentialRef(model.credentialRef()),
                model.nativeStreaming(),
                model.capabilities(),
                model.contextWindow(),
                model.maxOutputTokens(),
                Map.copyOf(providerOptions),
                Map.copyOf(invocationOptions));
        return resolveCatalogParameters(model, snapshot);
    }

    private static ResolvedModelSnapshot resolveCatalogParameters(
            CliConfiguration.Model model, ResolvedModelSnapshot snapshot) {
        Optional<ModelBindingProfile> configuredProfile = PackagedModelCatalog.load(
                        LocalCodingAgent.class.getClassLoader())
                .profileFor(snapshot);
        if (configuredProfile.isEmpty()) return snapshot;
        ModelBindingProfile profile = configuredProfile.orElseThrow();
        ModelReasoningMode mode =
                model.reasoningModeConfigured() ? model.reasoningMode() : defaultReasoningMode(profile);
        if (!model.reasoningModeConfigured()
                && model.reasoningEffort() != null
                && mode == ModelReasoningMode.DISABLED) {
            throw new io.haifa.agent.model.api.ModelParameterResolutionException(
                    io.haifa.agent.model.api.ModelParameterResolutionFailure.REASONING_MODE_UNSUPPORTED,
                    "model.reasoningMode must be explicit when reasoningEffort is configured and reasoning defaults to disabled");
        }
        Optional<ModelReasoningEffort> effort = mode == ModelReasoningMode.DISABLED
                ? Optional.empty()
                : Optional.ofNullable(model.reasoningEffort()).or(() -> recommendedEffort(profile));
        ModelReasoningPolicy reasoning = new ModelReasoningPolicy(mode, effort, java.util.OptionalLong.empty());
        var parameters = new DefaultModelParameterResolver()
                .resolve(
                        profile,
                        new ModelParameterResolutionRequest(
                                profile.bindingId(),
                                profile.version(),
                                profile.digest(),
                                reasoning,
                                model.maxOutputTokens()));
        return snapshot.withEffectiveParameters(parameters);
    }

    private static ModelReasoningMode defaultReasoningMode(ModelBindingProfile profile) {
        return switch (profile.reasoningBehavior()) {
            case NONE, OPTIONAL -> ModelReasoningMode.DISABLED;
            case ALWAYS -> ModelReasoningMode.ENABLED;
            case ADAPTIVE ->
                profile.allowedReasoningModes().contains(ModelReasoningMode.ADAPTIVE)
                        ? ModelReasoningMode.ADAPTIVE
                        : ModelReasoningMode.ENABLED;
        };
    }

    private static Optional<ModelReasoningEffort> recommendedEffort(ModelBindingProfile profile) {
        if (profile.allowedReasoningEfforts().isEmpty()) return Optional.empty();
        if (profile.allowedReasoningEfforts().contains(ModelReasoningEffort.MEDIUM)) {
            return Optional.of(ModelReasoningEffort.MEDIUM);
        }
        return profile.allowedReasoningEfforts().stream().sorted().findFirst();
    }

    private static PublicToolPolicy workspaceAccessPolicy(
            PublicToolPolicy delegate,
            AuthorizedWorkspaceProvisioning provisioning,
            TenantRef tenant,
            PrincipalRef principal) {
        return (run, binding, request) -> {
            String toolName = binding.definition().name().value();
            if (toolName.equals("execution_run")) {
                Object rawWorkspace = request.arguments().values().get("workspaceRef");
                if (!(rawWorkspace instanceof String workspaceRef) || workspaceRef.isBlank()) {
                    throw new SecurityException("WORKSPACE_ACCESS_TARGET_INVALID");
                }
                provisioning.requireAuthorized(
                        tenant, principal, new WorkspaceId(workspaceRef), WorkspaceAccessMode.DEVELOP);
            }
            return delegate.evaluate(run, binding, request);
        };
    }

    private static ResolvedModelSnapshot bailianModelSnapshot(
            CliConfiguration.Model model, LocalModelAuthStore authStore) {
        String workspaceId = model.workspaceId();
        String region = model.region();
        if (authStore != null && model.credentialRef().startsWith("model-auth://")) {
            try {
                var reference = LocalModelAuthReference.parse(model.credentialRef());
                var credential = authStore.find(reference);
                if (credential.isPresent() && credential.get() instanceof StoredApiKeyCredential apiKeyCred) {
                    workspaceId = apiKeyCred
                            .workspaceId()
                            .filter(java.util.function.Predicate.not(String::isBlank))
                            .orElse(workspaceId);
                    region = apiKeyCred
                            .region()
                            .filter(java.util.function.Predicate.not(String::isBlank))
                            .orElse(region);
                }
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> invocationOptions = new java.util.LinkedHashMap<>(
                OpenAiCompatibleDialects.configuredInvocationOptions(model.dialect(), model.reasoningMode()));
        if (model.reasoningEffort() != null) {
            invocationOptions.put(
                    "reasoning_effort", model.reasoningEffort().name().toLowerCase(Locale.ROOT));
        }
        var provider = AliyunBailianProviderFactory.provider(
                new AliyunBailianProviderFactory.ProviderConfiguration(
                        "cli-v1", workspaceId, region, new CredentialRef(model.credentialRef())),
                List.of(new AliyunBailianProviderFactory.ModelProfile(
                        new ModelDefinitionId(model.id()),
                        "cli-v1",
                        model.modelId(),
                        model.displayName(),
                        model.capabilities(),
                        model.contextWindow(),
                        model.maxOutputTokens(),
                        Map.copyOf(invocationOptions))));
        var definition = provider.models().getFirst();
        Map<String, Object> providerOptions = new java.util.LinkedHashMap<>(provider.options());
        return ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                definition.id(),
                definition.version(),
                definition.providerModelId(),
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1.0.0",
                model.style(),
                model.dialect(),
                provider.endpoint(),
                provider.credentialRef(),
                provider.nativeStreaming(),
                definition.capabilities(),
                definition.contextWindow(),
                definition.maxOutputTokens(),
                Map.copyOf(providerOptions),
                definition.options());
    }

    private static Optional<ResolvedModelSnapshot> resolveCurrentSnapshot(
            String profileId, CliConfiguration configuration, LocalModelAuthStore authStore) {
        if ("cli-coding".equals(profileId)) {
            return Optional.of(modelSnapshot(configuration.model(), authStore));
        }
        return configuration.availableModels().stream()
                .filter(m -> m.id().equals(profileId))
                .findFirst()
                .map(m -> modelSnapshot(m, authStore));
    }

    private static Set<String> deniedEnvironmentNames(CliConfiguration configuration) {
        Set<String> denied = new java.util.LinkedHashSet<>();
        if (configuration == null) return denied;
        if (configuration.model() != null)
            collectEnvRef(denied, configuration.model().credentialRef());
        if (configuration.availableModels() != null) {
            for (var model : configuration.availableModels()) {
                collectEnvRef(denied, model.credentialRef());
            }
        }
        if (configuration.web() != null) {
            if (configuration.web().search() != null)
                collectEnvRef(denied, configuration.web().search().credentialRef());
            if (configuration.web().fetch() != null)
                collectEnvRef(denied, configuration.web().fetch().credentialRef());
        }
        return Set.copyOf(denied);
    }

    private static void collectEnvRef(Set<String> target, String ref) {
        if (ref != null && ref.startsWith("env://") && ref.length() > "env://".length()) {
            target.add(ref.substring("env://".length()));
        }
    }
}
