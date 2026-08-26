package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Fixed-root, non-executable L0-L2 environment projection for future local Coding Runs. */
final class TrustedWorkspaceEnvironmentCatalog {
    static final int MAXIMUM_PROMPT_BYTES = 8 * 1024;
    private static final int MAXIMUM_FACT_CHARACTERS = 256;
    private static final int MAXIMUM_CANDIDATE_CHARACTERS = 512;
    private static final String DYNAMIC_CAPABILITY_UNKNOWN = "executables and versions were not probed";

    private final EnvironmentFacts environment;
    private final RepositoryStatus repositoryStatus;
    private final List<String> projectSignals;
    private final List<String> validationCandidates;
    private final List<String> diagnostics;
    private final Snapshot initialSnapshot;

    TrustedWorkspaceEnvironmentCatalog(
            Path workspaceRoot,
            CliVerificationProfileDiscovery.DiscoveryResult discovery,
            TrustedProjectResourceCatalog.Snapshot projectResources,
            EnvironmentFacts environment) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(discovery, "discovery must not be null");
        Objects.requireNonNull(projectResources, "projectResources must not be null");
        Objects.requireNonNull(environment, "environment must not be null");

        List<String> safeDiagnostics = new ArrayList<>(discovery.diagnostics());
        this.repositoryStatus = repositoryStatus(root, safeDiagnostics);
        this.environment = environment;
        this.projectSignals = discovery.projectSignals();
        this.validationCandidates = discovery.profile().candidates().stream()
                .map(CodingVerificationCandidate::command)
                .toList();
        this.diagnostics = List.copyOf(safeDiagnostics);
        this.initialSnapshot = createSnapshot(projectResources);
    }

    Snapshot snapshot() {
        return initialSnapshot;
    }

    Snapshot snapshot(TrustedProjectResourceCatalog.Snapshot projectResources) {
        Objects.requireNonNull(projectResources, "projectResources must not be null");
        if (projectResources.generation() == initialSnapshot.generation()
                && projectResources.status() == initialSnapshot.instructionStatus()) {
            return initialSnapshot;
        }
        return createSnapshot(projectResources);
    }

    private Snapshot createSnapshot(TrustedProjectResourceCatalog.Snapshot projectResources) {
        Rendered rendered =
                render(environment, repositoryStatus, projectResources.status(), projectSignals, validationCandidates);
        return new Snapshot(
                projectResources.generation(),
                environment,
                repositoryStatus,
                projectResources.status(),
                projectSignals,
                validationCandidates,
                List.of(DYNAMIC_CAPABILITY_UNKNOWN),
                rendered.truncated(),
                diagnostics,
                rendered.promptBlock());
    }

    private static RepositoryStatus repositoryStatus(Path root, List<String> diagnostics) {
        Path git = root.resolve(".git");
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(git, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                diagnostics.add("git-root-marker:INVALID");
                return RepositoryStatus.INVALID;
            }
            if (attributes.isDirectory() || attributes.isRegularFile()) {
                return RepositoryStatus.PRESENT;
            }
            diagnostics.add("git-root-marker:INVALID");
            return RepositoryStatus.INVALID;
        } catch (NoSuchFileException ignored) {
            return RepositoryStatus.NOT_PRESENT;
        } catch (IOException | SecurityException ignored) {
            diagnostics.add("git-root-marker:UNKNOWN");
            return RepositoryStatus.UNKNOWN;
        }
    }

    private static Rendered render(
            EnvironmentFacts environment,
            RepositoryStatus repositoryStatus,
            TrustedProjectResourceCatalog.InstructionStatus instructionStatus,
            List<String> projectSignals,
            List<String> validationCandidates) {
        List<String> visibleSignals = new ArrayList<>(projectSignals);
        List<String> visibleCandidates = new ArrayList<>(validationCandidates);
        boolean truncated = false;
        while (true) {
            String prompt = prompt(
                    environment, repositoryStatus, instructionStatus, visibleSignals, visibleCandidates, truncated);
            if (prompt.getBytes(StandardCharsets.UTF_8).length <= MAXIMUM_PROMPT_BYTES) {
                return new Rendered(prompt, truncated);
            }
            truncated = true;
            if (!visibleCandidates.isEmpty()) {
                visibleCandidates.removeLast();
            } else if (!visibleSignals.isEmpty()) {
                visibleSignals.removeLast();
            } else {
                throw new IllegalStateException("workspace environment base prompt exceeds its fixed budget");
            }
        }
    }

    private static String prompt(
            EnvironmentFacts environment,
            RepositoryStatus repositoryStatus,
            TrustedProjectResourceCatalog.InstructionStatus instructionStatus,
            List<String> projectSignals,
            List<String> validationCandidates,
            boolean truncated) {
        String signals = joined(projectSignals, MAXIMUM_FACT_CHARACTERS);
        String candidates = joined(validationCandidates, MAXIMUM_CANDIDATE_CHARACTERS);
        return "\n\n<workspace_environment truncated=\""
                + truncated
                + "\">\n"
                + "  <workspace_root>.</workspace_root>\n"
                + "  <host os=\""
                + xml(environment.operatingSystem())
                + "\" version=\""
                + xml(environment.operatingSystemVersion())
                + "\" architecture=\""
                + xml(environment.architecture())
                + "\" java=\""
                + xml(environment.javaVersion())
                + "\" />\n"
                + "  <shell>"
                + xml(environment.shell())
                + "</shell>\n"
                + "  <execution enabled=\""
                + environment.executionEnabled()
                + "\" network=\""
                + environment.networkPolicy()
                + "\" workdir=\"workspace-relative\" default_timeout_millis=\""
                + environment.defaultTimeoutMillis()
                + "\" maximum_timeout_millis=\""
                + environment.maximumTimeoutMillis()
                + "\" />\n"
                + "  <permissions read=\"workspace\" write=\"workspace\" />\n"
                + "  <temporary_space>"
                + environment.temporarySpace().promptValue()
                + "</temporary_space>\n"
                + "  <repository git=\""
                + repositoryStatus
                + "\" dirty=\"NOT_PROBED\" />\n"
                + "  <instructions root_agents=\""
                + instructionStatus
                + "\" />\n"
                + "  <project_signals>"
                + signals
                + "</project_signals>\n"
                + "  <validation_candidates>"
                + candidates
                + "</validation_candidates>\n"
                + "  <unknowns>"
                + DYNAMIC_CAPABILITY_UNKNOWN
                + "</unknowns>\n"
                + "</workspace_environment>";
    }

    private static String joined(List<String> values, int maximumCharacters) {
        if (values.isEmpty()) return "NONE";
        return values.stream()
                .map(value -> bounded(value, maximumCharacters))
                .map(TrustedWorkspaceEnvironmentCatalog::xml)
                .reduce((left, right) -> left + "," + right)
                .orElse("NONE");
    }

    private static String bounded(String value, int maximumCharacters) {
        String safe = Objects.requireNonNull(value, "environment text must not be null")
                .replaceAll("[\\p{Cntrl}]", " ")
                .strip();
        if (safe.isEmpty()) return "unknown";
        if (safe.codePointCount(0, safe.length()) <= maximumCharacters) return safe;
        int end = safe.offsetByCodePoints(0, maximumCharacters - 1);
        return safe.substring(0, end) + "…";
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    enum RepositoryStatus {
        PRESENT,
        NOT_PRESENT,
        INVALID,
        UNKNOWN
    }

    enum NetworkPolicyFact {
        ALLOW,
        DENY,
        UNAVAILABLE
    }

    enum TemporarySpaceFact {
        SANDBOX_MANAGED("sandbox-managed"),
        UNAVAILABLE("unavailable");

        private final String promptValue;

        TemporarySpaceFact(String promptValue) {
            this.promptValue = promptValue;
        }

        String promptValue() {
            return promptValue;
        }
    }

    record EnvironmentFacts(
            String operatingSystem,
            String operatingSystemVersion,
            String architecture,
            String javaVersion,
            String shell,
            boolean executionEnabled,
            NetworkPolicyFact networkPolicy,
            String defaultTimeoutMillis,
            String maximumTimeoutMillis,
            TemporarySpaceFact temporarySpace) {
        EnvironmentFacts {
            operatingSystem = bounded(operatingSystem, MAXIMUM_FACT_CHARACTERS);
            operatingSystemVersion = bounded(operatingSystemVersion, MAXIMUM_FACT_CHARACTERS);
            architecture = bounded(architecture, MAXIMUM_FACT_CHARACTERS);
            javaVersion = bounded(javaVersion, MAXIMUM_FACT_CHARACTERS);
            shell = bounded(shell, MAXIMUM_FACT_CHARACTERS);
            networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy must not be null");
            defaultTimeoutMillis = bounded(defaultTimeoutMillis, MAXIMUM_FACT_CHARACTERS);
            maximumTimeoutMillis = bounded(maximumTimeoutMillis, MAXIMUM_FACT_CHARACTERS);
            temporarySpace = Objects.requireNonNull(temporarySpace, "temporarySpace must not be null");
            if (!executionEnabled
                    && (networkPolicy != NetworkPolicyFact.UNAVAILABLE
                            || temporarySpace != TemporarySpaceFact.UNAVAILABLE)) {
                throw new IllegalArgumentException("disabled execution must not advertise runtime capabilities");
            }
        }

        static EnvironmentFacts capture(
                String shell,
                boolean executionEnabled,
                String networkPolicy,
                Duration defaultTimeout,
                Duration maximumTimeout) {
            return new EnvironmentFacts(
                    System.getProperty("os.name", "unknown"),
                    System.getProperty("os.version", "unknown"),
                    System.getProperty("os.arch", "unknown"),
                    System.getProperty("java.version", "unknown"),
                    executionEnabled ? shell : "unavailable",
                    executionEnabled,
                    executionEnabled
                            ? NetworkPolicyFact.valueOf(networkPolicy.toUpperCase(Locale.ROOT))
                            : NetworkPolicyFact.UNAVAILABLE,
                    executionEnabled ? Long.toString(defaultTimeout.toMillis()) : "UNAVAILABLE",
                    executionEnabled ? Long.toString(maximumTimeout.toMillis()) : "UNAVAILABLE",
                    executionEnabled ? TemporarySpaceFact.SANDBOX_MANAGED : TemporarySpaceFact.UNAVAILABLE);
        }
    }

    record Snapshot(
            long generation,
            EnvironmentFacts environment,
            RepositoryStatus gitRepositoryStatus,
            TrustedProjectResourceCatalog.InstructionStatus instructionStatus,
            List<String> projectSignals,
            List<String> validationCandidates,
            List<String> unknowns,
            boolean truncated,
            List<String> diagnostics,
            String promptBlock) {
        Snapshot {
            environment = Objects.requireNonNull(environment, "environment must not be null");
            gitRepositoryStatus = Objects.requireNonNull(gitRepositoryStatus, "gitRepositoryStatus must not be null");
            instructionStatus = Objects.requireNonNull(instructionStatus, "instructionStatus must not be null");
            projectSignals = List.copyOf(projectSignals);
            validationCandidates = List.copyOf(validationCandidates);
            unknowns = List.copyOf(unknowns);
            diagnostics = List.copyOf(diagnostics);
            promptBlock = Objects.requireNonNull(promptBlock, "promptBlock must not be null");
        }
    }

    private record Rendered(String promptBlock, boolean truncated) {}
}
