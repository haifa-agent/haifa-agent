package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.api.ClarificationPresentation;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validated request shape of one {@code ask_clarification} call.
 *
 * <p>It is built once from the model arguments and then copied onto the immutable interaction request, so every
 * reader (projection, answer validation, recovery) works from persisted data instead of re-parsing JSON.
 */
public record ClarificationSpec(
        ToolCallId toolCallId, List<InteractionInputContract> contracts, ClarificationPresentation presentation) {

    public static final String TOOL_NAME = "ask_clarification";
    public static final String INTERACTION_TYPE = "clarification";
    public static final int MAX_QUESTIONS = 3;
    public static final int MAX_CUSTOM_CHARACTERS = 2_000;
    private static final int MAX_QUESTION_CHARACTERS = 2_000;
    private static final int MAX_HEADER_CHARACTERS = 32;
    private static final int MAX_LABEL_CHARACTERS = 120;
    private static final int MAX_DESCRIPTION_CHARACTERS = 300;
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 6;

    public ClarificationSpec {
        Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        contracts = List.copyOf(Objects.requireNonNull(contracts, "contracts must not be null"));
        presentation = Objects.requireNonNull(presentation, "presentation must not be null");
        if (contracts.size() != presentation.questions().size()) {
            throw new IllegalArgumentException("one input contract is required per question");
        }
        presentation.requireMatchingContracts(contracts);
    }

    public static boolean isClarificationTool(String toolName) {
        return TOOL_NAME.equals(toolName);
    }

    public static boolean isClarificationRequest(String toolName, Map<String, Object> arguments) {
        return TOOL_NAME.equals(toolName) && arguments != null && arguments.containsKey("questions");
    }

    /**
     * Parses and validates one tool argument map.
     *
     * @throws IllegalArgumentException when the arguments cannot produce a bounded clarification request
     */
    public static ClarificationSpec from(ToolCallId toolCallId, Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        List<?> rawQuestions = requireList(arguments.get("questions"), "questions");
        if (rawQuestions.isEmpty() || rawQuestions.size() > MAX_QUESTIONS) {
            throw new IllegalArgumentException("questions must contain 1.." + MAX_QUESTIONS + " entries");
        }
        List<ClarificationPresentation.Question> questions = new ArrayList<>();
        List<InteractionInputContract> contracts = new ArrayList<>();
        for (int index = 0; index < rawQuestions.size(); index++) {
            String field = "questions[" + index + "]";
            Map<?, ?> raw = requireMap(rawQuestions.get(index), field);
            String header = requireText(raw.get("header"), field + ".header", MAX_HEADER_CHARACTERS);
            String question = requireText(raw.get("question"), field + ".question", MAX_QUESTION_CHARACTERS);
            boolean multiple = flag(raw.get("multiple"), false);
            boolean allowCustom = flag(raw.get("custom"), true);
            List<?> rawOptions = requireList(raw.get("options"), field + ".options");
            if (rawOptions.size() < MIN_OPTIONS || rawOptions.size() > MAX_OPTIONS) {
                throw new IllegalArgumentException(
                        field + ".options must contain " + MIN_OPTIONS + ".." + MAX_OPTIONS + " entries");
            }
            List<InteractionOption> options = new ArrayList<>();
            List<ClarificationPresentation.Option> presented = new ArrayList<>();
            HashSet<String> labels = new HashSet<>();
            for (int optionIndex = 0; optionIndex < rawOptions.size(); optionIndex++) {
                String optionField = field + ".options[" + optionIndex + "]";
                Map<?, ?> rawOption = requireMap(rawOptions.get(optionIndex), optionField);
                String label = requireText(rawOption.get("label"), optionField + ".label", MAX_LABEL_CHARACTERS);
                if (!labels.add(label)) {
                    throw new IllegalArgumentException(optionField + ".label must be unique within its question");
                }
                String optionId = "opt-" + index + "-" + optionIndex;
                String description = optionalText(
                        rawOption.get("description"), optionField + ".description", MAX_DESCRIPTION_CHARACTERS);
                options.add(new InteractionOption(optionId, label));
                presented.add(new ClarificationPresentation.Option(
                        optionId, label, java.util.Optional.ofNullable(description)));
            }
            int maximumCustom = allowCustom ? MAX_CUSTOM_CHARACTERS : 0;
            contracts.add(
                    multiple
                            ? InteractionInputContract.multiChoice(options, 0, options.size(), maximumCustom)
                            : InteractionInputContract.singleChoice(options, maximumCustom));
            questions.add(new ClarificationPresentation.Question(header, question, multiple, allowCustom, presented));
        }
        return new ClarificationSpec(toolCallId, contracts, new ClarificationPresentation(title(questions), questions));
    }

    public ClarificationTarget target() {
        return new ClarificationTarget(toolCallId);
    }

    /** Model-facing safe prompt used as the interaction summary; never contains the raw argument blob. */
    public String prompt() {
        StringBuilder prompt =
                new StringBuilder("Answer ").append(contracts.size()).append(" question");
        if (contracts.size() > 1) prompt.append('s');
        for (ClarificationPresentation.Question question : presentation.questions()) {
            prompt.append(" · ").append(question.header());
        }
        return prompt.toString();
    }

    private static String title(List<ClarificationPresentation.Question> questions) {
        if (questions.size() == 1) return questions.get(0).header();
        StringBuilder title = new StringBuilder();
        for (ClarificationPresentation.Question question : questions) {
            if (title.length() > 0) title.append(" · ");
            title.append(question.header());
        }
        return title.toString();
    }

    private static List<?> requireList(Object value, String field) {
        if (value instanceof List<?> list) return list;
        throw new IllegalArgumentException(field + " must be an array");
    }

    private static Map<?, ?> requireMap(Object value, String field) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException(field + " must be an object");
    }

    private static String requireText(Object value, String field, int maximumLength) {
        if (!(value instanceof String text)) throw new IllegalArgumentException(field + " must be a string");
        String normalized = text.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain at most " + maximumLength + " characters");
        }
        return normalized;
    }

    private static String optionalText(Object value, String field, int maximumLength) {
        if (value == null) return null;
        return requireText(value, field, maximumLength);
    }

    private static boolean flag(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean flag) return flag;
        throw new IllegalArgumentException("boolean flag expected");
    }
}
