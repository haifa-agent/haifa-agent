package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.display.BoundedText;
import io.haifa.agent.runtime.api.display.ToolDisplayBudget;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded Coding Terminal projection of one authoritative tool-call lifecycle fact.
 *
 * <p>This is an application adapter, not a second lifecycle: the authoritative status and reason code are
 * copied unchanged, and an indeterminate side-effecting result stays explicitly unknown. Display-only text is
 * bounded with the shared {@link BoundedText}/{@link ToolDisplayBudget} primitives; raw arguments, provider
 * payloads and full results never enter this projection.
 *
 * <p>{@link #status()} is the authoritative lifecycle status. {@link #displayStatus()} is the product-facing
 * status used for glyphs and transcript state; it collapses any indeterminate outcome to
 * {@link #OUTCOME_UNKNOWN_STATUS} so the UI can never render an unknown side-effecting result as an ordinary
 * success or failure, while the authoritative status stays in {@link #status()} for the body.
 */
public record ToolCallDisplayProjection(
        String toolCallId,
        String toolName,
        String status,
        boolean outcomeUnknown,
        Optional<String> reasonCode,
        String target,
        Optional<BoundedText> outputPreview,
        Optional<String> processState,
        Optional<Integer> exitCode,
        Optional<String> outputReference) {

    /** Runtime status emitted when a dispatched side-effecting tool has an indeterminate result. */
    public static final String OUTCOME_UNKNOWN_STATUS = "OUTCOME_UNKNOWN";

    /** Stable error wire code for the same indeterminate outcome. */
    public static final String TOOL_OUTCOME_UNKNOWN_CODE = "TOOL_OUTCOME_UNKNOWN";

    private static final int MAX_TOOL_CALL_ID_CHARS = 256;
    private static final int MAX_TOOL_NAME_CHARS = 128;
    private static final int MAX_STATUS_CHARS = 64;
    private static final int MAX_TARGET_CHARS = 256;
    private static final int MAX_PROCESS_STATE_CHARS = 64;
    private static final int MAX_REFERENCE_CHARS = 512;

    public ToolCallDisplayProjection {
        toolCallId = requireText(toolCallId, "toolCallId", MAX_TOOL_CALL_ID_CHARS);
        toolName = requireText(toolName, "toolName", MAX_TOOL_NAME_CHARS);
        status = requireText(status, "status", MAX_STATUS_CHARS);
        reasonCode = optionalText(reasonCode, "reasonCode", 128).filter(value -> !"NONE".equalsIgnoreCase(value));
        target = boundedTarget(Objects.requireNonNull(target, "target must not be null"));
        outputPreview = Objects.requireNonNull(outputPreview, "outputPreview must not be null");
        processState = optionalText(processState, "processState", MAX_PROCESS_STATE_CHARS);
        exitCode = Objects.requireNonNull(exitCode, "exitCode must not be null");
        outputReference = optionalText(outputReference, "outputReference", MAX_REFERENCE_CHARS);
    }

    /** Projects the bounded observation the Runtime event already carries, preserving its size facts. */
    public static ToolCallDisplayProjection from(RunEventPayloads.ToolLifecycle lifecycle, ToolDisplayBudget budget) {
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        RunEventPayloads.ToolObservation observation = lifecycle.observation().orElse(null);
        String reasonCode = lifecycle.reasonCode();
        return new ToolCallDisplayProjection(
                lifecycle.toolCallId(),
                lifecycle.displayName(),
                lifecycle.status(),
                isOutcomeUnknown(lifecycle.status(), reasonCode),
                Optional.of(reasonCode),
                lifecycle.targetSummary(),
                observation == null ? Optional.empty() : observation.outputPreview(),
                observation == null ? Optional.empty() : observation.processState(),
                observation == null ? Optional.empty() : observation.exitCode(),
                Optional.of(lifecycle.resultRef()));
    }

    /**
     * Projects the lifecycle plus an optional unbounded output summary when the caller already holds one.
     * The summary is only ever copied as a bounded display preview.
     */
    public static ToolCallDisplayProjection from(
            RunEventPayloads.ToolLifecycle lifecycle, Optional<String> outputSummary, ToolDisplayBudget budget) {
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(outputSummary, "outputSummary must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        String reasonCode = lifecycle.reasonCode();
        return new ToolCallDisplayProjection(
                lifecycle.toolCallId(),
                lifecycle.displayName(),
                lifecycle.status(),
                isOutcomeUnknown(lifecycle.status(), reasonCode),
                Optional.of(reasonCode),
                lifecycle.targetSummary(),
                outputSummary.filter(value -> !value.isBlank()).map(value -> BoundedText.of(value, budget)),
                Optional.empty(),
                Optional.empty(),
                Optional.of(lifecycle.resultRef()));
    }

    /** Whether the authoritative status or reason code marks an indeterminate side-effecting outcome. */
    public static boolean isOutcomeUnknown(String status, String reasonCode) {
        return OUTCOME_UNKNOWN_STATUS.equalsIgnoreCase(status)
                || TOOL_OUTCOME_UNKNOWN_CODE.equalsIgnoreCase(reasonCode);
    }

    /**
     * Product-facing status for glyphs and transcript state. An indeterminate side-effecting result is always
     * shown as {@link #OUTCOME_UNKNOWN_STATUS}, even when the authoritative status is {@code FAILED}.
     */
    public String displayStatus() {
        return outcomeUnknown ? OUTCOME_UNKNOWN_STATUS : status;
    }

    private static String boundedTarget(String value) {
        String target = value.strip();
        if (target.length() <= MAX_TARGET_CHARS) {
            return target;
        }
        int end = MAX_TARGET_CHARS - 1;
        if (end > 0
                && end < target.length()
                && Character.isLowSurrogate(target.charAt(end))
                && Character.isHighSurrogate(target.charAt(end - 1))) {
            end--;
        }
        return target.substring(0, end) + "…";
    }

    private static String requireText(String value, String field, int maximum) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }

    private static Optional<String> optionalText(Optional<String> value, String field, int maximum) {
        return Objects.requireNonNull(value, field + " must not be null")
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .map(item -> requireText(item, field, maximum));
    }
}
