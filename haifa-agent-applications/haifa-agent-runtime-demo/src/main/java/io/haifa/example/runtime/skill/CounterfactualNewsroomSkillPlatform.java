package io.haifa.example.runtime.skill;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.runtime.core.skill.DefaultSkillActivationService;
import io.haifa.agent.runtime.core.skill.SkillToolProvider;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillDiagnosticSeverity;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillResolutionPolicy;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSource;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.skill.core.ClasspathSkillSource;
import io.haifa.agent.skill.core.CompositeSkillContentLoader;
import io.haifa.agent.skill.core.DefaultSkillCatalog;
import io.haifa.agent.skill.core.SkillCatalogBuilder;
import io.haifa.agent.skill.core.SkillPackageLimits;
import io.haifa.agent.skill.core.SkillPackageParser;
import io.haifa.agent.tool.core.DefaultToolCatalog;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Freezes the example newsroom Skill and its progressive-disclosure activation Tool. */
public record CounterfactualNewsroomSkillPlatform(
        DefaultSkillCatalog skillCatalog, SkillContentLoader contentLoader, DefaultToolCatalog toolCatalog) {
    public static final String SKILL_NAME = "run-counterfactual-newsrooms";
    public static final String SKILL_LOAD_ALIAS = "skill_load";
    private static final String SKILL_ROOT = "META-INF/haifa-agent/skills";
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("local-user", "user");

    public static CounterfactualNewsroomSkillPlatform create(RuntimePersistencePorts persistence, TimeProvider time) {
        SkillSource source = new ClasspathSkillSource(
                CounterfactualNewsroomSkillPlatform.class.getClassLoader(),
                SKILL_ROOT,
                List.of(SKILL_NAME),
                new SkillSourceDescriptor(
                        new SkillSourceRef("classpath:runtime-demo-skills", "1"),
                        SkillScopeRef.product(),
                        SkillOrigin.BUNDLED,
                        100,
                        SkillParserMode.STRICT,
                        true,
                        false),
                new SkillPackageParser(SkillPackageLimits.defaults()),
                SkillAvailability.ENABLED);
        var visibility =
                new SkillVisibilityContext(TENANT, PRINCIPAL, Optional.empty(), false, Set.of(SkillScope.PRODUCT));
        DefaultSkillCatalog skillCatalog = new SkillCatalogBuilder(
                        List.of(source),
                        new SkillResolutionPolicy("runtime-demo-skill-policy-v1", List.of(SkillScope.PRODUCT), false))
                .build(new SkillDiscoveryContext(visibility));
        List<String> errors = skillCatalog.snapshot().diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == SkillDiagnosticSeverity.ERROR)
                .map(diagnostic -> diagnostic.code())
                .sorted()
                .toList();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("counterfactual newsroom Skill discovery failed: " + errors);
        }
        if (skillCatalog.snapshot().bindings().size() != 1
                || !SKILL_NAME.equals(
                        skillCatalog.snapshot().bindings().getFirst().alias().value())) {
            throw new IllegalStateException("counterfactual newsroom Skill was not frozen exactly once");
        }

        SkillContentLoader contentLoader = new CompositeSkillContentLoader(List.of(source));
        var activation =
                new DefaultSkillActivationService(persistence.runs(), persistence.state(), contentLoader, time);
        var skillTools = new SkillToolProvider(activation).contributions();
        var loadTool = skillTools.stream()
                .filter(contribution ->
                        SKILL_LOAD_ALIAS.equals(contribution.alias().value()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("skill_load Tool contribution is unavailable"));
        DefaultToolCatalog toolCatalog = new ToolCatalogBuilder()
                .register(
                        loadTool.alias(),
                        loadTool.definition(),
                        loadTool.providerBindingReference(),
                        loadTool.provider())
                .freeze();
        return new CounterfactualNewsroomSkillPlatform(skillCatalog, contentLoader, toolCatalog);
    }
}
