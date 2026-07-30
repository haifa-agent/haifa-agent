package io.haifa.agent.execution.core.tool;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillTrustDigests;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Provider for product-defined fixed Tools; it never accepts script identity or content from a model. */
public final class TrustedSkillScriptToolProvider implements ToolProvider {
    public static final ToolProviderId PROVIDER_ID = new ToolProviderId("haifa-trusted-skill-script");

    private final ExecutionToolProvider execution;
    private final SkillContentLoader contentLoader;
    private final Map<String, TrustedSkillScriptToolSpec> specs;

    public TrustedSkillScriptToolProvider(
            ExecutionToolProvider execution, SkillContentLoader contentLoader, List<TrustedSkillScriptToolSpec> specs) {
        this.execution = Objects.requireNonNull(execution, "execution must not be null");
        this.contentLoader = Objects.requireNonNull(contentLoader, "contentLoader must not be null");
        Objects.requireNonNull(specs, "specs must not be null");
        Map<String, TrustedSkillScriptToolSpec> indexed = new LinkedHashMap<>();
        for (TrustedSkillScriptToolSpec spec : specs) {
            String name = spec.definition().name().value();
            if (indexed.putIfAbsent(name, spec) != null) {
                throw new IllegalArgumentException("trusted script Tool names must be unique");
            }
        }
        if (indexed.isEmpty()) throw new IllegalArgumentException("at least one trusted script Tool is required");
        this.specs = Map.copyOf(indexed);
    }

    @Override
    public ToolProviderId id() {
        return PROVIDER_ID;
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest invocation) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        TrustedSkillScriptToolSpec spec =
                specs.get(invocation.binding().definition().name().value());
        if (spec == null
                || !spec.definition().equals(invocation.binding().definition())
                || !invocation
                        .binding()
                        .coordinate()
                        .name()
                        .equals(spec.definition().name())) {
            throw new SecurityException("trusted script Tool binding is unavailable or drifted");
        }
        if (invocation.policyDecisionRef().isEmpty()) {
            throw new SecurityException("trusted script Tool requires a public policy decision");
        }
        if (!execution.configurationIdentity().equals(spec.executionConfigurationDigest())) {
            throw new SecurityException("trusted script execution configuration has drifted");
        }
        if (!SkillTrustDigests.sandbox(execution.sandboxProfileIdentity()).equals(spec.sandboxDigest())) {
            throw new SecurityException("trusted script sandbox profile has drifted");
        }
        var visibility = new SkillVisibilityContext(
                invocation.tenant(),
                invocation.principal(),
                Optional.empty(),
                false,
                Set.of(SkillScope.USER, SkillScope.PRODUCT));
        var content = contentLoader.load(spec.skillBinding(), visibility);
        if (!content.binding().equals(spec.skillBinding())) {
            throw new SecurityException("trusted script Skill binding has drifted");
        }
        String script = content.resource(spec.scriptRelativePath());
        if (!SkillTrustDigests.content(script).equals(spec.scriptDigest())) {
            throw new SecurityException("trusted script content has drifted");
        }
        TrustedScriptArguments mapped = Objects.requireNonNull(
                spec.argumentMapper().map(invocation.arguments().values()), "argument mapper must not return null");
        return execution.invokeTrustedScript(
                invocation,
                spec.scriptRuntimeRef(),
                script,
                mapped.argv(),
                spec.purpose(),
                mapped.workingDirectory(),
                spec.timeout(),
                spec.requiredCapabilities(),
                mapped.workspaceInputPaths());
    }
}
