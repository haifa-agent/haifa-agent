package io.haifa.agent.application.project;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.haifa.agent.application.project.product.ProjectProductService;
import io.haifa.agent.application.project.workspace.WorkspaceAccess;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.core.DefaultModelParameterResolver;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ProjectApplicationArchitectureTest {
    private static final JavaClasses PROJECT_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.haifa.agent.application.project");
    private static final JavaClasses MODEL_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.haifa.agent.model.api", "io.haifa.agent.model.core");

    @Test
    void applicationDoesNotExecuteProcessesOrDependOnConcreteProviders() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(PROJECT_CLASSES);
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.sandbox.host..", "io.haifa.agent.model.openai..", "org.springframework..")
                .check(PROJECT_CLASSES);
    }

    @Test
    void codingClientIsAProductBoundaryIndependentOfTerminalAndCli() {
        noClasses()
                .that()
                .resideInAPackage("..product.coding.client..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.application.coding.terminal..",
                        "io.haifa.agent.cli..",
                        "io.haifa.agent.model.openai..",
                        "io.haifa.agent.sandbox.host..")
                .check(PROJECT_CLASSES);
    }

    @Test
    void applicationPersistenceUsesTheStoreMyBatisBoundaryInsteadOfJdbc() {
        noClasses()
                .that()
                .resideInAPackage("..persistence..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.sql..")
                .check(PROJECT_CLASSES);
    }

    @Test
    void ordinaryProductMethodsDoNotExposeWorkspace() {
        assertThatNoWorkspaceParameter("start");
        assertThatNoWorkspaceParameter("continueSession");
    }

    @Test
    void commonModelProfilesAndResolverAreReusableWithoutPersonalAssistantTypes() {
        org.assertj.core.api.Assertions.assertThat(ModelBindingProfile.class).isPublic();
        org.assertj.core.api.Assertions.assertThat(DefaultModelParameterResolver.class)
                .isPublic();

        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.haifa.agent.personalassistant..")
                .check(MODEL_CLASSES);
    }

    @Test
    void workspaceAccessRemainsACodingOwnedMinimalRelation() {
        assertThat(Arrays.stream(WorkspaceAccess.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList())
                .containsExactly("tenant", "principal", "workspaceId", "mode");
        assertThat(Arrays.stream(WorkspaceAccess.class.getRecordComponents())
                        .map(component -> component.getType().getSimpleName())
                        .toList())
                .containsExactly("TenantRef", "PrincipalRef", "WorkspaceId", "WorkspaceAccessMode");

        noClasses()
                .that()
                .resideInAPackage("..workspace..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.policy..",
                        "io.haifa.agent.execution..",
                        "io.haifa.agent.sandbox..",
                        "io.haifa.agent.personalassistant..")
                .check(PROJECT_CLASSES);
    }

    private static void assertThatNoWorkspaceParameter(String name) {
        boolean exposed = Arrays.stream(ProjectProductService.class.getMethods())
                .filter(method -> method.getName().equals(name))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(WorkspaceId.class::equals);
        org.assertj.core.api.Assertions.assertThat(exposed).isFalse();
    }
}
