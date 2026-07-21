package io.haifa.agent.application.project;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.haifa.agent.application.project.product.ProjectProductService;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProjectApplicationArchitectureTest {
    @Test
    void applicationDoesNotExecuteProcessesOrDependOnConcreteProviders() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.application.project");
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(classes);
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.sandbox.host..", "io.haifa.agent.model.openai..", "org.springframework..")
                .check(classes);
    }

    @Test
    void ordinaryProductMethodsDoNotExposeWorkspace() {
        assertThatNoWorkspaceParameter("start");
        assertThatNoWorkspaceParameter("continueSession");
    }

    private static void assertThatNoWorkspaceParameter(String name) {
        boolean exposed = Arrays.stream(ProjectProductService.class.getMethods())
                .filter(method -> method.getName().equals(name))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(WorkspaceId.class::equals);
        org.assertj.core.api.Assertions.assertThat(exposed).isFalse();
    }
}
