package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.testing.assets.TestingAssetPreflight;
import io.haifa.agent.testing.evidence.EvidenceFinalizer;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.run.SafeRunRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parameterized, plan-first entry point for the autonomous-delivery evidence campaign. */
public final class AutonomousDeliveryHarnessMain {
    private static final String DEFAULT_MATRIX_ID = "autonomous-delivery-v1";
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
        new TestingAssetPreflight().validate(options.projectRoot(), options.configRoot());
        AutonomousDeliveryCaseCatalog catalog = AutonomousDeliveryCaseCatalog.loadVerified();
        AutonomousDeliverySuiteManifest suite = null;
        String matrixId = DEFAULT_MATRIX_ID;
        if (options.suiteId() != null) {
            suite = new AutonomousDeliverySuiteManifestLoader().load(options.configRoot(), options.suiteId(), catalog);
            matrixId = suite.matrixRef();
        }
        AutonomousDeliveryMatrixManifest matrix =
                new AutonomousDeliveryMatrixManifestLoader().load(options.configRoot(), matrixId);
        AutonomousDeliveryMatrixManifest.Combination combination =
                matrix.requireCombination(options.matrixCombination());
        DeliveryHostProfile hostProfile = combination.requireCurrentHost();
        RepositoryRevision productRevision = RepositoryRevision.inspect(options.projectRoot());
        RepositoryRevision testConfigRevision = RepositoryRevision.inspect(options.configRoot());
        productRevision.requireCommit(matrix.compatibleAgentBaselineCommit(), "Autonomous Delivery matrix");
        if (options.command().equals("plan")) {
            printPlan(catalog, suite, matrix, combination, productRevision, testConfigRevision);
            return;
        }
        List<Path> repositories =
                List.of(options.projectRoot(), options.projectRoot().resolve("docs"), options.configRoot());
        if (options.command().equals("initialize-campaign")) {
            productRevision.requireClean("product repository");
            testConfigRevision.requireClean("test-config repository");
            Path campaign = new AutonomousDeliveryCampaign()
                    .initialize(
                            options.runParent(),
                            repositories,
                            catalog,
                            options.historicalBaselineRoots(),
                            matrix,
                            combination,
                            productRevision,
                            testConfigRevision);
            System.out.println("Created campaign: " + campaign);
            return;
        }
        if (options.command().equals("phase-0-gate")) {
            runPhaseZeroGate(options, catalog, repositories, matrix, combination, productRevision, testConfigRevision);
            return;
        }
        if (List.of("phase-1-gate", "phase-2-gate", "phase-3-gate").contains(options.command())) {
            if (!options.execute()) {
                throw new IllegalArgumentException(options.command() + " requires explicit --execute");
            }
            productRevision.requireClean("product repository");
            testConfigRevision.requireClean("test-config repository");
            Path campaign = requireCampaign(
                    options.campaignRoot(), repositories, matrix, combination, productRevision, testConfigRevision);
            String build = requireCommit(options.buildCommit());
            productRevision.requireCommit(build, "product repository");
            if (suite == null) {
                throw new IllegalArgumentException("--suite is required for production Phase Gates");
            }
            Map<String, Path> toolchains = new LinkedHashMap<>();
            toolchains.put("java", options.javaExecutable());
            toolchains.put("javac", options.javacExecutable());
            toolchains.put("python", options.pythonExecutable());
            toolchains.put("node", options.nodeExecutable());
            toolchains.put("go", options.goExecutable());
            toolchains.put("git", options.gitExecutable());
            Path gate = new AutonomousDeliveryGateCoordinator(clock)
                    .run(
                            campaign,
                            build,
                            suite,
                            catalog,
                            options.cliJar(),
                            toolchains,
                            hostProfile,
                            options.projectRoot(),
                            options.configRoot(),
                            combination,
                            productRevision,
                            testConfigRevision);
            System.out.println("Phase " + options.command().charAt("phase-".length()) + " gate PASS: " + gate);
            return;
        }
        throw new IllegalArgumentException("unknown command");
    }

    private void runPhaseZeroGate(
            Options options,
            AutonomousDeliveryCaseCatalog catalog,
            List<Path> repositories,
            AutonomousDeliveryMatrixManifest matrix,
            AutonomousDeliveryMatrixManifest.Combination combination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision)
            throws IOException, InterruptedException {
        productRevision.requireClean("product repository");
        testConfigRevision.requireClean("test-config repository");
        Path campaign = requireCampaign(
                options.campaignRoot(), repositories, matrix, combination, productRevision, testConfigRevision);
        String build = requireCommit(options.buildCommit());
        productRevision.requireCommit(build, "product repository");
        Path gate = campaign.resolve("phase-0").resolve("build-" + build).resolve("gate-" + GATE_TIME.format(now()));
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
        RepositoryRevision productRevisionAfter = RepositoryRevision.inspect(options.projectRoot());
        RepositoryRevision testConfigRevisionAfter = RepositoryRevision.inspect(options.configRoot());
        boolean repositoryStateStable =
                productRevision.equals(productRevisionAfter) && testConfigRevision.equals(testConfigRevisionAfter);
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 3);
        summary.put("phase", "PHASE_0");
        summary.put("buildCommit", build);
        summary.put("productRevision", productRevision);
        summary.put("testConfigRevision", testConfigRevision);
        summary.put("productRevisionAfter", productRevisionAfter);
        summary.put("testConfigRevisionAfter", testConfigRevisionAfter);
        summary.put("repositoryStateStable", repositoryStateStable);
        summary.put("startedAndFinishedAt", now().toString());
        summary.put("catalogSha256", catalog.catalogSha256());
        summary.put("matrixRef", matrix.matrixId());
        summary.put("matrixCombination", combination);
        summary.put("caseCount", results.size());
        summary.put("successful", results.size() == catalog.cases().size() && repositoryStateStable);
        summary.put("results", results);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(gate.resolve("phase-summary.json").toFile(), summary);
        EvidenceFinalizer.finalizeEvidence(gate);
        productRevision.requireUnchanged(productRevisionAfter, "product repository");
        testConfigRevision.requireUnchanged(testConfigRevisionAfter, "test-config repository");
        System.out.println("Phase 0 gate PASS: " + gate);
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    private static void printPlan(
            AutonomousDeliveryCaseCatalog catalog,
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryMatrixManifest matrix,
            AutonomousDeliveryMatrixManifest.Combination combination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision) {
        System.out.printf(
                "Catalog %s version=%s digest=%s cases=%d matrix=%s baseline=%s "
                        + "combination=%s platform=%s "
                        + "pty=%s sandbox=%s shell=%s isolation=%s execute=false%n",
                catalog.catalogId(),
                catalog.catalogVersion(),
                catalog.catalogSha256(),
                catalog.cases().size(),
                matrix.matrixId(),
                matrix.compatibleAgentBaselineCommit(),
                combination.id(),
                combination.platform(),
                combination.terminalBackend(),
                combination.sandboxProfile(),
                combination.shell(),
                combination.isolationAssurance());
        System.out.printf(
                "Revisions product=%s dirty=%s testConfig=%s dirty=%s%n",
                productRevision.commit(),
                productRevision.dirty(),
                testConfigRevision.commit(),
                testConfigRevision.dirty());
        catalog.cases()
                .forEach(testCase -> System.out.printf(
                        "  case-%s version=%s language=%s task=%s%n",
                        testCase.caseId(), testCase.caseVersion(), testCase.language(), testCase.taskType()));
        if (suite != null) {
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

    private Path requireCampaign(
            Path value,
            List<Path> repositories,
            AutonomousDeliveryMatrixManifest matrix,
            AutonomousDeliveryMatrixManifest.Combination combination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision)
            throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("--campaign-root is required");
        }
        Path campaign = SafeRunRoot.requireExternalExistingParent(value, repositories, "campaign root");
        Path manifest = campaign.resolve("campaign.json");
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalArgumentException("campaign root is missing campaign.json");
        }
        JsonNode campaignManifest = json.readTree(manifest.toFile());
        if (campaignManifest.path("schemaVersion").asInt(-1) != 3
                || !campaignManifest.path("matrixRef").asText("").equals(matrix.matrixId())
                || !campaignManifest
                        .path("matrixCombination")
                        .path("id")
                        .asText("")
                        .equals(combination.id())
                || !campaignManifest
                        .path("productRevision")
                        .path("commit")
                        .asText("")
                        .equals(productRevision.commit())
                || campaignManifest.path("productRevision").path("dirty").asBoolean(true)
                || !campaignManifest
                        .path("testConfigRevision")
                        .path("commit")
                        .asText("")
                        .equals(testConfigRevision.commit())
                || campaignManifest.path("testConfigRevision").path("dirty").asBoolean(true)) {
            throw new IllegalArgumentException(
                    "campaign matrix or repository revisions do not match the selected execution state");
        }
        return campaign;
    }

    private static String requireCommit(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("--build-commit must be a full lowercase Git commit");
        }
        return value;
    }

    static void writeManifest(Path gate) throws IOException {
        EvidenceFinalizer.writeManifest(gate);
    }

    record Options(
            String command,
            Path projectRoot,
            Path configRoot,
            Path runParent,
            Path campaignRoot,
            String buildCommit,
            String suiteId,
            String matrixCombination,
            List<Path> historicalBaselineRoots,
            boolean execute,
            Path cliJar,
            Path javaExecutable,
            Path javacExecutable,
            Path pythonExecutable,
            Path nodeExecutable,
            Path goExecutable,
            Path gitExecutable) {
        static Options parse(String[] arguments) {
            String command = "plan";
            Path projectRoot = Path.of(".").toAbsolutePath().normalize();
            Path configRoot = projectRoot.resolve("test-config");
            Path runParent = null;
            Path campaignRoot = null;
            String buildCommit = null;
            String suiteId = null;
            String matrixCombination = null;
            List<Path> historicalBaselineRoots = new ArrayList<>();
            boolean execute = false;
            Path cliJar = null;
            Path javaExecutable = null;
            Path javacExecutable = null;
            Path pythonExecutable = null;
            Path nodeExecutable = null;
            Path goExecutable = null;
            Path gitExecutable = null;
            for (int index = 0; index < arguments.length; index++) {
                switch (arguments[index]) {
                    case "plan",
                            "initialize-campaign",
                            "phase-0-gate",
                            "phase-1-gate",
                            "phase-2-gate",
                            "phase-3-gate" -> command = arguments[index];
                    case "--project-root" -> projectRoot = Path.of(value(arguments, ++index));
                    case "--config-root" -> configRoot = Path.of(value(arguments, ++index));
                    case "--run-parent" -> runParent = Path.of(value(arguments, ++index));
                    case "--campaign-root" -> campaignRoot = Path.of(value(arguments, ++index));
                    case "--build-commit" ->
                        buildCommit = value(arguments, ++index).toLowerCase(Locale.ROOT);
                    case "--suite" -> suiteId = value(arguments, ++index);
                    case "--matrix-combination" -> matrixCombination = value(arguments, ++index);
                    case "--baseline-root" -> historicalBaselineRoots.add(Path.of(value(arguments, ++index)));
                    case "--execute" -> execute = true;
                    case "--cli-jar" -> cliJar = Path.of(value(arguments, ++index));
                    case "--java-executable" -> javaExecutable = Path.of(value(arguments, ++index));
                    case "--javac-executable" -> javacExecutable = Path.of(value(arguments, ++index));
                    case "--python-executable" -> pythonExecutable = Path.of(value(arguments, ++index));
                    case "--node-executable" -> nodeExecutable = Path.of(value(arguments, ++index));
                    case "--go-executable" -> goExecutable = Path.of(value(arguments, ++index));
                    case "--git-executable" -> gitExecutable = Path.of(value(arguments, ++index));
                    default -> throw new IllegalArgumentException("unknown argument: " + arguments[index]);
                }
            }
            if (command.equals("initialize-campaign") && runParent == null) {
                throw new IllegalArgumentException("--run-parent is required");
            }
            if (matrixCombination == null || matrixCombination.isBlank()) {
                throw new IllegalArgumentException("--matrix-combination is required");
            }
            return new Options(
                    command,
                    projectRoot,
                    configRoot,
                    runParent,
                    campaignRoot,
                    buildCommit,
                    suiteId,
                    matrixCombination,
                    List.copyOf(historicalBaselineRoots),
                    execute,
                    cliJar,
                    javaExecutable,
                    javacExecutable,
                    pythonExecutable,
                    nodeExecutable,
                    goExecutable,
                    gitExecutable);
        }

        private static String value(String[] arguments, int index) {
            if (index >= arguments.length || arguments[index].isBlank()) {
                throw new IllegalArgumentException("missing argument value");
            }
            return arguments[index];
        }
    }
}
