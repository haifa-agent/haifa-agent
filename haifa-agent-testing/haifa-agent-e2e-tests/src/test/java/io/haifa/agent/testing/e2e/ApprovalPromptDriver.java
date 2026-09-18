package io.haifa.agent.testing.e2e;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class ApprovalPromptDriver {
    static final String PROMPT_SUFFIX = " [y/N] ";

    private final List<Decision> decisions;
    private final StringBuilder output = new StringBuilder();
    private int promptStart;
    private int nextDecision;

    ApprovalPromptDriver(List<Decision> decisions) {
        this.decisions = List.copyOf(decisions);
    }

    Optional<String> accept(char value) {
        output.append(value);
        if (!endsWith(PROMPT_SUFFIX)) return Optional.empty();
        if (nextDecision >= decisions.size()) {
            throw new IllegalStateException("unexpected additional approval prompt");
        }
        Decision decision = decisions.get(nextDecision++);
        String prompt = output.substring(promptStart, output.length() - PROMPT_SUFFIX.length());
        promptStart = output.length();
        if (!prompt.toLowerCase(Locale.ROOT).contains(decision.expectedTarget().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "approval prompt did not identify expected target " + decision.expectedTarget());
        }
        return Optional.of(decision.answer() + System.lineSeparator());
    }

    private boolean endsWith(String suffix) {
        if (output.length() < suffix.length()) return false;
        int offset = output.length() - suffix.length();
        for (int index = 0; index < suffix.length(); index++) {
            if (output.charAt(offset + index) != suffix.charAt(index)) return false;
        }
        return true;
    }

    String output() {
        return output.toString();
    }

    void assertComplete() {
        if (nextDecision != decisions.size()) {
            throw new IllegalStateException(
                    "expected " + decisions.size() + " approval prompts but observed " + nextDecision);
        }
    }

    record Decision(String expectedTarget, String answer) {
        Decision {
            if (expectedTarget == null || expectedTarget.isBlank()) {
                throw new IllegalArgumentException("expectedTarget must not be blank");
            }
            if (!"y".equalsIgnoreCase(answer) && !"n".equalsIgnoreCase(answer)) {
                throw new IllegalArgumentException("answer must be y or n");
            }
        }
    }
}
