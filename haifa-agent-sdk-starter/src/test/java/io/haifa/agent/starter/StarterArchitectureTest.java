package io.haifa.agent.starter;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class StarterArchitectureTest {
    @Test
    void remainsPureJavaAndFrameworkIndependent() {
        var classes = new ClassFileImporter().importPackages("io.haifa.agent.starter");

        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.mybatis..",
                        "io.modelcontextprotocol.sdk..")
                .check(classes);
    }
}
