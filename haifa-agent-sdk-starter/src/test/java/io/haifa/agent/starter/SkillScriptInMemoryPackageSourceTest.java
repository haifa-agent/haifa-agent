package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.core.tool.TrustedScriptArguments;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillResolutionPolicy;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.skill.core.CompositeSkillContentLoader;
import io.haifa.agent.skill.core.InMemorySkillSource;
import io.haifa.agent.skill.core.SkillCatalogBuilder;
import io.haifa.agent.skill.core.SkillPackageLimits;
import io.haifa.agent.skill.core.SkillPackageParser;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolName;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R2: the Sh Harness consumes imported and custom Skill packages through an in-memory source and
 * one composite content loader. The trusted script entry must therefore run a real multi-file
 * package from exactly that source chain: real {@link InMemorySkillSource} byte reading, real
 * {@link CompositeSkillContentLoader} delegation, version directory preparation and entry-file
 * execution with a relative cross-file import.
 */
class SkillScriptInMemoryPackageSourceTest {
    private static final TenantRef TENANT = new TenantRef("public");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("default-public-user", "user");
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(10);

    @Test
    void executesAMultiFilePackageFromTheInMemorySourceThatTheHarnessUses(@TempDir Path temporary) throws Exception {
        if (SkillScriptPlatformsTest.runtimeExecutable() == null
                || !java.nio.file.Files.isRegularFile(SkillScriptPlatformsTest.runtimeExecutable())) {
            return;
        }
        Path workspace = SkillScriptPlatformsTest.createWorkspace(temporary, "workspace");
        Path dataRoot = SkillScriptPlatformsTest.createWorkspace(temporary, "data");

        // The exact InMemory source chain the Harness uses for imports and custom packages.
        SkillPackageParser parser = new SkillPackageParser(SkillPackageLimits.defaults());
        SkillSourceDescriptor descriptor = new SkillSourceDescriptor(
                new SkillSourceRef("harness-import", "1"),
                SkillScopeRef.product(),
                SkillOrigin.BUNDLED,
                0,
                SkillParserMode.STRICT,
                true,
                false);
        Map<String, Map<String, byte[]>> packages = Map.of(
                "trusted-entry-package",
                SkillScriptPlatformsTest.packageFiles(SkillScriptPlatformsTest.entryScript(false)));
        InMemorySkillSource source = new InMemorySkillSource(descriptor, parser, SkillAvailability.ENABLED, packages);
        SkillVisibilityContext visibility = new SkillVisibilityContext(
                TENANT, PRINCIPAL, Optional.empty(), false, java.util.Set.of(SkillScope.PRODUCT));
        // A package that indexes a script is REVIEW_REQUIRED, exactly like the Harness flow: one
        // exact package review grant makes this reviewed version eligible.
        Instant now = Instant.now();
        var registration = source.discover(new SkillDiscoveryContext(visibility))
                .registrations()
                .getFirst();
        var grant = new io.haifa.agent.skill.api.SkillPackageReviewGrant(
                "package-review-1",
                1,
                1,
                TENANT,
                PRINCIPAL,
                "skill-script-entry-tests",
                io.haifa.agent.skill.api.SkillTrustScope.PRODUCT,
                Optional.empty(),
                registration.coordinate(),
                registration.registrationDigest(),
                registration.packageIndex().digest(),
                now.minusSeconds(3600),
                Optional.of(now.plus(Duration.ofDays(1))),
                Optional.empty(),
                io.haifa.agent.skill.api.SkillTrustGrantState.ACTIVE,
                "reviewer",
                "starter-tests",
                "SKILL_PACKAGE_REVIEWED");
        var catalog = new SkillCatalogBuilder(
                        List.of(source),
                        new SkillResolutionPolicy("starter@1", List.of(SkillScope.PRODUCT), true),
                        new io.haifa.agent.skill.api.SkillTrustSnapshot(
                                "sha256:" + "a".repeat(64), List.of(grant), List.of()),
                        new io.haifa.agent.skill.api.SkillTrustSubject(
                                TENANT, PRINCIPAL, "skill-script-entry-tests", Optional.empty()),
                        java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))
                .build(new SkillDiscoveryContext(visibility));
        FrozenSkillBinding binding = catalog.snapshot().bindings().getFirst();
        assertThat(binding.packageIndex().resources())
                .anyMatch(resource -> resource.relativePath().equals(SkillScriptPlatformsTest.scriptRelative()));

        var loader = new CompositeSkillContentLoader(List.of(source));
        SkillScriptPlatforms.SkillScriptToolDeclaration declaration =
                new SkillScriptPlatforms.SkillScriptToolDeclaration(
                        new ToolName("generate_note"),
                        "Generate one bounded note",
                        "Generates one bounded note artifact through the frozen skill package script.",
                        starterInputSchema(),
                        Duration.ofMinutes(2),
                        binding,
                        SkillScriptPlatformsTest.scriptRelative(),
                        SkillScriptPlatformsTest.runtimeLanguage(),
                        Duration.ofSeconds(60),
                        "verify the trusted package entry run from the in-memory source",
                        arguments -> TrustedScriptArguments.atWorkspaceRoot(List.of()));
        var spec = new SkillScriptPlatforms.SkillScriptSpec(
                workspace,
                dataRoot,
                List.of(new SkillScriptPlatforms.SkillScriptRuntime(
                        SkillScriptPlatformsTest.runtimeLanguage(), SkillScriptPlatformsTest.runtimeExecutable())),
                loader,
                List.of(declaration),
                Duration.ofSeconds(90),
                MAXIMUM_TIMEOUT,
                65_536,
                1_000);

        try (SkillScriptPlatforms.SkillScriptPlatform platform =
                SkillScriptPlatforms.trusted(spec, TENANT, PRINCIPAL)) {
            var toolRegistration = platform.registrations().get(0);
            ToolDefinition definition = toolRegistration.definition();
            var catalog2 = new io.haifa.agent.tool.core.ToolCatalogBuilder();
            catalog2.register(
                    toolRegistration.alias(),
                    definition,
                    toolRegistration.providerBindingReference(),
                    toolRegistration.provider());
            FrozenToolBinding toolBinding =
                    catalog2.freeze().snapshot().bindings().getFirst();
            var result = toolRegistration.provider().invoke(invocation(toolBinding, definition));

            assertThat(result.successful()).isTrue();
            assertThat(result.structuredData()).containsEntry("processState", "EXITED");
            assertThat(java.nio.file.Files.readString(
                            workspace.resolve("outputs").resolve("result.txt"), StandardCharsets.UTF_8))
                    .isEqualTo(SkillScriptPlatformsTest.MARKER + "\n");
        }
    }

    private static io.haifa.agent.tool.api.ToolSchema starterInputSchema() {
        return new io.haifa.agent.tool.api.ToolSchema(
                "haifa.skillscript.note.input",
                "1.0.0",
                Map.of(
                        "$schema",
                        io.haifa.agent.tool.api.ToolSchema.DRAFT_2020_12,
                        "type",
                        "object",
                        "additionalProperties",
                        false,
                        "properties",
                        Map.of()));
    }

    private static ToolInvocationRequest invocation(FrozenToolBinding binding, ToolDefinition definition) {
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("in-memory-source-call-1"),
                new AgentRunId("in-memory-source-run-1"),
                TENANT,
                PRINCIPAL,
                new ToolArguments(
                        definition.inputSchema().id(), definition.inputSchema().version(), Map.of()),
                Instant.now().plus(Duration.ofMinutes(5)),
                Optional.of("in-memory-source-invocation-1"),
                () -> false,
                Map.of());
    }
}
