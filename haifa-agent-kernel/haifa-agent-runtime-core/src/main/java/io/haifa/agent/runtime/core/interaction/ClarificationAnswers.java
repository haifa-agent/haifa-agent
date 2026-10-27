package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.runtime.api.ClarificationPresentation;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionOption;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decoded answer set of one clarification response.
 *
 * <p>Wire format: one {@link TextPart} per question, ordered by question index, with format
 * {@value #PART_FORMAT} and two lines:
 *
 * <pre>
 * selected=opt-0-0,opt-0-2
 * custom=free text answer
 * </pre>
 */
public record ClarificationAnswers(List<List<String>> answers, boolean declined) {

    public static final String PART_FORMAT = "clarification-answer.v1";
    private static final String SELECTED_PREFIX = "selected=";
    private static final String CUSTOM_PREFIX = "custom=";

    public ClarificationAnswers {
        answers = List.copyOf(Objects.requireNonNull(answers, "answers must not be null"));
    }

    public static ClarificationAnswers declined(int questionCount) {
        List<List<String>> empty = new ArrayList<>();
        for (int index = 0; index < questionCount; index++) empty.add(List.of());
        return new ClarificationAnswers(empty, true);
    }

    /**
     * Decodes and validates the submitted parts against the persisted request.
     *
     * @throws RuntimeContractException with {@code INTERACTION_INPUT_INVALID} when the payload cannot be applied
     */
    public static ClarificationAnswers parse(List<ContentPart> inputs, InteractionRequest request) {
        Objects.requireNonNull(inputs, "inputs must not be null");
        ClarificationPresentation presentation = request.clarificationPresentation()
                .orElseThrow(() -> invalid("The interaction does not accept clarification answers"));
        List<InteractionInputContract> contracts = contracts(request);
        if (inputs.size() != presentation.questions().size()) {
            throw invalid("One answer is required per question");
        }
        List<List<String>> answers = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            answers.add(answer(inputs.get(index), presentation.questions().get(index), contracts.get(index), index));
        }
        return new ClarificationAnswers(answers, false);
    }

    public String modelText(InteractionRequest request) {
        List<ClarificationPresentation.Question> questions =
                request.clarificationPresentation().orElseThrow().questions();
        if (declined) {
            StringBuilder text = new StringBuilder("User dismissed the questions without answering: ");
            for (int index = 0; index < questions.size(); index++) {
                if (index > 0) text.append(", ");
                text.append('"').append(questions.get(index).question()).append('"');
            }
            return text.append(". Continue with the available information, or stop and explain what you still need.")
                    .toString();
        }
        StringBuilder text = new StringBuilder("User has answered your questions: ");
        for (int index = 0; index < questions.size(); index++) {
            if (index > 0) text.append(", ");
            List<String> answer = answers.get(index);
            text.append('"')
                    .append(questions.get(index).question())
                    .append("\"=\"")
                    .append(answer.isEmpty() ? "Unanswered" : String.join(", ", answer))
                    .append('"');
        }
        return text.append(". You can now continue with the user's answers in mind.")
                .toString();
    }

    public Map<String, Object> structuredData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answers", answers);
        data.put("declined", declined);
        return data;
    }

    private static List<String> answer(
            ContentPart part,
            ClarificationPresentation.Question question,
            InteractionInputContract contract,
            int index) {
        if (!(part instanceof TextPart text) || !PART_FORMAT.equals(text.format())) {
            throw invalid("Answer " + index + " must use format " + PART_FORMAT);
        }
        String[] lines = text.text().split("\n", -1);
        if (lines.length != 2 || !lines[0].startsWith(SELECTED_PREFIX) || !lines[1].startsWith(CUSTOM_PREFIX)) {
            throw invalid("Answer " + index + " must contain one selected line and one custom line");
        }
        LinkedHashSet<String> selected = selected(lines[0].substring(SELECTED_PREFIX.length()));
        String custom = lines[1].substring(CUSTOM_PREFIX.length()).trim();
        List<InteractionOption> options = contract.options();
        List<String> labels = new ArrayList<>();
        for (InteractionOption option : options) {
            if (selected.contains(option.id())) labels.add(option.label());
        }
        if (labels.size() != selected.size()) {
            throw invalid("Answer " + index + " contains an unknown option");
        }
        if (contract.maximumSelections() == 1 && labels.size() > 1) {
            throw invalid("Answer " + index + " accepts a single option");
        }
        if (labels.size() > contract.maximumSelections()) {
            throw invalid("Answer " + index + " exceeds the selection limit");
        }
        if (contract.maximumCharacters() == 0 && !custom.isEmpty()) {
            throw invalid("Answer " + index + " does not accept a free-text answer");
        }
        if (custom.length() > contract.maximumCharacters()) {
            throw invalid("Answer " + index + " exceeds the free-text limit");
        }
        if (!custom.isEmpty()) labels.add(custom);
        if (labels.isEmpty()) throw invalid("Answer " + index + " is empty");
        return labels;
    }

    private static LinkedHashSet<String> selected(String value) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String candidate : value.split(",", -1)) {
            String id = candidate.trim();
            if (id.isEmpty()) continue;
            if (!id.matches("[a-z][a-z0-9-]*")) throw invalid("Selected option id is malformed");
            selected.add(id);
        }
        return selected;
    }

    private static List<InteractionInputContract> contracts(InteractionRequest request) {
        if (request.inputContracts().isEmpty()) {
            throw invalid("The interaction does not accept clarification answers");
        }
        return request.inputContracts();
    }

    private static RuntimeContractException invalid(String message) {
        return new RuntimeContractException(RuntimeApiErrorCode.INTERACTION_INPUT_INVALID, message);
    }
}
