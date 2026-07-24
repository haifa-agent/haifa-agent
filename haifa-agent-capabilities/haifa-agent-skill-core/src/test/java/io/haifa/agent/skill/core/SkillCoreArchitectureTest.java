package io.haifa.agent.skill.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class SkillCoreArchitectureTest {
    @Test
    void coreDoesNotDependOnRuntimeContextExecutionCredentialsMcpOrFrameworks() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.skill.core");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.context..",
                        "io.haifa.agent.execution..",
                        "io.haifa.agent.credential.core..",
                        "io.modelcontextprotocol..",
                        "org.springframework..",
                        "jakarta.persistence..")
                .check(classes);
    }

    @Test
    void coreDoesNotUseProcessesOrNetworkClients() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.skill.core");
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
