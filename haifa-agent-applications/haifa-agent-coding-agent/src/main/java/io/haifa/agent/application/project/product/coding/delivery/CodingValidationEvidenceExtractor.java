package io.haifa.agent.application.project.product.coding.delivery;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small deterministic extractor for explicit test-runner summaries; it never infers counts from exit code alone. */
public final class CodingValidationEvidenceExtractor {
    private static final Pattern PYTEST_COUNT = Pattern.compile(
            "(?<![A-Za-z])(\\d+)\\s+(passed|failed|error|errors|skipped|xfailed|xpassed|deselected)(?![A-Za-z])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUREFIRE = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CARGO = Pattern.compile(
            "test result:\\s*(?:ok|FAILED)\\.\\s*(\\d+) passed;\\s*(\\d+) failed;\\s*(\\d+) ignored;",
            Pattern.CASE_INSENSITIVE);

    private CodingValidationEvidenceExtractor() {}

    public static Optional<CodingValidationAttemptEvidence> extract(
            String operationFamily, boolean successful, String boundedOutput, boolean truncated) {
        if (!"TEST".equals(operationFamily) && !"BUILD".equals(operationFamily)) return Optional.empty();
        CodingValidationStatus status = successful ? CodingValidationStatus.PASSED : CodingValidationStatus.FAILED;
        if (!truncated && "TEST".equals(operationFamily)) {
            Optional<Counts> counts =
                    pytest(boundedOutput).or(() -> surefire(boundedOutput)).or(() -> cargo(boundedOutput));
            if (counts.isPresent()) {
                Counts value = counts.orElseThrow();
                CodingValidationScope scope = value.selected() == value.discovered() && value.ignored() == 0
                        ? CodingValidationScope.FULL
                        : CodingValidationScope.SELECTED;
                String claimCode = value.selected() == 1
                        ? "SELECTED_TESTS_ONLY"
                        : scope == CodingValidationScope.FULL ? "DISCOVERED_TESTS_SELECTED" : "PARTIAL_TEST_SELECTION";
                return Optional.of(new CodingValidationAttemptEvidence(
                        CodingValidationAttemptEvidence.SCHEMA_VERSION,
                        status,
                        value.discovered(),
                        value.selected(),
                        value.ignored(),
                        scope,
                        value.source(),
                        claimCode));
            }
        }
        return Optional.of(new CodingValidationAttemptEvidence(
                CodingValidationAttemptEvidence.SCHEMA_VERSION,
                status,
                null,
                null,
                null,
                CodingValidationScope.UNKNOWN,
                truncated ? "TRUNCATED_OUTPUT" : "COUNTS_UNAVAILABLE",
                "TEST_COUNTS_UNAVAILABLE"));
    }

    private static Optional<Counts> pytest(String output) {
        String normalized = output == null ? "" : output.toLowerCase(Locale.ROOT);
        String summary = null;
        for (String line : normalized.lines().toList()) {
            if ((!line.contains(" in ") && !line.contains("pytest"))
                    || !PYTEST_COUNT.matcher(line).find()) continue;
            if (summary != null) return Optional.empty();
            summary = line;
        }
        if (summary == null) return Optional.empty();
        Matcher matcher = PYTEST_COUNT.matcher(summary);
        int selected = 0;
        int deselected = 0;
        int ignored = 0;
        boolean observed = false;
        while (matcher.find()) {
            observed = true;
            int count = Integer.parseInt(matcher.group(1));
            String kind = matcher.group(2);
            if (kind.equals("deselected")) {
                deselected += count;
            } else {
                selected += count;
                if (kind.equals("skipped")) ignored += count;
            }
        }
        if (!observed) return Optional.empty();
        return Optional.of(new Counts(selected + deselected, selected, ignored + deselected, "PYTEST_SUMMARY"));
    }

    private static Optional<Counts> surefire(String output) {
        Matcher matcher = SUREFIRE.matcher(output == null ? "" : output);
        if (!matcher.find()) return Optional.empty();
        int run = Integer.parseInt(matcher.group(1));
        int skipped = Integer.parseInt(matcher.group(4));
        if (matcher.find()) return Optional.empty(); // Multiple summaries may contain overlapping reactor totals.
        return Optional.of(new Counts(run, run, skipped, "SUREFIRE_SUMMARY"));
    }

    private static Optional<Counts> cargo(String output) {
        Matcher matcher = CARGO.matcher(output == null ? "" : output);
        int passed = 0;
        int failed = 0;
        int ignored = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            passed += Integer.parseInt(matcher.group(1));
            failed += Integer.parseInt(matcher.group(2));
            ignored += Integer.parseInt(matcher.group(3));
        }
        if (!found) return Optional.empty();
        int selected = passed + failed + ignored;
        return Optional.of(new Counts(selected, selected, ignored, "CARGO_TEST_SUMMARY"));
    }

    private record Counts(int discovered, int selected, int ignored, String source) {}
}
