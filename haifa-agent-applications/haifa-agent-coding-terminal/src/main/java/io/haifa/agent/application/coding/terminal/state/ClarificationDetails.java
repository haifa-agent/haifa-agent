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
    /** Option rationales the selector renders under its own rows, so every row explains what Enter will do. */
    public static final String CUSTOM_ENTRY_DESCRIPTION =
            "Answer this question with your own text instead of one of the options";

    public static final String DONE_LABEL = "Done with this question";
    public static final String DONE_DESCRIPTION = "Mark the answers and open the next question";

    public static final String SUBMIT_LABEL = "Send answer";
    public static final String SUBMIT_DESCRIPTION = "Send every marked answer and resume the run";

    /** U+2713 CHECK MARK: a narrow, non-emoji glyph, so marked rows keep their column alignment. */
    public static final String MARKER_SELECTED = "[\u2713]";

    public static final String MARKER_UNSELECTED = "[ ]";

    /** A single-choice question marks its answer with parentheses, so the shape states the question type. */
    public static final String MARKER_SINGLE_SELECTED = "(\u2713)";

    public static final String MARKER_SINGLE_UNSELECTED = "( )";

    public static final String CANCEL_LABEL = "Cancel";
    public static final String CANCEL_DESCRIPTION = "Leave the questions unanswered and resume the run";

    /** The marker for one option row; both brackets keep the three columns the selector list depends on. */
    public static String marker(boolean multiple, boolean selected) {
        if (multiple) {
            return selected ? MARKER_SELECTED : MARKER_UNSELECTED;
        }
        return selected ? MARKER_SINGLE_SELECTED : MARKER_SINGLE_UNSELECTED;
    }

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

    /**
     * The selector title of one question.
     *
     * <p>A multi-question clarification is a wizard, so the modal states which step it is on and which question it
     * asks; the transcript card only carries the overall title.
     */
    public String selectorTitle(int index) {
        if (index < 0 || index >= questions.size()) {
            throw new IllegalArgumentException("question index is out of range");
        }
        return "Clarification · Step " + (index + 1) + " of " + questions.size() + " · "
                + questions.get(index).header();
    }

    /**
     * The interaction card projection: one line per question and no repeated option list.
     *
     * <p>The selector is the only surface that lists options together with their rationale, so the transcript card
     * never duplicates them. The card therefore stays short enough to read without a fold while the answer draft is
     * open right below it.
     */
    public ApprovalDetails toApprovalDetails() {
        StringBuilder content = new StringBuilder();
        for (Question question : questions) {
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(question.header()).append(": ").append(singleLine(question.prompt()));
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
                    "selected=" + String.join(",", selected.get(index)) + "\ncustom=" + singleLine(free),
                    ANSWER_FORMAT));
        }
        return List.copyOf(parts);
    }

    /** The answer format carries one custom line, so a pasted line break becomes a space. */
    private static String singleLine(String value) {
        return value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
    }
}
