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
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.delivery.CodingCompletionPolicy;
import io.haifa.agent.application.project.product.coding.delivery.CodingDeliveryEvidenceLedger;
import io.haifa.agent.application.project.product.coding.delivery.CodingDeliveryIntentResolver;
import io.haifa.agent.application.project.product.coding.delivery.CodingDeliveryProfile;
import io.haifa.agent.application.project.product.coding.delivery.CodingRunOutcomeProjectionMiddleware;
import io.haifa.agent.application.project.product.coding.delivery.CodingRunOutcomeProjectionService;
import io.haifa.agent.application.project.product.coding.delivery.CodingTaskModeResolver;
import io.haifa.agent.application.project.product.coding.delivery.CodingWorkProjectionMiddleware;
import io.haifa.agent.application.project.product.coding.delivery.CodingWorkProjectionService;
import io.haifa.agent.application.project.product.coding.delivery.OnDemandChangeReviewService;
import io.haifa.agent.application.project.product.coding.prompt.CodingAgentPrompt;
import io.haifa.agent.application.project.product.coding.verification.CodingSessionVerificationConfiguration;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfileMiddleware;
import io.haifa.agent.application.project.product.coding.verification.PersistedCodingVerificationProfileProvider;
import io.haifa.agent.application.project.skill.ProjectSkillPlatform;
import io.haifa.agent.application.project.tool.CodingToolchainEnvironmentProfile;
import io.haifa.agent.application.project.tool.ProjectPermissionRequestOperations;
import io.haifa.agent.application.project.tool.ProjectToolCatalog;
import io.haifa.agent.application.project.tool.ProjectToolExecutor;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginCoordinator;
import io.haifa.agent.auth.localmodel.ExternalLoginMethod;
import io.haifa.agent.auth.localmodel.ExternalLoginRegistry;
import io.haifa.agent.auth.localmodel.FileLocalModelAuthStore;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import io.haifa.agent.auth.localmodel.LocalModelCredentialResolver;
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
import io.haifa.agent.model.anthropic.AnthropicMessagesDialects;
import io.haifa.agent.model.anthropic.AnthropicMessagesModel;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.gemini.GeminiGenerateContentModel;
import io.haifa.agent.model.openai.AliyunBailianProviderFactory;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import io.haifa.agent.model.openai.responses.OpenAiResponsesDialects;
import io.haifa.agent.model.openai.responses.OpenAiResponsesModel;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.configuration.InMemoryProjectConfigurationStore;
import io.haifa.agent.project.configuration.ProjectConfiguration;
import io.haifa.agent.project.configuration.ProjectConfigurationService;
import io.haifa.agent.project.configuration.ProjectConfigurationVersion;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.ledger.InMemorySessionChangeLedger;
import io.haifa.agent.project.mutation.InMemoryWorkspaceWriteLeaseManager;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.LocalWorkspaceMutationService;
import io.haifa.agent.project.provider.local.SensitivePathPolicy;
import io.haifa.agent.project.provider.local.root.LocalWorkspaceRoot;
import io.haifa.agent.project.provider.local.root.LocalWorkspaceRootRegistry;
import io.haifa.agent.project.provider.local.root.LocalWorkspaceRootStrategyDetector;
import io.haifa.agent.project.root.WorkspaceRootAlias;
import io.haifa.agent.project.root.WorkspaceRootPermission;
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
import io.haifa.agent.runtime.core.model.ModelAdapterKey;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.retry.RepairRetryPolicy;
import io.haifa.agent.runtime.core.skill.DefaultSkillActivationService;
import io.haifa.agent.runtime.core.skill.SkillToolCatalogContribution;
import io.haifa.agent.runtime.core.skill.SkillToolProvider;
import io.haifa.agent.runtime.core.tool.DefaultPublicToolPolicy;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.sandbox.host.HostGuardedSandboxProvider;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.awt.Desktop;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
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
    private final Optional<CodingShellService> shell;
    private final Optional<CliExecutionPlatform> executionPlatform;
    private final CodingSessionExportService exporter;
    private final CodingSessionVerificationConfiguration defaultVerification;
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
            Optional<CodingShellService> shell,
            Optional<CliExecutionPlatform> executionPlatform,
            CodingSessionExportService exporter,
            CodingSessionVerificationConfiguration defaultVerification,
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
        this.shell = shell;
        this.executionPlatform = executionPlatform;
        this.exporter = exporter;
        this.defaultVerification = Objects.requireNonNull(defaultVerification, "defaultVerification must not be null");
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
        return createWithTrace(workspaceRoot, configuration, output, traceObserver, System.getenv());
    }

    static LocalCodingAgent createWithTrace(
            Path workspaceRoot,
            CliConfiguration configuration,
            PrintStream output,
            Consumer<RuntimeTraceEvent> traceObserver,
            Map<String, String> environment) {
        Map<String, String> resolvedEnvironment =
                Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        boolean allowInsecureLoopback =
                allowInsecureLoopback(configuration, resolvedEnvironment.get("HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL"));
        var http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var json = new ObjectMapper();
        Clock authClock = Clock.systemUTC();
        var authStore = FileLocalModelAuthStore.defaultStore(json);
        var codexRegistration = CodexLocalCompatibilityRegistrationFactory.create(resolvedEnvironment);
        var antigravityRegistration = AntigravityLocalCompatibilityRegistrationFactory.create(resolvedEnvironment);
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
                resolvedEnvironment::get, authStore, authRegistry, authClock, Duration.ofMinutes(5));
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
                new LocalModelAuthenticationService(authStore, authCoordinator, credentials, resolvedEnvironment::get);
        var authentication = new CliCodingAuthenticationClient(
                authenticationService,
                configuration.model().credentialRef(),
                configuration.model().providerId(),
                configuration.availableModels().stream()
                        .map(CliConfiguration.Model::credentialRef)
                        .toList(),
                antigravityRegistration.isPresent());
        var chat = new OpenAiCompatibleChatModel(
                "openai-compatible", "1.0.0", http, json, credentials, allowInsecureLoopback, 4 * 1024 * 1024);
        var responses = new OpenAiResponsesModel(
                http, json, credentials, allowInsecureLoopback, 4 * 1024 * 1024, ref -> authenticationService
                        .findExternalAccountId(
                                ref, io.haifa.agent.auth.localmodel.codex.CodexExternalLoginMethod.METHOD_ID)
                        .map(io.haifa.agent.model.openai.responses.CodexAccountIdentity::new));
        var anthropic = new AnthropicMessagesModel(http, json, credentials, allowInsecureLoopback, 4 * 1024 * 1024);
        var gemini = new GeminiGenerateContentModel(
                http, json, credentials, allowInsecureLoopback, 4 * 1024 * 1024, false, antigravityProjects::resolve);
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
                resolveContinuationProtector(configuration, resolvedEnvironment),
                resolvedEnvironment,
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
                resolveContinuationProtector(configuration, System.getenv()));
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
                System.getenv(),
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
            Map<String, String> environment,
            CodingAuthenticationClient authentication,
            Function<CliConfiguration.Model, CodingModelState.Connection> connectionState) {
        Map<String, String> resolvedEnvironment =
                Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
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
        var executionResources = new java.util.ArrayList<CliExecutionPlatform>();
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
            CliWebPlatform webPlatform =
                    CliWebPlatform.create(configuration.web(), principal, resolvedEnvironment::get);
            var projects = new InMemoryProjectStore();
            var workspaces = new InMemoryWorkspaceStore();
            var bindings = new InMemoryWorkspaceBindingStore();
            var locations = new LocalWorkspaceLocationStore();
            WorkspaceId workspaceId = workspaceIdentity.workspaceId();
            var verificationDiscovery = CliVerificationProfileDiscovery.discoverWithSignals(
                    workspaceRoot, System.getProperty("os.name", ""));
            var verificationProfile = verificationDiscovery.profile();
            var verificationProfiles = new PersistedCodingVerificationProfileProvider(
                    persistence.ports().runs(), persistence.ports().sessions());
            WorkspaceBindingId bindingId = workspaceIdentity.bindingId();
            WorkspaceLocationRef locationRef = workspaceIdentity.locationRef();
            locations.register(locationRef, workspaceRoot);
            Set<String> configuredTools = effectiveBuiltInTools(configuration);
            var policy = CodingAgentPolicyAssembly.create(
                    policyMode(configuration.approval()),
                    configuration.approvalThreshold(),
                    clock,
                    identifiers::nextValue,
                    persistence.policy());
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
            var detector = new LocalWorkspaceRootStrategyDetector();
            var detection = detector.detect(workspaceRoot);
            LocalWorkspaceRoot mainRoot = LocalWorkspaceRoot.of(
                    WorkspaceRootAlias.MAIN,
                    workspaceRoot,
                    WorkspaceRootPermission.READ_WRITE,
                    detection.strategy(),
                    detection.initialDirty());
            LocalWorkspaceRootRegistry rootRegistry = LocalWorkspaceRootRegistry.singleMain(mainRoot);
            var sessionLedger = new InMemorySessionChangeLedger();
            var onDemandReview = new OnDemandChangeReviewService(rootRegistry, sessionLedger);
            var mutations = new LocalWorkspaceMutationService(
                    workspaces,
                    bindings,
                    locations,
                    sensitivePaths,
                    new InMemoryWorkspaceWriteLeaseManager(),
                    identifiers,
                    time);
            var operations =
                    new LocalFileToolOperations(workspaces, files, mutations, identifiers, rootRegistry, sessionLedger);
            var deliveryIntents = new CodingDeliveryIntentResolver(
                    persistence.codingSessions(), persistence.ports().runs());
            CliExecutionPlatform executionPlatform = executionEnabled
                    ? CliExecutionPlatform.create(
                            configuration.execution(),
                            workspaces,
                            bindings,
                            locations,
                            files,
                            identifiers,
                            time,
                            clock,
                            policy,
                            workspaceId,
                            workspaceRoot,
                            output,
                            resolvedEnvironment,
                            verificationProfiles)
                    : null;
            if (executionPlatform != null) executionResources.add(executionPlatform);
            TrustedWorkspaceEnvironmentCatalog workspaceEnvironment = new TrustedWorkspaceEnvironmentCatalog(
                    workspaceRoot,
                    verificationDiscovery,
                    resources.snapshot(),
                    TrustedWorkspaceEnvironmentCatalog.EnvironmentFacts.capture(
                            executionPlatform == null ? "unavailable" : executionPlatform.shellDisplayName(),
                            executionPlatform != null,
                            executionPlatform == null
                                    ? "UNAVAILABLE"
                                    : executionPlatform
                                            .profile()
                                            .networkPolicy()
                                            .name(),
                            configuration.execution().defaultTimeout(),
                            configuration.execution().maximumTimeout()));
            var permissionRequests = executionPlatform == null
                    ? null
                    : new ProjectPermissionRequestOperations(
                            persistence.ports().state(),
                            executionPlatform.permissionOperations(),
                            executionPlatform.profile(),
                            executionPlatform.permissionProfile());
            var provider = new ProjectToolExecutor(
                    (runId, ignoredPrincipal) -> new io.haifa.agent.application.project.tool.RunWorkspaceAccess(
                            workspaceId, effectiveCapabilities),
                    operations,
                    executionPlatform == null ? null : executionPlatform.operations(),
                    permissionRequests);
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
                            executionPlatform == null ? null : executionPlatform.permissionProfile(),
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
            var completionPolicy =
                    new CodingCompletionPolicy(taskModes, deliveryEvidence, deliveryProfile, deliveryIntents);
            var outcomeProjection = new CodingRunOutcomeProjectionService(
                    completionPolicy,
                    persistence.ports().events(),
                    persistence.ports().runs());
            var workProjection = new CodingWorkProjectionService(
                    persistence.ports().state(), taskModes, deliveryEvidence, deliveryProfile, time, deliveryIntents);
            var runtimeBuilder = persistence
                    .configure(new RuntimeCoreBuilder())
                    .identifierGenerator(identifiers)
                    .timeProvider(time)
                    .trace(event -> {
                        traces.add(event);
                        traceObserver.accept(event);
                    })
                    .failureDiagnostics(CliFailureDiagnosticSink.forPersistence(configuration.persistence()))
                    .completionPolicy(completionPolicy)
                    .middleware(CodingWorkProjectionMiddleware.events(
                            workProjection,
                            io.haifa.agent.runtime.core.middleware.RuntimePhase.BEFORE_RUN,
                            persistence.ports().events(),
                            time))
                    .middleware(CodingWorkProjectionMiddleware.events(
                            workProjection,
                            io.haifa.agent.runtime.core.middleware.RuntimePhase.AFTER_DECISION_EXECUTION,
                            persistence.ports().events(),
                            time))
                    .middleware(new CodingRunOutcomeProjectionMiddleware(
                            outcomeProjection, persistence.ports().events(), time))
                    .middleware(new CodingVerificationProfileMiddleware(verificationProfiles))
                    .repairRetry(new RepairRetryPolicy(2));
            modelAdapters.forEach((key, adapter) ->
                    runtimeBuilder.registerChatModel(key.adapterType(), key.adapterVersion(), adapter));
            var runtime = runtimeBuilder
                    .credentialBroker(webPlatform.credentialBroker())
                    .toolRequestCanonicalizer(
                            new io.haifa.agent.application.project.tool.CodingExecutionToolRequestCanonicalizer(
                                    CliExecutionPlatform.workspaceWorkdirNormalizer(workspaceRoot)))
                    .toolPlatform(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator())
                    .skillPlatform(skillPlatform.catalog(), skillPlatform.contentLoader())
                    .toolApprovalPrompts((binding, call, reauthentication) -> {
                        String toolName = binding.definition().name().value();
                        if (!toolName.equals("execution.run")
                                && !toolName.equals(ProjectPermissionRequestOperations.TOOL_NAME)) {
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
                        boolean permissionRequest = toolName.equals(ProjectPermissionRequestOperations.TOOL_NAME);
                        String description = safeApprovalText(String.valueOf(arguments.getOrDefault(
                                permissionRequest ? "justification" : "description",
                                permissionRequest ? "Request one-time host network access" : "Run shell command")));
                        String permissionDetails = permissionRequest
                                ? "\nPrior failed Tool Call: "
                                        + safeApprovalText(String.valueOf(arguments.get("priorToolCallId")))
                                        + "\nRequested permission: "
                                        + safeApprovalText(String.valueOf(arguments.get("requestedPermission")))
                                        + "\nScope: this exact command once; no reusable grant"
                                : "";
                        return description + "\nCommand: " + safeApprovalText(command) + "\nWorkdir: "
                                + safeApprovalText(workdir) + "\nTimeout: " + timeout + " ms\nShell: "
                                + (executionPlatform == null ? "unavailable" : executionPlatform.shellDisplayName())
                                + permissionDetails
                                + "\nSecurity: "
                                + (executionPlatform == null
                                        ? "execution unavailable"
                                        : permissionRequest
                                                ? "approved host execution, network=ALLOW, current OS user; workspace "
                                                        + "and hard command denials remain enforced"
                                                : executionPlatform.securitySummary());
                    })
                    .policyStores(policy.decisionsStore(), policy.evidence())
                    .approvalVerification(policy.approvalVerification())
                    .publicToolPolicy(new DefaultPublicToolPolicy(
                            new io.haifa.agent.application.project.policy.CodingExecutionPolicyRequestAdapter(
                                    policyMode(configuration.approval())),
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
                                    + workspaceEnvironment
                                            .snapshot(resources.snapshot())
                                            .promptBlock()
                                    + resources.snapshot().instructionBlock(),
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
                                    64,
                                    configuration.maxToolCalls(),
                                    8),
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
                    new CliCodingModelCatalog(configuration, connectionState),
                    verificationProfile);
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
                    sessionHistory,
                    resources,
                    Optional.ofNullable(shell),
                    Optional.ofNullable(executionPlatform),
                    new CliCodingSessionExportService(
                            workspaceRoot,
                            codingSessions,
                            persistence.ports().state(),
                            webPlatform.credentialBroker().redactor()),
                    CodingSessionVerificationConfiguration.freeze(verificationProfile),
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
            executionResources.forEach(resource -> {
                try {
                    resource.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            });
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
                + "- Keep command output bounded and relevant. Narrow an overly broad query before repeating it.\n"
                + "- request_permissions is not a general sandbox bypass. Use it only after execution_run returns an eligible "
                + "stable remote-access or host-authentication code for a direct system git or gh command, and repeat the exact command, "
                + "workdir, timeout, and prior Tool Call ID. operationFamily is only an optional diagnostic hint. Compound commands, wrappers, path "
                + "escape, credential override, destructive commands, and unknown outcomes cannot be elevated.";
    }

    AgentRunSnapshot start(String message) {
        if (closed.get()) throw new IllegalStateException("coding agent is closed");
        AgentSessionId sessionId = new AgentSessionId(identifiers.nextValue());
        persistence.provisionUserSession(sessionId, tenant, principal, defaultVerification.sessionMetadata(), clock);
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
        if (configuration.approval() == ApprovalMode.DENY) {
            configuredTools.remove("execution.run");
            configuredTools.remove(ProjectPermissionRequestOperations.TOOL_NAME);
        } else if (configuredTools.contains("execution.run")
                && !configuration.execution().provider().equals(HostGuardedSandboxProvider.PROVIDER_ID)) {
            configuredTools.add(ProjectPermissionRequestOperations.TOOL_NAME);
        } else {
            configuredTools.remove(ProjectPermissionRequestOperations.TOOL_NAME);
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
            executionPlatform.ifPresent(CliExecutionPlatform::close);
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
            CliConfiguration configuration, Map<String, String> environment) {
        if (configuration.persistence().mode() == ProjectPersistenceMode.MEMORY
                || configuration.persistence().protection() == ProjectPersistenceProtection.NONE) {
            return null;
        }
        String reference = configuration
                .persistence()
                .protectorReference()
                .orElseThrow(() -> new IllegalArgumentException("durable continuation protector is not configured"));
        String environmentName = reference.substring("env://".length());
        String encoded = environment.get(environmentName);
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
        if (OpenAiCompatibleDialects.ALIYUN_BAILIAN.equals(model.dialect())) {
            return bailianModelSnapshot(model);
        }
        Map<String, Object> providerOptions = new java.util.LinkedHashMap<>();
        if (ModelApiStyles.OPENAI_CHAT_COMPLETIONS.equals(model.style())) {
            providerOptions.putAll(OpenAiCompatibleDialects.configuredOptions(model.dialect(), model.endpoint()));
        }
        boolean deepSeek = OpenAiCompatibleDialects.DEEPSEEK.equals(model.dialect())
                || AnthropicMessagesDialects.DEEPSEEK.equals(model.dialect());
        if (deepSeek) providerOptions.put("thinking", "disabled");
        Map<String, Object> invocationOptions = new java.util.LinkedHashMap<>();
        if (deepSeek) invocationOptions.put("thinking", "disabled");
        if (OpenAiResponsesDialects.ALIYUN_BAILIAN.equals(model.dialect())
                && model.reasoningMode() == ModelReasoningMode.ENABLED) {
            invocationOptions.put("reasoning_effort", "high");
        }
        if (OpenAiResponsesDialects.OPENAI_CODEX.equals(model.dialect())) {
            providerOptions.put("codex_originator", model.originator());
            providerOptions.put("codex_user_agent", model.userAgent());
        }
        return ResolvedModelSnapshot.create(
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
    }

    private static ResolvedModelSnapshot bailianModelSnapshot(CliConfiguration.Model model) {
        var provider = AliyunBailianProviderFactory.provider(
                new AliyunBailianProviderFactory.ProviderConfiguration(
                        "cli-v1", model.workspaceId(), model.region(), new CredentialRef(model.credentialRef())),
                List.of(new AliyunBailianProviderFactory.ModelProfile(
                        new ModelDefinitionId(model.id()),
                        "cli-v1",
                        model.modelId(),
                        model.displayName(),
                        model.capabilities(),
                        model.contextWindow(),
                        model.maxOutputTokens(),
                        OpenAiCompatibleDialects.configuredInvocationOptions(model.dialect(), model.reasoningMode()))));
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
}
