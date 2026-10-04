package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.skill.DefaultSkillActivationService;
import io.haifa.agent.runtime.core.skill.SkillToolProvider;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
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
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SkillToolVisibilityTest {
    @Test
    void exposesResourcesAfterActivationButNotFromAnEarlierRunInTheSameSession() {
        execute(true, 2);
    }

    @Test
    void entryOnlySkillsNeverExposeTheAuxiliaryReader() {
        execute(false, 1);
    }

    @Test
    void doesNotHideACustomProviderUsingTheReaderAlias() {
        var scheduler = new ManualExecutionScheduler();
        var calls = new AtomicInteger();
        var builder = new RuntimeCoreBuilder()
                .scheduler(scheduler)
                .definitions((id, version) -> new ResolvedDefinition(
                        id,
                        new AgentDefinitionVersion(1, 0, 0),
                        Set.of("skill_resource_read"),
                        Set.of(),
                        "Complete the request."))
                .registerChatModel("openai-compatible", "1.0.0", request -> {
                    calls.incrementAndGet();
                    assertThat(request.tools())
                            .extracting(ModelToolSpecification::name)
                            .containsExactly("skill_resource_read");
                    return response("done", null, Map.of());
                });
        var runtime = TestToolPlatform.install(
                        builder,
                        "skill_resource_read",
                        "1.0.0",
                        "custom-reader",
                        false,
                        request -> new io.haifa.agent.core.tool.ToolResult(
                                true, "custom", Map.of(), List.of(), List.of(), false))
                .build();
        var accepted = runtime.start(new AgentRunRequest(
                "custom-reader",
                new AgentDefinitionId("fixture-agent"),
                Optional.empty(),
                "default",
                new AgentSessionId("custom-session"),
                Optional.empty(),
                "Finish.",
                List.of(),
                RuntimeOverrides.NONE));
        scheduler.runAll();
        assertThat(runtime.find(accepted.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(calls).hasValue(1);
    }

    private static void execute(boolean resources, int runs) {
        var store = new InMemoryRuntimeStore();
        var scheduler = new ManualExecutionScheduler();
        var files = new HashMap<String, byte[]>();
        files.put(
                "SKILL.md",
                "---\nname: fixture\ndescription: Controlled resource fixture\n---\nUse the indexed resources.\n"
                        .getBytes(StandardCharsets.UTF_8));
        if (resources) files.put("references/fact.txt", "17\n".getBytes(StandardCharsets.UTF_8));
        var source = new InMemorySkillSource(
                new SkillSourceDescriptor(
                        new SkillSourceRef("fixture", "1"),
                        SkillScopeRef.sdk(),
                        SkillOrigin.BUNDLED,
                        0,
                        SkillParserMode.STRICT,
                        true,
                        false),
                new SkillPackageParser(SkillPackageLimits.defaults()),
                SkillAvailability.ENABLED,
                Map.of("fixture", files));
        var loader = new CompositeSkillContentLoader(List.of(source));
        var skillCatalog = new SkillCatalogBuilder(
                        List.of(source), new SkillResolutionPolicy("fixture@1", List.of(SkillScope.SDK), true))
                .build(new SkillDiscoveryContext(new SkillVisibilityContext(
                        new io.haifa.agent.core.reference.TenantRef("default"),
                        new io.haifa.agent.core.reference.PrincipalRef("default", "user"),
                        Optional.empty(),
                        false,
                        Set.of(SkillScope.SDK))));
        var service =
                new DefaultSkillActivationService(store, store, loader, () -> Instant.parse("2026-10-04T00:00:00Z"));
        var provider = new SkillToolProvider(service);
        var tools = new ToolCatalogBuilder();
        provider.contributions()
                .forEach(c -> tools.register(c.alias(), c.definition(), c.providerBindingReference(), c.provider()));
        var catalog = tools.freeze();
        var calls = new AtomicInteger();
        int phases = resources ? 3 : 2;
        var runtime = new RuntimeCoreBuilder()
                .persistence(RuntimePersistencePorts.inMemory(store))
                .scheduler(scheduler)
                .definitions((id, version) -> new ResolvedDefinition(
                        id,
                        new AgentDefinitionVersion(1, 0, 0),
                        Set.of("skill_load", "skill_resource_read"),
                        Set.of("fixture"),
                        Set.of(),
                        "Complete the request.",
                        List.of()))
                .skillPlatform(skillCatalog, loader)
                .toolPlatform(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator())
                .publicToolPolicy((run, binding, request) -> TestToolPlatform.allow())
                .registerChatModel("openai-compatible", "1.0.0", request -> {
                    int phase = calls.getAndIncrement() % phases;
                    if (phase == 0) {
                        assertThat(request.tools())
                                .extracting(ModelToolSpecification::name)
                                .containsExactly("skill_load");
                        return response(request.runId().value(), "skill_load", Map.of("skill", "fixture"));
                    }
                    if (resources) {
                        assertThat(request.tools())
                                .extracting(ModelToolSpecification::name)
                                .containsExactly("skill_load", "skill_resource_read");
                        if (phase == 1)
                            return response(
                                    request.runId().value(),
                                    "skill_resource_read",
                                    Map.of("skill", "fixture", "path", "references/fact.txt"));
                        assertThat(request.messages().stream()
                                        .filter(message -> message.role() == ModelMessageRole.TOOL)
                                        .map(message -> message.toolResultData().get("content")))
                                .contains("17\n");
                    } else {
                        assertThat(request.tools())
                                .extracting(ModelToolSpecification::name)
                                .containsExactly("skill_load");
                    }
                    return response("done", null, Map.of());
                })
                .build();
        for (int index = 0; index < runs; index++) {
            var accepted = runtime.start(new AgentRunRequest(
                    "visibility-" + index,
                    new AgentDefinitionId("fixture-agent"),
                    Optional.empty(),
                    "default",
                    new AgentSessionId("same-session"),
                    Optional.empty(),
                    "Use the Skill.",
                    List.of(),
                    RuntimeOverrides.NONE));
            scheduler.runAll();
            assertThat(runtime.find(accepted.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(store.skillActivations(accepted.runId())).hasSize(1);
        }
        assertThat(calls).hasValue(phases * runs);
    }

    private static AgentChatResponse response(String id, String tool, Map<String, Object> args) {
        return new AgentChatResponse(
                id,
                "fixture",
                tool == null ? "done" : "",
                tool == null
                        ? List.of()
                        : List.of(new ModelToolCall(new ProviderToolCallCorrelationId(id + "-" + tool), tool, args)),
                tool == null ? ModelFinishReason.STOP : ModelFinishReason.TOOL_CALLS,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }
}
