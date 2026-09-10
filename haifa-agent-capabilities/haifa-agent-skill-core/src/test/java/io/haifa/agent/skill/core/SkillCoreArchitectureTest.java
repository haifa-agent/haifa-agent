package io.haifa.agent.skill.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class SkillCoreArchitectureTest {
    private static final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.haifa.agent.skill.core");

    @Test
    void coreDoesNotDependOnRuntimeContextExecutionCredentialsMcpOrFrameworks() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.context..",
                        "io.haifa.agent.execution..",
                        "io.haifa.agent.credential.core..",
                        "io.haifa.agent.personalassistant..",
                        "io.modelcontextprotocol..",
                        "org.springframework..",
                        "jakarta.persistence..")
                .check(classes);
    }

    @Test
    void coreDoesNotUseProcessesOrNetworkClients() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.net..")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(classes);
    }
}
