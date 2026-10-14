package io.haifa.agent.starter;

import static io.haifa.agent.starter.SkillScriptTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void executesAMultiFilePackageFromTheInMemorySourceThatTheHarnessUses(@TempDir Path temporary) throws Exception {
        if (SkillScriptTestSupport.runtimeExecutable() == null
                || !java.nio.file.Files.isRegularFile(SkillScriptTestSupport.runtimeExecutable())) {
            return;
        }
        Path workspace = SkillScriptTestSupport.createWorkspace(temporary, "workspace");
        Path dataRoot = SkillScriptTestSupport.createWorkspace(temporary, "data");

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
                SkillScriptTestSupport.packageFiles(SkillScriptTestSupport.entryScript(false)));
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
                .anyMatch(resource -> resource.relativePath().equals(SkillScriptTestSupport.scriptRelative()));

        var loader = new CompositeSkillContentLoader(List.of(source));
        var declaration = declaration(
                binding,
                runtimeLanguage(),
                Duration.ofSeconds(60),
                "verify the trusted package entry run from the in-memory source");
        var spec = spec(workspace, dataRoot, loader, List.of(declaration));

        try (SkillScriptPlatforms.SkillScriptPlatform platform =
                SkillScriptPlatforms.trusted(spec, TENANT, PRINCIPAL)) {
            var toolRegistration = platform.registrations().get(0);
            ToolDefinition definition = toolRegistration.definition();
            FrozenToolBinding toolBinding = freeze(toolRegistration);
            var result =
                    toolRegistration.provider().invoke(invocation(toolBinding, definition, "in-memory-source-call-1"));

            assertThat(result.successful()).isTrue();
            assertThat(result.structuredData()).containsEntry("processState", "EXITED");
            assertThat(java.nio.file.Files.readString(
                            workspace.resolve("outputs").resolve("result.txt"), StandardCharsets.UTF_8))
                    .isEqualTo(SkillScriptTestSupport.MARKER + "\n");
        }
    }
}
