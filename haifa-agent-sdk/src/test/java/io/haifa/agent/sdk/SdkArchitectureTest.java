package io.haifa.agent.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.sdk.api.HaifaAgent;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class SdkArchitectureTest {
    private static final Set<String> EXPORTED_PACKAGES = Set.of(
            "io.haifa.agent.sdk.api",
            "io.haifa.agent.sdk.conversation",
            "io.haifa.agent.sdk.contribution",
            "io.haifa.agent.sdk.diagnostics",
            "io.haifa.agent.sdk.product",
            "io.haifa.agent.sdk.tool");
    private static final Set<String> FORBIDDEN_PREFIXES = Set.of(
            "io.haifa.agent.runtime.core",
            "io.haifa.agent.store.sqlite",
            "org.mybatis",
            "org.springframework",
            "com.fasterxml.jackson",
            "java.sql");

    @Test
    void exportedPublicMethodSignaturesDoNotLeakRuntimeOrFrameworkInternals() {
        var classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .importPackages("io.haifa.agent.sdk").stream()
                        .map(javaClass -> javaClass.reflect())
                        .filter(type -> EXPORTED_PACKAGES.contains(type.getPackageName()))
                        .filter(type -> Modifier.isPublic(type.getModifiers()))
                        .toList();

        assertThat(classes).isNotEmpty();
        var methods = classes.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(method -> (Executable) method))
                .toList();
        var constructors = classes.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredConstructors())
                        .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                        .map(constructor -> (Executable) constructor))
                .toList();
        assertThat(methods).allSatisfy(SdkArchitectureTest::assertSafe);
        assertThat(constructors).allSatisfy(SdkArchitectureTest::assertSafe);
        assertThat(HaifaAgent.class.getDeclaredMethods())
                .noneMatch(method -> method.getReturnType().getName().startsWith("io.haifa.agent.runtime.core"));
    }

    private static void assertSafe(Executable executable) {
        Arrays.stream(executable.getParameterTypes()).forEach(type -> assertSafeType(executable, type));
        if (executable instanceof java.lang.reflect.Method method) {
            assertSafeType(executable, method.getReturnType());
        }
    }

    private static void assertSafeType(Executable executable, Class<?> type) {
        assertThat(FORBIDDEN_PREFIXES)
                .as("public signature %s must not expose %s", executable, type.getName())
                .noneMatch(type.getName()::startsWith);
    }
}
