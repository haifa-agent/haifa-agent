package io.haifa.agent.application.coding.terminal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class CodingTerminalArchitectureTest {
    @Test
    void productionCodeUsesOnlyStableBoundaries() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.application.coding.terminal");

        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime.core..",
                        "io.haifa.agent.store.sqlite..",
                        "io.haifa.agent.sandbox..",
                        "org.jline..",
                        "java.sql..")
                .check(classes);
    }

    @Test
    void productionCodeDoesNotSpawnProcessesDirectly() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.application.coding.terminal");

        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(ProcessBuilder.class.getName())
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(Process.class.getName())
                .check(classes);
    }

    @Test
    void productionCodeDoesNotPerformDirectFileIo() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.application.coding.terminal");

        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.nio.file..")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.io.File")
                .check(classes);
    }

    @Test
    void markdownParsingAndTokensStayIndependentOfTerminalAndRuntimeLibraries() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.application.coding.terminal.markdown");
        noClasses()
                .that()
                .resideInAnyPackage("..markdown.parse..", "..markdown.style..")
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages("java..", "..markdown.parse..", "..markdown.style..")
                .check(classes);
    }
}
