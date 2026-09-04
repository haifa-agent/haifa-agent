package io.haifa.agent.testing.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PersonalAssistantSmokeCatalogTest {

    @Test
    void publishesTheFiveBlockingPersonalAssistantSmokeCasesInOrder() {
        assertEquals(
                java.util.List.of("PA-SM-01", "PA-SM-02", "PA-SM-03", "PA-SM-04", "PA-SM-05"),
                PersonalAssistantSmokeCatalog.cases().stream()
                        .map(PersonalAssistantSmokeCase::caseId)
                        .toList());
    }
}
