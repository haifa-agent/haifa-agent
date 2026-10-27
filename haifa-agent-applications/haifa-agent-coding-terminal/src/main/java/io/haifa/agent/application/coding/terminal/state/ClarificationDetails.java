package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.runtime.api.ClarificationPresentation;
import io.haifa.agent.runtime.api.InteractionView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Terminal projection of a clarification interaction.
 *
 * <p>It renders through the ordinary interaction card so the terminal needs no second card component, and it owns
 * the answer draft so the selector stays the single input surface while the Runtime receives one submitted payload
 * with one part per question.
 */
public record ClarificationDetails(String title, List<Question> questions) {

    /** The answer wire format shared with the Runtime tool. */
    public static final String ANSWER_FORMAT = "clarification-answer.v1";

    public static final String CUSTOM_ENTRY_LABEL = "Type your own answer";
    public static final String DONE_LABEL = "Done with this question";
    public static final String SUBMIT_LABEL = "Send answer";
    public static final String CANCEL_LABEL = "Cancel";

    public ClarificationDetails {
        Objects.requireNonNull(title, "title must not be null");
        questions = List.copyOf(Objects.requireNonNull(questions, "questions must not be null"));
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("questions must not be empty");
        }
    }

    /** One question as the terminal renders and answers it. */
    public record Question(String header, String prompt, boolean multiple, boolean allowCustom, List<Option> options) {

        public Question {
            Objects.requireNonNull(header, "header must not be null");
            Objects.requireNonNull(prompt, "prompt must not be null");
            options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
        }

        /** Whether the given option identifiers all belong to this question. */
        public boolean accepts(List<String> optionIds) {
            return optionIds.stream().allMatch(id -> options.stream()
                    .anyMatch(option -> option.optionId().equals(id)));
        }

        String content() {
            StringBuilder text = new StringBuilder(prompt);
            for (Option option : options) {
                text.append("\n- ").append(option.label());
                option.description().ifPresent(value -> text.append(" — ").append(value));
            }
            if (allowCustom) {
                text.append("\n- ").append(CUSTOM_ENTRY_LABEL);
            }
            if (multiple) {
                text.append("\n(choose one or more)");
            }
            return text.toString();
        }
    }

    /** One option of a question. */
    public record Option(String optionId, String label, Optional<String> description) {

        public Option {
            Objects.requireNonNull(optionId, "optionId must not be null");
            Objects.requireNonNull(label, "label must not be null");
            description = description == null ? Optional.empty() : description;
        }
    }

    public static Optional<ClarificationDetails> from(InteractionView view) {
        Objects.requireNonNull(view, "view must not be null");
        return view.clarificationPresentation()
                .map(presentation -> new ClarificationDetails(
                        presentation.title(),
                        presentation.questions().stream()
                                .map(ClarificationDetails::question)
                                .toList()));
    }

    private static Question question(ClarificationPresentation.Question question) {
        List<Option> options = question.options().stream()
                .map(option -> new Option(option.optionId(), option.label(), option.description()))
                .toList();
        return new Question(
                question.header(), question.question(), question.multiple(), question.allowCustom(), options);
    }

    /** The interaction card projection; the card body lists every question with its options. */
    public ApprovalDetails toApprovalDetails() {
        StringBuilder content = new StringBuilder();
        for (Question question : questions) {
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append(question.header()).append(": ").append(question.content());
        }
        return new ApprovalDetails(
                title,
                "The agent needs an answer before it can continue",
                "text/plain",
                content.toString(),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of());
    }

    /**
     * Encodes the draft as one part per question.
     *
     * @param selected the selected option identifiers per question, in question order
     * @param custom the free-text answer per question, blank when the user did not type one
     */
    public static List<ContentPart> answerParts(List<List<String>> selected, List<String> custom) {
        List<ContentPart> parts = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            String free = index < custom.size() && custom.get(index) != null ? custom.get(index) : "";
            parts.add(new TextPart(
                    "selected=" + String.join(",", selected.get(index)) + "\ncustom=" + free.trim(), ANSWER_FORMAT));
        }
        return List.copyOf(parts);
    }
}
