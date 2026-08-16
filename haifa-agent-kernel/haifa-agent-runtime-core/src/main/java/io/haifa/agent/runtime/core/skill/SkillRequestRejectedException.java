package io.haifa.agent.runtime.core.skill;

final class SkillRequestRejectedException extends SecurityException {
    private final String failureCode;

    SkillRequestRejectedException(String failureCode, String safeMessage) {
        super(safeMessage);
        this.failureCode = java.util.Objects.requireNonNull(failureCode, "failureCode");
    }

    String failureCode() {
        return failureCode;
    }
}
