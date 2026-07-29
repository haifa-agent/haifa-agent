package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Parameterized, plan-first entry point for the autonomous-delivery evidence campaign. */
public final class AutonomousDeliveryHarnessMain {
    private static final DateTimeFormatter GATE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;

    public AutonomousDeliveryHarnessMain() {
        this(Clock.systemUTC());
    }

    AutonomousDeliveryHarnessMain(Clock clock) {
        this.clock = clock;
    }

    public static void main(String[] arguments) {
        try {
            new AutonomousDeliveryHarnessMain().run(Options.parse(arguments));
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid autonomous-delivery request: " + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Autonomous-delivery harness failed: "
                    + exception.getClass().getSimpleName());
            System.exit(1);
        }
    }

    void run(Options options) throws Exception {
        AutonomousDeliveryCaseCatalog catalog = AutonomousDeliveryCaseCatalog.loadVerified();
        if (options.command().equals("plan")) {
            printPlan(catalog, options);
            return;
        }
        List<Path> repositories =
                List.of(options.projectRoot(), options.projectRoot().resolve("docs"), options.configRoot());
        if (options.command().equals("initialize-campaign")) {
            Path campaign = new AutonomousDeliveryCampaign()
                    .initialize(options.runParent(), repositories, catalog, options.historicalBaselineRoots());
            System.out.println("Created campaign: " + campaign);
            return;
        }
        if (options.command().equals("phase-0-gate")) {
            runPhaseZeroGate(options, catalog, repositories);
            return;
        }
        if (options.command().equals("phase-1-gate")) {
            if (!options.execute()) {
                throw new IllegalArgumentException("phase-1-gate requires explicit --execute");
            }
            Path campaign = requireCampaign(options.campaignRoot(), repositories);
            String build = requireCommit(options.buildCommit());
            requireBuildCheckout(options.projectRoot(), build);
            AutonomousDeliverySuiteManifest suite =
                    new AutonomousDeliverySuiteManifestLoader().load(options.configRoot(), options.suiteId(), catalog);
            Map<String, Path> toolchains = new LinkedHashMap<>();
            toolchains.put("java", options.javaHome());
            toolchains.put("python", options.pythonHome());
            toolchains.put("node", options.nodeHome());
            toolchains.put("go", options.goHome());
            Path gate = new AutonomousDeliveryPhaseOneGate(clock)
                    .run(campaign, build, suite, catalog, options.cliJar(), toolchains);
            System.out.println("Phase 1 gate PASS: " + gate);
            return;
        }
        throw new IllegalArgumentException("unknown command");
    }

    private void runPhaseZeroGate(Options options, AutonomousDeliveryCaseCatalog catalog, List<Path> repositories)
            throws IOException {
        Path campaign = requireCampaign(options.campaignRoot(), repositories);
        String build = requireCommit(options.buildCommit());
        Path gate = campaign.resolve("phase-0")
                .resolve("build-" + build)
                .resolve("gate-" + GATE_TIME.format(clock.instant()));
        Files.createDirectories(gate.getParent());
        Files.createDirectory(gate);
        AutonomousDeliveryFixtureStore fixtures = new AutonomousDeliveryFixtureStore();
        List<Map<String, Object>> results = new ArrayList<>();
        for (AutonomousDeliveryCase testCase : catalog.cases()) {
            Path repeat = gate.resolve("case-" + testCase.caseId()).resolve("repeat-01");
            Files.createDirectories(repeat.getParent());
            Files.createDirectory(repeat);
            fixtures.materializeCase(testCase, repeat.resolve("immutable-case"));
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("caseId", testCase.caseId());
            result.put("caseVersion", testCase.caseVersion());
            result.put("promptSha256", testCase.promptSha256());
            result.put("workspaceSha256", testCase.workspaceSha256());
            result.put("acceptanceSha256", testCase.acceptanceSha256());
            result.put("fixtureIntegrity", "PASS");
            results.add(result);
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(repeat.resolve("result.json").toFile(), result);
        }
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 1);
        summary.put("phase", "PHASE_0");
        summary.put("buildCommit", build);
        summary.put("startedAndFinishedAt", Instant.now(clock).toString());
        summary.put("catalogSha256", catalog.catalogSha256());
        summary.put("caseCount", results.size());
        summary.put("successful", results.size() == 10);
        summary.put("results", results);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(gate.resolve("phase-summary.json").toFile(), summary);
        writeManifest(gate);
        makeReadOnly(gate);
        System.out.println("Phase 0 gate PASS: " + gate);
    }

    private static void printPlan(AutonomousDeliveryCaseCatalog catalog, Options options) throws IOException {
        System.out.printf(
                "Catalog %s version=%s digest=%s cases=%d execute=false%n",
                catalog.catalogId(),
                catalog.catalogVersion(),
                catalog.catalogSha256(),
                catalog.cases().size());
        catalog.cases()
                .forEach(testCase -> System.out.printf(
                        "  case-%s version=%s language=%s task=%s%n",
                        testCase.caseId(), testCase.caseVersion(), testCase.language(), testCase.taskType()));
        if (options.suiteId() != null) {
            AutonomousDeliverySuiteManifest suite =
                    new AutonomousDeliverySuiteManifestLoader().load(options.configRoot(), options.suiteId(), catalog);
            System.out.printf(
                    "Suite %s phase=%s matrix=%s selections=%d%n",
                    suite.suiteId(),
                    suite.phase(),
                    suite.matrixRef(),
                    suite.cases().size());
            suite.cases()
                    .forEach(selection -> System.out.printf(
                            "  case-%s repetitions=%d blocking=%s%n",
                            selection.caseId(), selection.repetitions(), selection.blocking()));
        }
        System.out.println("Plan only. No campaign or external call was created.");
    }

    private static Path requireCampaign(Path value, List<Path> repositories) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("--campaign-root is required");
        }
        Path campaign = value.toAbsolutePath().normalize().toRealPath();
        AutonomousDeliveryCampaign.requireSafeParent(campaign.getParent(), repositories);
        if (!Files.isRegularFile(campaign.resolve("campaign.json"))) {
            throw new IllegalArgumentException("campaign root is missing campaign.json");
        }
        return campaign;
    }

    private static String requireCommit(String value) {
        if (value == null || !value.matches("[0-9a-f]{7,40}")) {
            throw new IllegalArgumentException("--build-commit must be a lowercase Git commit");
        }
        return value;
    }

    private static void requireBuildCheckout(Path projectRoot, String buildCommit)
            throws IOException, InterruptedException {
        Path repository = projectRoot.toAbsolutePath().normalize().toRealPath();
        ProcessBuilder builder = new ProcessBuilder("git", "rev-parse", "HEAD").directory(repository.toFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        String head = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0 || !head.equals(buildCommit)) {
            process.destroyForcibly();
            throw new IllegalArgumentException("--build-commit must exactly match the checked-out root HEAD");
        }
        ProcessBuilder statusBuilder = new ProcessBuilder("git", "status", "--porcelain", "--untracked-files=no")
                .directory(repository.toFile());
        statusBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process status = statusBuilder.start();
        String trackedChanges = new String(status.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!status.waitFor(30, TimeUnit.SECONDS) || status.exitValue() != 0 || !trackedChanges.isEmpty()) {
            status.destroyForcibly();
            throw new IllegalArgumentException("root checkout must have no tracked changes before Phase 1 Gate");
        }
    }

    static void writeManifest(Path gate) throws IOException {
        List<String> lines = new ArrayList<>();
        try (var paths = Files.walk(gate)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("manifest.sha256"))
                    .sorted(Comparator.comparing(path -> gate.relativize(path).toString()))
                    .toList()) {
                lines.add(Sha256Digests.file(file) + "  "
                        + gate.relativize(file).toString().replace('\\', '/'));
            }
        }
        Files.write(gate.resolve("manifest.sha256"), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    static void makeReadOnly(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(false, false);
            }
        }
    }

    record Options(
            String command,
            Path projectRoot,
            Path configRoot,
            Path runParent,
            Path campaignRoot,
            String buildCommit,
            String suiteId,
            List<Path> historicalBaselineRoots,
            boolean execute,
            Path cliJar,
            Path javaHome,
            Path pythonHome,
            Path nodeHome,
            Path goHome) {
        static Options parse(String[] arguments) {
            String command = "plan";
            Path projectRoot = Path.of(".").toAbsolutePath().normalize();
            Path configRoot = projectRoot.resolve("test-config");
            Path runParent = null;
            Path campaignRoot = null;
            String buildCommit = null;
            String suiteId = null;
            List<Path> historicalBaselineRoots = new ArrayList<>();
            boolean execute = false;
            Path cliJar = null;
            Path javaHome = null;
            Path pythonHome = null;
            Path nodeHome = null;
            Path goHome = null;
            for (int index = 0; index < arguments.length; index++) {
                switch (arguments[index]) {
                    case "plan", "initialize-campaign", "phase-0-gate", "phase-1-gate" -> command = arguments[index];
                    case "--project-root" -> projectRoot = Path.of(value(arguments, ++index));
                    case "--config-root" -> configRoot = Path.of(value(arguments, ++index));
                    case "--run-parent" -> runParent = Path.of(value(arguments, ++index));
                    case "--campaign-root" -> campaignRoot = Path.of(value(arguments, ++index));
                    case "--build-commit" ->
                        buildCommit = value(arguments, ++index).toLowerCase(Locale.ROOT);
                    case "--suite" -> suiteId = value(arguments, ++index);
                    case "--baseline-root" -> historicalBaselineRoots.add(Path.of(value(arguments, ++index)));
                    case "--execute" -> execute = true;
                    case "--cli-jar" -> cliJar = Path.of(value(arguments, ++index));
                    case "--java-home" -> javaHome = Path.of(value(arguments, ++index));
                    case "--python-home" -> pythonHome = Path.of(value(arguments, ++index));
                    case "--node-home" -> nodeHome = Path.of(value(arguments, ++index));
                    case "--go-home" -> goHome = Path.of(value(arguments, ++index));
                    default -> throw new IllegalArgumentException("unknown argument: " + arguments[index]);
                }
            }
            if (command.equals("initialize-campaign") && runParent == null) {
                throw new IllegalArgumentException("--run-parent is required");
            }
            return new Options(
                    command,
                    projectRoot,
                    configRoot,
                    runParent,
                    campaignRoot,
                    buildCommit,
                    suiteId,
                    List.copyOf(historicalBaselineRoots),
                    execute,
                    cliJar,
                    javaHome,
                    pythonHome,
                    nodeHome,
                    goHome);
        }

        private static String value(String[] arguments, int index) {
            if (index >= arguments.length || arguments[index].isBlank()) {
                throw new IllegalArgumentException("missing argument value");
            }
            return arguments[index];
        }
    }
}
