package io.haifa.agent.testing.delivery;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The planned 23-case autonomous-delivery capability ladder. */
public final class AutonomousDeliveryLadderCatalog {
    private static final List<AutonomousDeliveryLadderCase> CASES = List.of(
            new AutonomousDeliveryLadderCase(
                    "L1-01",
                    LadderLevel.L1,
                    "Fix off-by-one page window slicing",
                    "The bundled mini-project computes paginated result windows in pagination.py. "
                            + "PageWindow.slice(items, page, size) returns one item too many when the last page "
                            + "is partially filled. A failing unit test is provided; fix the single function so "
                            + "all tests pass, modifying only pagination.py.",
                    1,
                    1,
                    1,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L1-02",
                    LadderLevel.L1,
                    "Fix century leap-year rule",
                    "Date utility dates.py misclassifies century years: 2000 must be a leap year while 1900 "
                            + "must not. Fix the rule so the provided unit tests pass.",
                    1,
                    1,
                    1,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L1-03",
                    LadderLevel.L1,
                    "Recover slugify edge cases from a red suite",
                    "The workspace contains slugify.py and a unit test suite that currently fails on hyphen "
                            + "runs and leading or trailing separators. A previous fix attempt is already committed "
                            + "and still red. Read the failing output, correct the implementation, and iterate "
                            + "until green; the first attempt is not expected to be the final one.",
                    2,
                    1,
                    2,
                    List.of(LadderVariant.ERROR_RECOVERY)),
            new AutonomousDeliveryLadderCase(
                    "L1-04",
                    LadderLevel.L1,
                    "Fix integer division in conversion rate",
                    "metrics.py computes conversion_rate with integer division so 2 of 3 renders as 0%. Fix "
                            + "the calculation to return the ratio rounded to two decimals and keep existing "
                            + "callers working.",
                    1,
                    1,
                    1,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L1-05",
                    LadderLevel.L1,
                    "Make record search case-insensitive",
                    "matches(query, records) in search.py is case-sensitive while the product spec requires "
                            + "case-insensitive matching for ASCII input. Update the single comparison point so "
                            + "the provided tests pass.",
                    1,
                    1,
                    1,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L2-01",
                    LadderLevel.L2,
                    "Thread verbose flag through config to CLI",
                    "Add a --verbose flag end to end to the mini CLI: parse it in cli.py, thread it through "
                            + "config.py into report.py, and emit per-step log lines when enabled. Three adjacent "
                            + "files change; acceptance runs the CLI and checks output with and without the flag.",
                    1,
                    3,
                    2,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L2-02",
                    LadderLevel.L2,
                    "Add cancelled status to order state machine",
                    "Introduce a CANCELLED order status in models.py, reject transitions into it from "
                            + "FINISHED in state_machine.py, and render it in the summary renderer views.py. "
                            + "Acceptance covers the new transitions and rendering.",
                    1,
                    3,
                    2,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L2-03",
                    LadderLevel.L2,
                    "Propagate optional coupon field through service",
                    "Thread an optional coupon_code field from the request DTO through order_service.py into "
                            + "the JSON serializer serializers.py; omitted means null end to end, and the "
                            + "provided API tests must still pass.",
                    1,
                    3,
                    2,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L2-04",
                    LadderLevel.L2,
                    "Add CSV export beside existing JSON export",
                    "The report exporter currently writes JSON only. Add CSV export beside it: register a csv "
                            + "format in exporters.py, implement csv_exporter.py, and wire --format csv in "
                            + "cli.py. Existing JSON export must keep identical output.",
                    2,
                    3,
                    2,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L2-05",
                    LadderLevel.L2,
                    "Add page size while preserving default ordering",
                    "Add a page_size request parameter to listing.py and repository.py supporting 1..100 with "
                            + "default 20. An unannounced contract test asserts the default listing order and "
                            + "shape are unchanged; preserve them.",
                    1,
                    3,
                    3,
                    List.of(LadderVariant.REGRESSION_PROTECTION)),
            new AutonomousDeliveryLadderCase(
                    "L3-01",
                    LadderLevel.L3,
                    "Diagnose settings ignored on Windows paths",
                    "Symptom: on Windows, values from settings.ini are silently ignored whenever the project "
                            + "path contains a backslash; the same configuration loads fine on Linux. No file "
                            + "paths are given. Locate the root cause in the bundled mini-project and fix it "
                            + "with a minimal change.",
                    4,
                    1,
                    1,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L3-02",
                    LadderLevel.L3,
                    "Recover report generator from stack trace",
                    "Symptom: report generation crashes with an AttributeError on a None value, as shown in "
                            + "the attached stack trace. Find the failing component without any file hints, add "
                            + "the missing guard consistent with existing fallback behavior, and make the suite "
                            + "green.",
                    4,
                    1,
                    2,
                    List.of(LadderVariant.ERROR_RECOVERY)),
            new AutonomousDeliveryLadderCase(
                    "L3-03",
                    LadderLevel.L3,
                    "Diagnose duplicate nightly aggregation entries",
                    "Symptom: the nightly aggregation in the mini-project sometimes counts the same "
                            + "transaction twice around midnight. Diagnose the boundary condition from the "
                            + "symptom description and sample data alone, then fix it with the smallest change "
                            + "the acceptance script confirms.",
                    4,
                    1,
                    2,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L3-04",
                    LadderLevel.L3,
                    "Diagnose batch import hang on large input",
                    "Symptom: importing more than roughly ten thousand rows makes the batch importer appear "
                            + "to hang while CPU stays busy. Identify the algorithmic cause (quadratic membership "
                            + "checks) and fix it so the acceptance run completes within its timeout without "
                            + "changing import results.",
                    3,
                    1,
                    2,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L4-01",
                    LadderLevel.L4,
                    "Propagate priority field across all layers",
                    "Add a priority field to the task domain across four layers: JSON schema task_api.yaml, "
                            + "core model task.py, adapter serializer json_adapter.py, and the SQLite projection "
                            + "store.py. Acceptance writes through the API surface and verifies the field "
                            + "round-trips through every layer and appears in the projection.",
                    1,
                    4,
                    3,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L4-02",
                    LadderLevel.L4,
                    "Introduce new event type through pipeline",
                    "Introduce a JOB_RETRYING event type through the event pipeline: extend the event enum, "
                            + "register it in the dispatcher registry, and include it in the JSONL projection. "
                            + "Acceptance emits the new event through the public entry point and checks every "
                            + "projection and registry contains it.",
                    2,
                    4,
                    3,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L4-03",
                    LadderLevel.L4,
                    "Add validated tool parameter across boundaries",
                    "Add a validated max_retries parameter (0..5, default 3) to the send_notification tool: "
                            + "update the tool definition, the runtime argument validator, and the adapter DTO "
                            + "so the parameter is enforced and serialized consistently at each boundary.",
                    1,
                    4,
                    3,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L4-04",
                    LadderLevel.L4,
                    "Extend status API without breaking consumers",
                    "Extend the job status API with a machine-readable reason_code while keeping the existing "
                            + "response shape compatible for old consumers. A hidden contract test replays "
                            + "recorded responses from the previous version and must still pass unchanged.",
                    2,
                    4,
                    4,
                    List.of(LadderVariant.REGRESSION_PROTECTION)),
            new AutonomousDeliveryLadderCase(
                    "L5-01",
                    LadderLevel.L5,
                    "Validate emails without new public types",
                    "Add format validation for user-supplied email addresses in accounts.py with the strict "
                            + "constraint: no new public types and no new modules, only edits inside the existing "
                            + "file. Acceptance checks behavior and inspects the diff to enforce the constraint.",
                    2,
                    2,
                    4,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L5-02",
                    LadderLevel.L5,
                    "Speed up dedup using standard library only",
                    "Reduce duplicate-detection latency in dedup.py using only the Python standard library; "
                            + "adding any third-party dependency or new module fails acceptance. Behavior must "
                            + "be identical, and the acceptance run must finish inside its time budget.",
                    3,
                    2,
                    4,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L5-03",
                    LadderLevel.L5,
                    "Rename calculation entry point backward compatibly",
                    "Rename the poorly named calc(x, y) to calculate(total, discount) while keeping calc "
                            + "working unchanged for existing callers. Acceptance calls both signatures; the old "
                            + "name must remain a working alias, not a copy-paste duplicate.",
                    2,
                    2,
                    4,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L6-01",
                    LadderLevel.L6,
                    "Open issue: bulk inventory import from CSV",
                    "Issue: as a maintainer I want the inventory tool to accept a CSV file with columns sku, "
                            + "name, quantity and import it into the store, reporting a per-row success and error "
                            + "summary and exiting non-zero when any row fails. No file hints and no design "
                            + "guidance: plan, implement, test, and deliver the feature in the bundled "
                            + "mini-project.",
                    3,
                    5,
                    4,
                    List.of()),
            new AutonomousDeliveryLadderCase(
                    "L6-02",
                    LadderLevel.L6,
                    "Open issue: resumable batch export",
                    "Issue: long exports are lost when the process is interrupted; make the batch export "
                            + "resumable so a re-run continues from the last completed batch instead of starting "
                            + "over. Deliver the full feature autonomously; acceptance simulates an interruption "
                            + "after the first batch and verifies the resumed run completes without duplicates "
                            + "or loss.",
                    4,
                    5,
                    5,
                    List.of()));

    private AutonomousDeliveryLadderCatalog() {}

    static {
        validate();
    }

    public static List<AutonomousDeliveryLadderCase> cases() {
        return CASES;
    }

    public static AutonomousDeliveryLadderCase require(String caseId) {
        return CASES.stream()
                .filter(ladderCase -> ladderCase.caseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown ladder case: " + caseId));
    }

    public static Map<LadderLevel, List<AutonomousDeliveryLadderCase>> byLevel() {
        Map<LadderLevel, List<AutonomousDeliveryLadderCase>> grouped = new EnumMap<>(LadderLevel.class);
        for (LadderLevel level : LadderLevel.values()) {
            grouped.put(
                    level,
                    CASES.stream()
                            .filter(ladderCase -> ladderCase.level() == level)
                            .toList());
        }
        return Map.copyOf(grouped);
    }

    private static void validate() {
        if (CASES.size() != 23) {
            throw new IllegalStateException("ladder must contain exactly 23 cases: " + CASES.size());
        }
        if (CASES.stream().map(AutonomousDeliveryLadderCase::caseId).distinct().count() != CASES.size()) {
            throw new IllegalStateException("ladder case ids must be unique");
        }
        Map<LadderLevel, List<AutonomousDeliveryLadderCase>> grouped = byLevel();
        for (LadderLevel level : LadderLevel.values()) {
            List<AutonomousDeliveryLadderCase> levelCases = grouped.get(level);
            if (levelCases.size() != level.plannedCases()) {
                throw new IllegalStateException(level + " must contain exactly " + level.plannedCases() + " cases");
            }
            for (int index = 0; index < levelCases.size(); index++) {
                String expectedId = level.prefix() + "%02d".formatted(index + 1);
                if (!levelCases.get(index).caseId().equals(expectedId)) {
                    throw new IllegalStateException("expected ladder case " + expectedId);
                }
            }
        }
    }
}
