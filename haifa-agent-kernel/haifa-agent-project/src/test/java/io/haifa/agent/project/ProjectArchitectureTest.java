package io.haifa.agent.project;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ProjectArchitectureTest {
    @Test
    void projectIsFrameworkRuntimeAndProviderIndependent() {
        var classes = productionClasses();
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "com.fasterxml.jackson..",
                        "jakarta.persistence..",
                        "io.haifa.agent.runtime..",
                        "io.haifa.agent.model..",
                        "io.haifa.agent.product..")
                .check(classes);
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(classes);
    }

    @Test
    void onlyHostWorkspaceAccessUsesHostFileApis() {
        noClasses()
                .that()
                .resideOutsideOfPackage("io.haifa.agent.project.hostworkspace..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.nio.file..")
                .check(productionClasses());
    }

    @Test
    void legacyMultiRootAliasModelIsRemoved() {
        assertThat(productionClasses().stream().map(javaClass -> javaClass.getSimpleName()))
                .noneMatch(name -> name.equals("WorkspaceRootAlias")
                        || name.equals("MultiRootPath")
                        || name.equals("WorkspaceRootStrategy")
                        || name.equals("WorkspaceRootPermission")
                        || name.equals("WorkspaceRootErrorCode")
                        || name.equals("WorkspaceRootException")
                        || name.equals("LocalWorkspaceRoot")
                        || name.equals("LocalWorkspaceRootRegistry")
                        || name.equals("LocalWorkspaceRootStrategyDetector")
                        || name.equals("LocalMultiRootPathResolver"));
    }

    @Test
    void legacyLocalWorkspaceAccessNamesAreRemoved() {
        assertThat(productionClasses().stream().map(javaClass -> javaClass.getSimpleName()))
                .noneMatch(name -> name.equals("LocalWorkspaceScope")
                        || name.equals("LocalAllowedDirectory")
                        || name.equals("LocalDirectoryPermission")
                        || name.equals("LocalDirectoryIdentity")
                        || name.equals("LocalScopeErrorCode")
                        || name.equals("LocalWorkspaceScopeException")
                        || name.equals("LocalWorkspaceFileService")
                        || name.equals("LocalWorkspaceMutationService")
                        || name.equals("LocalWorkspaceLocationStore")
                        || name.equals("LocalWorkspacePathSafety")
                        || name.equals("LocalStreamingPatchTransformer")
                        || name.equals("AuthorizedDirectoryProvisioning"));
    }

    private static com.tngtech.archunit.core.domain.JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.project");
    }
}
