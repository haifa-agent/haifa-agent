package io.haifa.agent.spring.autoconfigure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class HaifaAgentSpringArchitectureTest {
    @Test
    void remainsAThinSpringAdapter() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.spring.autoconfigure");

        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.ai..",
                        "com.alibaba.cloud.ai..",
                        "io.haifa.agent.runtime.core..",
                        "jakarta.persistence..",
                        "org.mybatis..",
                        "java.sql..")
                .check(classes);
    }
}
