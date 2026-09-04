package io.haifa.agent.testing.personal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PA product smoke catalog; private suites select these cases without redefining their selectors. */
public final class PersonalAssistantSmokeCatalog {
    private static final Map<String, PersonalAssistantSmokeCase> CASES = build();

    private PersonalAssistantSmokeCatalog() {}

    public static List<PersonalAssistantSmokeCase> cases() {
        return List.copyOf(CASES.values());
    }

    public static PersonalAssistantSmokeCase require(String caseId) {
        PersonalAssistantSmokeCase value = CASES.get(caseId);
        if (value == null) throw new IllegalArgumentException("unknown personal-assistant smoke case: " + caseId);
        return value;
    }

    private static Map<String, PersonalAssistantSmokeCase> build() {
        List<PersonalAssistantSmokeCase> cases = List.of(
                test(
                        "PA-SM-01",
                        "Server assembly and loopback API boot",
                        "PersonalAssistantWebFluxTest#conversationCreationPersistsTheExactModelSelectionAtomically"),
                test(
                        "PA-SM-02",
                        "Safe capability and model projection",
                        "PersonalAssistantWebFluxTest#adminListsFrozenToolMcpAndSkillRegistrationsWithoutRuntimeSecrets"),
                test(
                        "PA-SM-03",
                        "Conversation runtime pipeline and durable stream",
                        "PersonalAssistantWebFluxTest#webfluxApiExecutesToolSkillAndMcpThroughOneRuntimePipeline"),
                test(
                        "PA-SM-04",
                        "Exact approval round trip",
                        "PersonalAssistantWebFluxTest#executionRequiresExactApprovalAndPublishesSafeActivity"),
                test(
                        "PA-SM-05",
                        "SQLite-backed conversation recovery after restart",
                        "PersonalAssistantRestartTest#conversationRunUsageAndActivitiesRecoverFromTheSameSqliteDatabase"),
                test(
                        "PA-SM-06",
                        "Vision image upload and semantic response",
                        "PersonalAssistantVisionLiveTest#uploadsWebpAndVerifiesAVisionResponse"));
        LinkedHashMap<String, PersonalAssistantSmokeCase> result = new LinkedHashMap<>();
        for (PersonalAssistantSmokeCase value : cases) {
            if (result.put(value.caseId(), value) != null) {
                throw new IllegalStateException("duplicate personal-assistant smoke case: " + value.caseId());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static PersonalAssistantSmokeCase test(String caseId, String title, String selector) {
        return new PersonalAssistantSmokeCase(caseId, title, ":haifa-agent-personal-assistant-server", selector);
    }
}
