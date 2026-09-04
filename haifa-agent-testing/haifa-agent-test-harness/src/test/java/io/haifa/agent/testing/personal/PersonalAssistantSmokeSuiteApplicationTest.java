package io.haifa.agent.testing.personal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonalAssistantSmokeSuiteApplicationTest {
    @Test
    void runsPersonalAssistantSlowCasesThroughSurefire() {
        PersonalAssistantSmokeCase testCase = PersonalAssistantSmokeCatalog.require("PA-SM-01");
        Path projectRoot = repositoryRoot();
        Path reportsRoot = projectRoot.resolve("local-tmp").resolve("personal-assistant-smoke-reports");

        List<String> command = PersonalAssistantSmokeSuiteApplication.mavenCommand(projectRoot, testCase, reportsRoot);

        assertTrue(command.contains("-Pslow-tests"));
        assertTrue(command.contains("-Dtest=" + testCase.testSelector()));
        assertTrue(command.contains("-Dhaifa.surefire.reportsDirectory=" + reportsRoot));
        assertTrue(command.contains("test"));
    }

    private static Path repositoryRoot() {
        Path current =
                Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".mvn")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository root from Maven basedir");
    }
}
