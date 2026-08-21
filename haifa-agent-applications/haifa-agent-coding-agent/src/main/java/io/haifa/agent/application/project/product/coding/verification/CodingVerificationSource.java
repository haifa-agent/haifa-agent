package io.haifa.agent.application.project.product.coding.verification;

public enum CodingVerificationSource {
    USER_EXPLICIT(0),
    REPOSITORY_INSTRUCTIONS(1),
    BUILD_CONFIGURATION(2),
    ADJACENT_TEST(3),
    ECOSYSTEM_DEFAULT(4);

    private final int priority;

    CodingVerificationSource(int priority) {
        this.priority = priority;
    }

    int priority() {
        return priority;
    }
}
