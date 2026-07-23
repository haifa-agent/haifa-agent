package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
import java.util.concurrent.locks.LockSupport;
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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Real-provider coding evaluation. Ordinary builds skip it unless the explicit live switch is enabled. */
@Tag("live")
@Tag("functional")
@Tag("cli-coding")
@Tag("p0")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodingAgentLiveE2E {
    private static final String LIVE_SWITCH = "HAIFA_CLI_LIVE_E2E_TEST";
    private static final String ROOT_SENTINEL = ".haifa-cli-live-e2e-root";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, CaseSpec> CASES = loadCases();
    private static Path approvedRoot;
    private static String runId;
    private static String expectedProviderId;
    private static String credentialEnvironmentName;
    private static CliConfiguration.Model liveModel;

    @BeforeAll
    static void requireExplicitLiveEnvironment() throws Exception {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv(LIVE_SWITCH)),
                "real-model CLI E2E requires " + LIVE_SWITCH + "=true");
        requireEnvironment("HAIFA_FT_ENABLED", "true");
        requireEnvironment("HAIFA_FT_MODE", "LIVE");
        liveModel = liveModel();
        String apiKey = System.getenv(credentialEnvironmentName);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(credentialEnvironmentName + " is required");
        }
        runId = requiredEnvironment("HAIFA_FT_RUN_ID");
        approvedRoot =
                Path.of(requiredEnvironment("HAIFA_FT_ROOT")).toAbsolutePath().normalize();
        validateApprovedRoot(approvedRoot, runId);
        assertThat(CASES).hasSize(9);
    }

    @Test
    @Order(1)
    void repairsSingleFileBoundaryDefect() throws Exception {
        runCase("HF-06-E2E-CLI-001");
    }

    @Test
    @Order(2)
    void implementsMultiFileDiscountFeature() throws Exception {
        runCase("HF-06-E2E-CLI-002");
    }

    @Test
    @Order(3)
    void addsRegressionTestAndRepairsValidation() throws Exception {
        runCase("HF-06-E2E-CLI-003");
    }

    @Test
    @Order(4)
    void repairsMavenSourceDirectoryConfiguration() throws Exception {
        runCase("HF-06-E2E-CLI-004");
    }

    @Test
    @Order(5)
    void performsBehaviorPreservingRefactor() throws Exception {
        runCase("HF-06-E2E-CLI-005");
    }

    @Test
    @Order(6)
    void migratesTypeAcrossFileLifecycle() throws Exception {
        runCase("HF-06-E2E-CLI-006");
    }

    @Test
    @Order(7)
    void preservesUnrelatedDirtyWorkspaceContent() throws Exception {
        runCase("HF-06-E2E-CLI-007");
    }

    @Test
    @Order(8)
    void diagnosesFailedExecutionAndRecovers() throws Exception {
        runCase("HF-06-E2E-CLI-008");
    }

    @Test
    @Order(9)
    void rejectedApprovalProducesNoSideEffect() throws Exception {
        runCase("HF-06-E2E-CLI-009");
    }

    private static void runCase(String caseId) throws Exception {
        CaseSpec specification = Objects.requireNonNull(CASES.get(caseId), "unknown live case " + caseId);
        Path caseRoot = Files.createDirectory(approvedRoot.resolve(caseId + "-" + java.util.UUID.randomUUID()));
        Path workspace = Files.createDirectory(caseRoot.resolve("workspace"));
        copyFixture(specification.fixture(), workspace);
        Map<String, String> before = fileDigests(workspace);
        String fixtureDigest = aggregateDigest(before);
        String sentinelBefore = sha256(approvedRoot.resolve(ROOT_SENTINEL));
        Map<String, String> protectedBefore = specification.protectedPaths().stream()
                .collect(Collectors.toMap(path -> path, path -> digest(workspace.resolve(path))));
        ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();
        PrintStream captured = new PrintStream(capturedBytes, true, StandardCharsets.UTF_8);
        CliConfiguration configuration = configuration(specification);
        Instant startedAt = Instant.now();
        int rejectedApprovals = 0;
        io.haifa.agent.runtime.api.AgentRunSnapshot completed;
        List<RuntimeTraceEvent> traces;

        try (LocalCodingAgent agent = LocalCodingAgent.create(workspace, configuration, captured)) {
            var accepted = agent.start(specification.task());
            Instant deadline = startedAt.plusSeconds(specification.timeoutSeconds());
            completed = agent.runtime().find(accepted.runId()).orElseThrow();
            while (!completed.status().isTerminal() && Instant.now().isBefore(deadline)) {
                var pending = agent.interactions().pending(accepted.runId());
                if (pending.isPresent()) {
                    if (!specification.approval().equals("ASK_REJECT")) {
                        throw new AssertionError("unexpected approval request for automatically approved case");
                    }
                    var request = pending.orElseThrow();
                    agent.runtime()
                            .respond(new InteractionResponse(
                                    new InteractionResponseId(
                                            agent.identifiers().nextValue()),
                                    request.id(),
                                    request.runId(),
                                    InteractionResponseType.REJECT,
                                    List.of(),
                                    "live-e2e-reject-" + request.id().value(),
                                    agent.time().now()));
                    rejectedApprovals++;
                }
                LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
                completed = agent.runtime().find(accepted.runId()).orElseThrow();
            }
            if (!completed.status().isTerminal()) {
                agent.cancel(accepted.runId());
                throw new AssertionError("live coding case exceeded its bounded timeout");
            }
            traces = agent.traceEvents();
        }

        String capturedOutput = capturedBytes.toString(StandardCharsets.UTF_8);
        rejectSensitiveOutput(capturedOutput, workspace);
        assertThat(sha256(approvedRoot.resolve(ROOT_SENTINEL))).isEqualTo(sentinelBefore);
        specification.protectedPaths().forEach(path -> assertThat(digest(workspace.resolve(path)))
                .as("protected workspace path %s", path)
                .isEqualTo(protectedBefore.get(path)));
        assertRealModelEvidence(traces);
        if (completed.status() != AgentRunStatus.COMPLETED) {
            throw new AssertionError("live coding run did not complete: " + safeFailureSummary(completed, traces));
        }
        if (specification.approval().equals("ASK_REJECT")) {
            verifyRejectedApproval(workspace, traces, rejectedApprovals);
        } else {
            verifyOracle(specification.caseId(), workspace, caseRoot.resolve("oracle-classes"), traces);
        }
        Map<String, String> after = fileDigests(workspace);
        List<String> changedPaths = changedPaths(before, after);
        writeEvidence(
                specification,
                completed,
                traces,
                fixtureDigest,
                Duration.between(startedAt, Instant.now()),
                rejectedApprovals,
                changedPaths);
    }

    private static CliConfiguration configuration(CaseSpec specification) {
        CliConfiguration defaults = CliConfiguration.defaults();
        ApprovalMode approval = specification.approval().equals("ASK_REJECT") ? ApprovalMode.ASK : ApprovalMode.AUTO;
        return new CliConfiguration(
                liveModel,
                defaults.enabledTools(),
                List.of(),
                defaults.execution(),
                approval,
                Duration.ofSeconds(specification.timeoutSeconds()),
                defaults.maxIterations(),
                specification.maxToolCalls());
    }

    private static void verifyOracle(String caseId, Path workspace, Path classes, List<RuntimeTraceEvent> traces)
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
                List<Boolean> executionResults = toolResults(traces, "execution.run");
                assertThat(executionResults).contains(false, true);
                assertThat(executionResults).hasSizeGreaterThanOrEqualTo(2);
            }
            default -> throw new IllegalArgumentException("no oracle for " + caseId);
        }
    }

    private static void verifyRejectedApproval(Path workspace, List<RuntimeTraceEvent> traces, int rejectedApprovals) {
        assertThat(rejectedApprovals).isGreaterThanOrEqualTo(1);
        assertThat(Files.notExists(workspace.resolve("requested.txt"))).isTrue();
        long executedSideEffects = traces.stream()
                .filter(event -> event.operation().equals("tool.execute"))
                .map(event -> String.valueOf(event.safeAttributes().get("toolName")))
                .filter(Set.of("file.create", "file.write", "execution.run")::contains)
                .count();
        assertThat(executedSideEffects).isZero();
    }

    private static void assertRealModelEvidence(List<RuntimeTraceEvent> traces) {
        List<RuntimeTraceEvent> modelCalls = traces.stream()
                .filter(event -> event.operation().equals("model.invoke"))
                .toList();
        assertThat(modelCalls).isNotEmpty();
        modelCalls.forEach(event -> {
            assertThat(String.valueOf(event.safeAttributes().get("providerId"))).isEqualTo(expectedProviderId);
            assertThat(String.valueOf(event.safeAttributes().get("modelId"))).isNotBlank();
            assertThat(String.valueOf(event.safeAttributes().get("adapterVersion")))
                    .isNotBlank();
            assertThat(String.valueOf(event.safeAttributes().get("modelCallId")))
                    .isNotBlank();
            assertThat(String.valueOf(event.safeAttributes().get("responseId"))).isNotBlank();
            assertThat(((Number) event.safeAttributes().get("inputTokens")).longValue())
                    .isPositive();
            assertThat(((Number) event.safeAttributes().get("outputTokens")).longValue())
                    .isPositive();
        });
    }

    private static String safeFailureSummary(
            io.haifa.agent.runtime.api.AgentRunSnapshot snapshot, List<RuntimeTraceEvent> traces) {
        String error = snapshot.error()
                .map(value -> value.code().value() + "/" + value.category().name() + "/"
                        + value.attributes().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
                                .collect(Collectors.joining(",")))
                .orElse("none");
        String operations = traces.stream()
                .skip(Math.max(0, traces.size() - 40L))
                .map(event -> event.operation() + safeTraceSuffix(event))
                .collect(Collectors.joining(" -> "));
        return "status=" + snapshot.status().name() + ", error=" + error + ", recent=" + operations;
    }

    private static String safeTraceSuffix(RuntimeTraceEvent event) {
        if (event.operation().equals("model.error")) {
            return "[type=" + event.safeAttributes().getOrDefault("exceptionType", "unknown") + ",category="
                    + event.safeAttributes().getOrDefault("category", "unknown") + ",http="
                    + event.safeAttributes().getOrDefault("httpStatus", "unknown") + ",providerCode="
                    + event.safeAttributes().getOrDefault("providerCode", "unknown") + "]";
        }
        if (event.operation().equals("tool.execute")) {
            return "[" + event.safeAttributes().getOrDefault("toolName", "unknown") + "]";
        }
        if (event.operation().equals("tool.persisted")) {
            return "[successful=" + event.safeAttributes().getOrDefault("successful", "unknown") + "]";
        }
        return "";
    }

    private static List<Boolean> toolResults(List<RuntimeTraceEvent> traces, String toolName) {
        Set<String> calls = traces.stream()
                .filter(event -> event.operation().equals("tool.execute"))
                .filter(event -> toolName.equals(event.safeAttributes().get("toolName")))
                .flatMap(event -> event.toolCallId().stream())
                .map(value -> value.value())
                .collect(Collectors.toSet());
        return traces.stream()
                .filter(event -> event.operation().equals("tool.persisted"))
                .filter(event -> event.toolCallId()
                        .map(value -> calls.contains(value.value()))
                        .orElse(false))
                .map(event -> (Boolean) event.safeAttributes().get("successful"))
                .toList();
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
            io.haifa.agent.runtime.api.AgentRunSnapshot completed,
            List<RuntimeTraceEvent> traces,
            String fixtureDigest,
            Duration duration,
            int rejectedApprovals,
            List<String> changedPaths)
            throws Exception {
        List<Map<String, Object>> modelCalls = traces.stream()
                .filter(event -> event.operation().equals("model.invoke"))
                .map(event -> Map.<String, Object>ofEntries(
                        Map.entry("providerId", event.safeAttributes().get("providerId")),
                        Map.entry("providerVersion", event.safeAttributes().get("providerVersion")),
                        Map.entry("modelId", event.safeAttributes().get("modelId")),
                        Map.entry("modelVersion", event.safeAttributes().get("modelVersion")),
                        Map.entry("adapterVersion", event.safeAttributes().get("adapterVersion")),
                        Map.entry("modelCallId", event.safeAttributes().get("modelCallId")),
                        Map.entry("responseId", event.safeAttributes().get("responseId")),
                        Map.entry("inputTokens", event.safeAttributes().get("inputTokens")),
                        Map.entry("outputTokens", event.safeAttributes().get("outputTokens")),
                        Map.entry("cacheHitTokens", event.safeAttributes().get("cacheHitTokens")),
                        Map.entry("cacheMissTokens", event.safeAttributes().get("cacheMissTokens")),
                        Map.entry("reasoningTokens", event.safeAttributes().get("reasoningTokens"))))
                .toList();
        long toolCalls = traces.stream()
                .filter(event -> event.operation().equals("tool.execute"))
                .count();
        long failedTools = traces.stream()
                .filter(event -> event.operation().equals("tool.persisted"))
                .filter(event -> Boolean.FALSE.equals(event.safeAttributes().get("successful")))
                .count();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", "1.0");
        evidence.put("batchId", runId);
        evidence.put("caseId", specification.caseId());
        evidence.put("caseVersion", specification.caseVersion());
        evidence.put("fixtureDigest", fixtureDigest);
        evidence.put("runId", completed.runId().value());
        evidence.put("status", completed.status().name());
        evidence.put("durationMillis", duration.toMillis());
        evidence.put("modelCalls", modelCalls);
        evidence.put("toolCalls", toolCalls);
        evidence.put("failedToolCalls", failedTools);
        evidence.put("rejectedApprovals", rejectedApprovals);
        evidence.put("changedPaths", changedPaths);
        evidence.put("oracle", "PASSED");
        Path base = Path.of(System.getProperty("basedir", "."));
        Path reports = Files.createDirectories(base.resolve("target/coding-agent-live-e2e-evidence"));
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(reports.resolve(specification.caseId() + ".json").toFile(), evidence);
    }

    private static void rejectSensitiveOutput(String output, Path workspace) {
        String apiKey = System.getenv(credentialEnvironmentName);
        if (apiKey != null && !apiKey.isBlank() && output.contains(apiKey)) {
            throw new AssertionError("captured CLI output contains a credential");
        }
        if (output.contains("reasoning_content")) {
            throw new AssertionError("captured CLI output contains reasoning content metadata");
        }
        if (output.contains(workspace.toAbsolutePath().toString())) {
            throw new AssertionError("captured CLI output contains the real workspace path");
        }
    }

    private static Map<String, String> fileDigests(Path root) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                if (relative.startsWith(".verify-out/")) continue;
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

    private static CliConfiguration.Model liveModel() {
        String provider = System.getenv()
                .getOrDefault("HAIFA_CLI_LIVE_E2E_PROVIDER", "deepseek")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        expectedProviderId = provider;
        return switch (provider) {
            case "deepseek" -> {
                credentialEnvironmentName = "DEEPSEEK_API_KEY";
                yield CliConfiguration.defaults().model();
            }
            case "aliyun-bailian" -> {
                credentialEnvironmentName = "DASHSCOPE_API_KEY";
                String modelId = System.getenv().getOrDefault("HAIFA_BAILIAN_MODEL_ID", "qwen-plus");
                String region = System.getenv().getOrDefault("HAIFA_BAILIAN_REGION", "cn-beijing");
                yield new CliConfiguration.Model(
                        provider,
                        modelId,
                        null,
                        "env://" + credentialEnvironmentName,
                        requiredEnvironment("HAIFA_BAILIAN_WORKSPACE_ID"),
                        region);
            }
            default ->
                throw new IllegalStateException("HAIFA_CLI_LIVE_E2E_PROVIDER must be deepseek or aliyun-bailian");
        };
    }

    private static void requireEnvironment(String name, String expected) {
        if (!expected.equalsIgnoreCase(requiredEnvironment(name))) {
            throw new IllegalStateException(name + " must be " + expected);
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
