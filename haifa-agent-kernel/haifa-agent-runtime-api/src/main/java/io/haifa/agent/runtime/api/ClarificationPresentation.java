package io.haifa.agent.runtime.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Display-only projection of a clarification request.
 *
 * <p>It never takes part in policy or execution decisions. Products render questions, tabs, option
 * descriptions and multi-select markers from this structure instead of parsing {@link
 * InteractionView#safePrompt()} text.
 */
public record ClarificationPresentation(String title, List<Question> questions) {

    public ClarificationPresentation {
        title = InteractionOption.requireText(title, "title", 256);
        questions = List.copyOf(Objects.requireNonNull(questions, "questions must not be null"));
        if (questions.isEmpty() || questions.size() > 3) {
            throw new IllegalArgumentException("questions must contain 1..3 entries");
        }
    }

    public record Question(
            String header, String question, boolean multiple, boolean allowCustom, List<Option> options) {

        public Question {
            header = InteractionOption.requireText(header, "header", 32);
            question = InteractionOption.requireText(question, "question", 2_000);
            options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
            if (options.size() < 2 || options.size() > 6) {
                throw new IllegalArgumentException("options must contain 2..6 entries");
            }
        }
    }

    public record Option(String optionId, String label, Optional<String> description) {

        public Option {
            optionId = InteractionKind.requireToken(optionId, "optionId");
            label = InteractionOption.requireText(label, "label", 120);
            description = Objects.requireNonNull(description, "description must not be null")
                    .map(value -> InteractionOption.requireText(value, "description", 300));
        }
    }

    /**
     * Every question must expose the same option ids as its input contract.
     *
     * @param contracts one input contract per question, in question order
     */
    public ClarificationPresentation requireMatchingContracts(List<InteractionInputContract> contracts) {
        Objects.requireNonNull(contracts, "contracts must not be null");
        if (contracts.size() != questions.size()) {
            throw new IllegalArgumentException("one input contract is required per question");
        }
        for (int index = 0; index < questions.size(); index++) {
            Question question = questions.get(index);
            InteractionInputContract contract = contracts.get(index);
            List<String> expected =
                    question.options().stream().map(Option::optionId).toList();
            List<String> actual =
                    contract.options().stream().map(InteractionOption::id).toList();
            if (!expected.equals(actual) || new HashSet<>(expected).size() != expected.size()) {
                throw new IllegalArgumentException("question " + index + " options must match its input contract");
            }
            boolean isMultiple = contract.type().equals(InteractionInputType.MULTI_CHOICE);
            boolean isSingle = contract.type().equals(InteractionInputType.SINGLE_CHOICE);
            if (!isMultiple && !isSingle) {
                throw new IllegalArgumentException("question " + index + " requires a choice input contract");
            }
            if (isMultiple != question.multiple()) {
                throw new IllegalArgumentException("question " + index + " multiple flag must match its contract");
            }
            if ((contract.maximumCharacters() > 0) != question.allowCustom()) {
                throw new IllegalArgumentException("question " + index + " custom flag must match its contract");
            }
        }
        return this;
    }
}
