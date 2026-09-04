package io.haifa.agent.testing.personal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonalAssistantSmokeSuiteApplicationTest {
    @Test
    void runsPersonalAssistantSlowCasesThroughSurefire() {
        PersonalAssistantSmokeCase testCase = PersonalAssistantSmokeCatalog.require("PA-SM-01");

        List<String> command = PersonalAssistantSmokeSuiteApplication.mavenCommand(
                Path.of("D:/workspace/haifa-agent"), testCase, Path.of("D:/haifa-test-runs/reports"));

        assertTrue(command.contains("-Pslow-tests"));
        assertTrue(command.contains("-Dtest=" + testCase.testSelector()));
        assertTrue(command.contains("-Dhaifa.surefire.reportsDirectory=D:\\haifa-test-runs\\reports"));
        assertTrue(command.contains("test"));
    }
}
