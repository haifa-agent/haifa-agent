package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.haifa.agent.application.project.product.coding.client.CodingAgentClient;
import io.haifa.agent.application.project.product.coding.client.CodingAgentClientFactory;
import io.haifa.agent.application.project.product.coding.client.CodingAgentClientMetadata;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.testing.e2e.StandardCodingAgentClientExtension;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Real-provider Coding E2E owned by the central E2E test module. */
@Tag("live")
@Tag("functional")
@Tag("coding-product")
@Tag("p0")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(StandardCodingAgentClientExtension.class)
class CodingAgentLiveE2E {
    private static final String LIVE_SWITCH = "HAIFA_CODING_CLIENT_LIVE_TEST";
    private static final String ROOT_SENTINEL = ".haifa-cli-live-e2e-root";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, CaseSpec> CASES = loadCases();
    private static Path approvedRoot;
    private static Path agentConfiguration;
    private static String runId;

    @BeforeAll
    static void requireExplicitLiveEnvironment() throws Exception {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv(LIVE_SWITCH)),
                "real-model CLI E2E requires " + LIVE_SWITCH + "=true");
        requireEnvironment("HAIFA_FT_ENABLED", "true");
        requireEnvironment("HAIFA_FT_MODE", "LIVE");
        agentConfiguration = Path.of(requiredEnvironment("HAIFA_TEST_AGENT_CONFIG"))
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(agentConfiguration)) {
            throw new IllegalStateException("HAIFA_TEST_AGENT_CONFIG must identify a standard Coding Agent YAML");
        }
        runId = requiredEnvironment("HAIFA_FT_RUN_ID");
        approvedRoot =
                Path.of(requiredEnvironment("HAIFA_FT_ROOT")).toAbsolutePath().normalize();
        validateApprovedRoot(approvedRoot, runId);
        assertThat(CASES).hasSize(9);
    }

    @Test
    @Order(1)
    void repairsSingleFileBoundaryDefect(CodingAgentClientFactory clients) throws Exception {
        runCase("HF-06-E2E-CLI-001", clients);
    }

    @Test
    @Order(2)
    void implementsMultiFileDiscountFeature(CodingAgentClientFactory clients) throws Exception {
        runCase("HF-06-E2E-CLI-002", clients);
    }

    @Test
    @Order(3)
    void addsRegressionTestAndRepairsValidation(CodingAgentClientFactory clients) throws Exception {
        runCase("HF-06-E2E-CLI-003", clients);
    }

    @Test
    @Order(4)
    void repairsMavenSourceDirectoryConfiguration(CodingAgentClientFactory clients) throws Exception {
        runCase("HF-06-E2E-CLI-004", clients);
    }

    @Test
    @Order(5)
    void performsBehaviorPreservingRefactor(CodingAgentClientFactory clients) throws Exception {
        runCase("HF-06-E2E-CLI-005", clients);
    }

    @Test
    @Order(6)
    void migratesTypeAcrossFileLifecycle(CodingAgentClientFactory clients) throws Exception {
        runCase("HF-06-E2E-CLI-006", clients);
    }

    @Test
    @Order(7)
    void preservesUnrelatedDirtyWorkspaceContent(CodingAgentClientFactory clients) throws Exception {
        runCase("HF-06-E2E-CLI-007", clients);
    }

    @Test
    @Order(8)
    void diagnosesFailedExecutionAndRecovers(CodingAgentClientFactory clients) throws Exception {
        runCase("HF-06-E2E-CLI-008", clients);
    }

    @Test
    @Order(9)
    void rejectedApprovalProducesNoSideEffect(CodingAgentClientFactory clients) throws Exception {
        runCase("HF-06-E2E-CLI-009", clients);
    }

    private static void runCase(String caseId, CodingAgentClientFactory clients) throws Exception {
        CaseSpec specification = Objects.requireNonNull(CASES.get(caseId), "unknown live case " + caseId);
        Path caseRoot = Files.createDirectory(approvedRoot.resolve(caseId + "-" + java.util.UUID.randomUUID()));
        Path workspace = Files.createDirectory(caseRoot.resolve("workspace"));
        copyFixture(specification.fixture(), workspace);
        initializeGitBaseline(workspace);
        Map<String, String> before = fileDigests(workspace);
        String fixtureDigest = aggregateDigest(before);
        String sentinelBefore = sha256(approvedRoot.resolve(ROOT_SENTINEL));
        Map<String, String> protectedBefore = specification.protectedPaths().stream()
                .collect(Collectors.toMap(path -> path, path -> digest(workspace.resolve(path))));
        Instant startedAt = now();
        ClientOutcome outcome;
        CodingAgentClientMetadata metadata;
        try (CodingAgentClient agent = clients.open(workspace, agentConfiguration, System.getenv())) {
            metadata = agent.metadata();
            outcome = executeCase(specification, agent.client(), agent.projectId(), startedAt);
        }

        assertThat(sha256(approvedRoot.resolve(ROOT_SENTINEL))).isEqualTo(sentinelBefore);
        specification.protectedPaths().forEach(path -> assertThat(digest(workspace.resolve(path)))
                .as("protected workspace path %s", path)
                .isEqualTo(protectedBefore.get(path)));
        Map<String, String> after = fileDigests(workspace);
        List<String> changedPaths = changedPaths(before, after);
        writeEvidence(
                specification,
                outcome.completed(),
                outcome.events(),
                metadata,
                fixtureDigest,
                Duration.between(startedAt, now()),
                outcome.rejectedApprovals(),
                changedPaths,
                "PENDING");
        if (outcome.completed().status() != AgentRunStatus.COMPLETED) {
            throw new AssertionError(
                    "live coding run did not complete: " + safeFailureSummary(outcome.completed(), outcome.events()));
        }
        assertRealModelEvidence(outcome.events(), metadata);
        if (specification.approval().equals("ASK_REJECT")) {
            verifyRejectedApproval(workspace, outcome.events(), outcome.rejectedApprovals());
        } else {
            verifyOracle(specification.caseId(), workspace, caseRoot.resolve("oracle-classes"), outcome.events());
        }
        writeEvidence(
                specification,
                outcome.completed(),
                outcome.events(),
                metadata,
                fixtureDigest,
                Duration.between(startedAt, now()),
                outcome.rejectedApprovals(),
                changedPaths,
                "PASSED");
    }

    private static ClientOutcome executeCase(
            CaseSpec specification, CodingSessionClient client, ProjectId projectId, Instant startedAt)
            throws Exception {
        var created = client.create(projectId, specification.task(), "live-e2e-create-" + specification.caseId());
        var sessionId = created.summary().sessionId();
        AgentRunSnapshot completed = created.activeRun().orElseThrow();
        Instant deadline = startedAt.plusSeconds(specification.timeoutSeconds());
        int rejectedApprovals = 0;
        while (!completed.status().isTerminal() && now().isBefore(deadline)) {
            var pending = client.pendingInteraction(completed.runId());
            if (pending.isPresent()) {
                var request = pending.orElseThrow();
                boolean reject = specification.approval().equals("ASK_REJECT");
                AgentRunSnapshot latest = client.findRun(completed.runId()).orElseThrow();
                if (latest.status().isTerminal()) {
                    completed = latest;
                    break;
                }
                try {
                    client.respond(
                            request,
                            reject ? InteractionAction.REJECT : InteractionAction.APPROVE,
                            "live-e2e-" + (reject ? "reject-" : "approve-") + request.requestId());
                    if (reject) rejectedApprovals++;
                } catch (IllegalStateException responseRace) {
                    latest = client.findRun(completed.runId()).orElseThrow();
                    if (!latest.status().isTerminal()) throw responseRace;
                    completed = latest;
                    break;
                }
            }
            Thread.sleep(25);
            completed = client.findRun(completed.runId()).orElseThrow();
        }
        if (!completed.status().isTerminal()) {
            client.cancel(sessionId, "live-e2e-timeout-" + specification.caseId());
            throw new AssertionError("live coding case exceeded its bounded timeout");
        }
        return new ClientOutcome(completed, readAllEvents(client, completed), rejectedApprovals);
    }

    private static List<AgentRunEvent> readAllEvents(CodingSessionClient client, AgentRunSnapshot completed) {
        java.util.ArrayList<AgentRunEvent> events = new java.util.ArrayList<>();
        RunEventCursor cursor = RunEventCursor.beforeFirst(completed.runId());
        boolean more;
        do {
            var page = client.events(completed.runId(), cursor, 100);
            events.addAll(page.items());
            cursor = page.nextCursor();
            more = page.hasMore();
        } while (more);
        return List.copyOf(events);
    }

    private static void verifyOracle(String caseId, Path workspace, Path classes, List<AgentRunEvent> events)
            throws Exception {
        switch (caseId) {
            case "HF-06-E2E-CLI-001" -> {
                compile(workspace, classes);
                assertThat(invokeStatic(
                                classes,
                                "sample.Clamp",
                                "clamp",
                                new Class<?>[] {int.class, int.class, int.class},
                                -9,
                                0,
                                10))
                        .isEqualTo(0);
                assertThat(invokeStatic(
                                classes,
                                "sample.Clamp",
                                "clamp",
                                new Class<?>[] {int.class, int.class, int.class},
                                19,
                                0,
                                10))
                        .isEqualTo(10);
                assertThat(invokeStatic(
                                classes,
                                "sample.Clamp",
                                "clamp",
                                new Class<?>[] {int.class, int.class, int.class},
                                6,
                                0,
                                10))
                        .isEqualTo(6);
            }
            case "HF-06-E2E-CLI-002" -> {
                compile(workspace, classes);
                try (URLClassLoader loader = loader(classes)) {
                    Class<?> policyType = loader.loadClass("sample.DiscountPolicy");
                    Class<?> implementation = loader.loadClass("sample.ThresholdDiscountPolicy");
                    Object policy =
                            implementation.getConstructor(int.class, int.class).newInstance(100, 25);
                    Method total = loader.loadClass("sample.OrderTotal")
                            .getMethod("totalAfterDiscount", int.class, policyType);
                    assertThat(total.invoke(null, 99, policy)).isEqualTo(99);
                    assertThat(total.invoke(null, 100, policy)).isEqualTo(75);
                    Object largeDiscount =
                            implementation.getConstructor(int.class, int.class).newInstance(1, 500);
                    assertThat(total.invoke(null, 20, largeDiscount)).isEqualTo(0);
                }
            }
            case "HF-06-E2E-CLI-003" -> {
                compile(workspace, classes);
                String regression =
                        Files.readString(workspace.resolve("src/test/java/sample/UsernameValidatorTest.java"));
                assertThat(regression).contains("two words");
                assertThat(invokeStatic(
                                classes,
                                "sample.UsernameValidator",
                                "isValid",
                                new Class<?>[] {String.class},
                                "two words"))
                        .isEqualTo(false);
                assertThat(invokeStatic(
                                classes,
                                "sample.UsernameValidator",
                                "isValid",
                                new Class<?>[] {String.class},
                                "valid_user"))
                        .isEqualTo(true);
            }
            case "HF-06-E2E-CLI-004" -> {
                String pom = Files.readString(workspace.resolve("pom.xml"));
                assertThat(pom).contains("<sourceDirectory>src/main/java</sourceDirectory>");
                assertThat(pom).doesNotContain("<sourceDirectory>src/java</sourceDirectory>");
                compile(workspace, classes);
                assertThat(invokeStatic(classes, "sample.App", "greeting", new Class<?>[] {}))
                        .isEqualTo("ready");
            }
            case "HF-06-E2E-CLI-005" -> {
                compile(workspace, classes);
                assertThat(Files.isRegularFile(workspace.resolve("src/main/java/sample/MoneyFormatter.java")))
                        .isTrue();
                assertThat(invokeStatic(classes, "sample.ReceiptFormatter", "usd", new Class<?>[] {int.class}, 105))
                        .isEqualTo("USD 1.05");
                assertThat(invokeStatic(classes, "sample.ReceiptFormatter", "cad", new Class<?>[] {int.class}, 250))
                        .isEqualTo("CAD 2.50");
                String formatter = Files.readString(workspace.resolve("src/main/java/sample/ReceiptFormatter.java"));
                assertThat(formatter).contains("MoneyFormatter.format");
            }
            case "HF-06-E2E-CLI-006" -> {
                assertThat(Files.notExists(workspace.resolve("src/main/java/sample/LegacySlugger.java")))
                        .isTrue();
                assertThat(Files.isRegularFile(workspace.resolve("src/main/java/sample/Slugger.java")))
                        .isTrue();
                compile(workspace, classes);
                try (URLClassLoader loader = loader(classes)) {
                    Object service = loader.loadClass("sample.ArticleService")
                            .getConstructor()
                            .newInstance();
                    assertThat(service.getClass()
                                    .getMethod("articlePath", String.class)
                                    .invoke(service, "Hello Agent"))
                            .isEqualTo("/articles/hello-agent");
                }
            }
            case "HF-06-E2E-CLI-007" -> {
                compile(workspace, classes);
                assertThat(invokeStatic(
                                classes,
                                "sample.RetryPolicy",
                                "shouldRetry",
                                new Class<?>[] {int.class, int.class},
                                2,
                                3))
                        .isEqualTo(true);
                assertThat(invokeStatic(
                                classes,
                                "sample.RetryPolicy",
                                "shouldRetry",
                                new Class<?>[] {int.class, int.class},
                                3,
                                3))
                        .isEqualTo(false);
            }
            case "HF-06-E2E-CLI-008" -> {
                compile(workspace, classes);
                try (URLClassLoader loader = loader(classes)) {
                    Class<?> range = loader.loadClass("sample.Range");
                    Object instance = range.getConstructor(int.class, int.class).newInstance(-2, 2);
                    Method contains = range.getMethod("contains", int.class);
                    assertThat(contains.invoke(instance, -2)).isEqualTo(true);
                    assertThat(contains.invoke(instance, 2)).isEqualTo(true);
                    assertThat(contains.invoke(instance, -3)).isEqualTo(false);
                }
                assertThat(events)
                        .extracting(AgentRunEvent::eventType)
                        .contains("execution.failed", "execution.completed");
            }
            default -> throw new IllegalArgumentException("no oracle for " + caseId);
        }
    }

    private static void verifyRejectedApproval(Path workspace, List<AgentRunEvent> events, int rejectedApprovals) {
        assertThat(rejectedApprovals).isGreaterThanOrEqualTo(1);
        assertThat(Files.notExists(workspace.resolve("requested.txt"))).isTrue();
        long executedSideEffects = events.stream()
                .map(AgentRunEvent::payload)
                .filter(RunEventPayloads.ToolLifecycle.class::isInstance)
                .map(RunEventPayloads.ToolLifecycle.class::cast)
                .filter(event -> event.status().equals("SUCCEEDED"))
                .map(RunEventPayloads.ToolLifecycle::displayName)
                .filter(Set.of("file.create", "file.write", "execution.run")::contains)
                .count();
        assertThat(executedSideEffects).isZero();
    }

    private static void assertRealModelEvidence(List<AgentRunEvent> events, CodingAgentClientMetadata metadata) {
        List<RunEventPayloads.ModelLifecycle> modelCalls = events.stream()
                .map(AgentRunEvent::payload)
                .filter(RunEventPayloads.ModelLifecycle.class::isInstance)
                .map(RunEventPayloads.ModelLifecycle.class::cast)
                .filter(event -> event.status().equals("SUCCEEDED"))
                .toList();
        assertThat(modelCalls).isNotEmpty();
        modelCalls.forEach(event -> {
            assertThat(event.providerId()).isEqualTo(metadata.providerId());
            assertThat(event.modelId()).isEqualTo(metadata.modelId());
            assertThat(event.modelCallId()).isNotBlank();
            assertThat(event.inputTokens()).isPositive();
            assertThat(event.outputTokens()).isPositive();
        });
    }

    private static String safeFailureSummary(AgentRunSnapshot snapshot, List<AgentRunEvent> events) {
        String error = snapshot.error()
                .map(value -> value.code().wireCode() + "/" + value.category().name() + "/"
                        + value.details().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
                                .collect(Collectors.joining(",")))
                .orElse("none");
        String operations = events.stream()
                .skip(Math.max(0, events.size() - 40L))
                .map(AgentRunEvent::eventType)
                .collect(Collectors.joining(" -> "));
        return "status=" + snapshot.status().name() + ", error=" + error + ", recent=" + operations;
    }

    private static void compile(Path workspace, Path output) throws Exception {
        Files.createDirectories(output);
        List<Path> sources;
        try (var paths = Files.walk(workspace.resolve("src"))) {
            sources = paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        assertThat(sources).isNotEmpty();
        JavaCompiler compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "JDK compiler required");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var units = files.getJavaFileObjectsFromPaths(sources);
            boolean successful = Boolean.TRUE.equals(compiler.getTask(
                            null, files, diagnostics, List.of("--release", "21", "-d", output.toString()), null, units)
                    .call());
            if (!successful) {
                String codes = diagnostics.getDiagnostics().stream()
                        .map(diagnostic -> diagnostic.getCode())
                        .distinct()
                        .sorted()
                        .collect(Collectors.joining(","));
                throw new AssertionError("external Java oracle compilation failed: " + codes);
            }
        }
    }

    private static Object invokeStatic(
            Path classes, String className, String methodName, Class<?>[] parameterTypes, Object... arguments)
            throws Exception {
        try (URLClassLoader loader = loader(classes)) {
            return loader.loadClass(className)
                    .getMethod(methodName, parameterTypes)
                    .invoke(null, arguments);
        }
    }

    private static URLClassLoader loader(Path classes) throws Exception {
        return new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()}, null);
    }

    private static void writeEvidence(
            CaseSpec specification,
            AgentRunSnapshot completed,
            List<AgentRunEvent> events,
            CodingAgentClientMetadata metadata,
            String fixtureDigest,
            Duration duration,
            int rejectedApprovals,
            List<String> changedPaths,
            String oracle)
            throws Exception {
        List<Map<String, Object>> modelCalls = events.stream()
                .map(AgentRunEvent::payload)
                .filter(RunEventPayloads.ModelLifecycle.class::isInstance)
                .map(RunEventPayloads.ModelLifecycle.class::cast)
                .map(event -> Map.<String, Object>of(
                        "providerId", event.providerId(),
                        "modelId", event.modelId(),
                        "modelCallId", event.modelCallId(),
                        "status", event.status(),
                        "inputTokens", event.inputTokens(),
                        "outputTokens", event.outputTokens(),
                        "finishReason", event.finishReason(),
                        "reasonCode", event.reasonCode()))
                .toList();
        long toolCalls = events.stream()
                .filter(event -> event.eventType().equals("tool.call.requested"))
                .count();
        long failedTools = events.stream()
                .filter(event -> event.eventType().equals("tool.call.failed"))
                .count();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 2);
        evidence.put("batchId", runId);
        evidence.put("caseId", specification.caseId());
        evidence.put("caseVersion", specification.caseVersion());
        evidence.put("fixtureDigest", fixtureDigest);
        evidence.put("runId", completed.runId().value());
        evidence.put("status", completed.status().name());
        evidence.put("durationMillis", duration.toMillis());
        evidence.put("agentAssemblyDigest", metadata.assemblyDigest());
        evidence.put("modelCalls", modelCalls);
        evidence.put("toolCalls", toolCalls);
        evidence.put("failedToolCalls", failedTools);
        evidence.put("rejectedApprovals", rejectedApprovals);
        evidence.put("changedPaths", changedPaths);
        evidence.put("oracle", oracle);
        Path base = Path.of(System.getProperty("basedir", "."));
        Path reports = Files.createDirectories(base.resolve("target/coding-agent-live-e2e-evidence"));
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(reports.resolve(specification.caseId() + ".json").toFile(), evidence);
    }

    private static Map<String, String> fileDigests(Path root) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                if (relative.startsWith(".git/") || relative.startsWith(".verify-out/")) continue;
                result.put(relative, sha256(path));
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> changedPaths(Map<String, String> before, Map<String, String> after) {
        java.util.TreeSet<String> paths = new java.util.TreeSet<>();
        paths.addAll(before.keySet());
        paths.addAll(after.keySet());
        return paths.stream()
                .filter(path -> !Objects.equals(before.get(path), after.get(path)))
                .toList();
    }

    private static String aggregateDigest(Map<String, String> values) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        values.forEach((path, hash) -> {
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(hash.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        });
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static String digest(Path path) {
        try {
            return sha256(path);
        } catch (Exception exception) {
            throw new AssertionError("protected path is missing or unreadable", exception);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void copyFixture(String fixture, Path workspace) throws Exception {
        var resource = Objects.requireNonNull(
                CodingAgentLiveE2E.class.getResource("/coding-e2e/fixtures/" + fixture), "missing fixture " + fixture);
        Path source = Path.of(resource.toURI());
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                Path target = workspace.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        if (Files.isRegularFile(workspace.resolve("verify.json"))) {
            var verifier = Objects.requireNonNull(
                    CodingAgentLiveE2E.class.getResource("/coding-e2e/support/verify_java.py"),
                    "missing shared Coding E2E verifier");
            Files.copy(Path.of(verifier.toURI()), workspace.resolve("verify.py"), StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void initializeGitBaseline(Path workspace) throws Exception {
        runGit(workspace, "init", "--template=");
        runGit(workspace, "config", "user.name", "Haifa Coding E2E");
        runGit(workspace, "config", "user.email", "coding-e2e@invalid.example");
        runGit(workspace, "add", "--all");
        runGit(workspace, "commit", "--no-gpg-sign", "--no-verify", "-m", "test: establish fixture baseline");
    }

    private static void runGit(Path workspace, String... arguments) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(workspace.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("git fixture setup timed out");
        }
        byte[] output = process.getInputStream().readNBytes(8192);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git fixture setup failed with exit " + process.exitValue() + ": "
                    + new String(output, StandardCharsets.UTF_8));
        }
    }

    private static Map<String, CaseSpec> loadCases() {
        try (var input = CodingAgentLiveE2E.class.getResourceAsStream("/coding-e2e/cases.yaml")) {
            CaseCatalog catalog = new ObjectMapper(new YAMLFactory())
                    .readValue(Objects.requireNonNull(input, "missing coding E2E case catalog"), CaseCatalog.class);
            Map<String, CaseSpec> result = new LinkedHashMap<>();
            for (CaseSpec item : catalog.cases()) {
                CaseSpec normalized = item.normalized();
                if (result.put(normalized.caseId(), normalized) != null) {
                    throw new IllegalStateException("duplicate coding E2E case " + normalized.caseId());
                }
            }
            return Map.copyOf(result);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void validateApprovedRoot(Path root, String expectedRunId) throws Exception {
        if (!root.isAbsolute() || !Files.isDirectory(root)) {
            throw new IllegalStateException("HAIFA_FT_ROOT must be an existing absolute directory");
        }
        Path real = root.toRealPath();
        Path current = Path.of(".").toRealPath();
        Path home = Path.of(System.getProperty("user.home")).toRealPath();
        if (real.equals(real.getRoot()) || real.equals(current) || real.equals(home) || current.startsWith(real)) {
            throw new IllegalStateException("HAIFA_FT_ROOT is too broad");
        }
        Path sentinel = real.resolve(ROOT_SENTINEL);
        if (!Files.isRegularFile(sentinel)
                || !Files.readString(sentinel, StandardCharsets.UTF_8).trim().equals(expectedRunId)) {
            throw new IllegalStateException("live E2E root sentinel does not match HAIFA_FT_RUN_ID");
        }
        try (var children = Files.list(real)) {
            List<Path> unknown = children.filter(
                            path -> !path.getFileName().toString().equals(ROOT_SENTINEL))
                    .toList();
            if (!unknown.isEmpty()) throw new IllegalStateException("HAIFA_FT_ROOT must start empty");
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value.trim();
    }

    private static void requireEnvironment(String name, String expected) {
        if (!expected.equalsIgnoreCase(requiredEnvironment(name))) {
            throw new IllegalStateException(name + " must be " + expected);
        }
    }

    private static Instant now() {
        return Instant.ofEpochMilli(System.currentTimeMillis());
    }

    private record ClientOutcome(AgentRunSnapshot completed, List<AgentRunEvent> events, int rejectedApprovals) {
        private ClientOutcome {
            completed = Objects.requireNonNull(completed, "completed must not be null");
            events = List.copyOf(Objects.requireNonNull(events, "events must not be null"));
        }
    }

    private record CaseCatalog(List<CaseSpec> cases) {
        private CaseCatalog {
            cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
        }
    }

    private record CaseSpec(
            String caseId,
            String caseVersion,
            String title,
            String fixture,
            String approval,
            long timeoutSeconds,
            long maxToolCalls,
            List<String> protectedPaths,
            String task) {
        private CaseSpec normalized() {
            return new CaseSpec(
                    required(caseId, "caseId"),
                    required(caseVersion, "caseVersion"),
                    required(title, "title"),
                    required(fixture, "fixture"),
                    required(approval, "approval"),
                    timeoutSeconds,
                    maxToolCalls,
                    protectedPaths == null ? List.of() : List.copyOf(protectedPaths),
                    required(task, "task"));
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
            return value.trim();
        }
    }
}
