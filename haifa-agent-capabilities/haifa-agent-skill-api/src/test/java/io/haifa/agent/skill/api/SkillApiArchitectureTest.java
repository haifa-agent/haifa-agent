package io.haifa.agent.skill.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class SkillApiArchitectureTest {
    @Test
    void apiDoesNotDependOnParsingFilesystemRuntimeFrameworkOrProviderTypes() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.skill.api");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.fasterxml.jackson..",
                        "org.yaml..",
                        "java.nio.file..",
                        "org.springframework..",
                        "io.modelcontextprotocol..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.personalassistant..",
                        "jakarta.persistence..")
                .check(classes);
    }
}
