package io.haifa.agent.runtime.core.skill;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillActivation;
import io.haifa.agent.skill.api.SkillActivationRequest;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillResourceRef;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Objects;

public final class DefaultSkillActivationService implements SkillActivationService {
    public static final long MAX_ACTIVATED_INSTRUCTION_BYTES_PER_RUN = 512L * 1024;
    public static final long MAX_ACTIVATED_ESTIMATED_TOKENS_PER_RUN = 10_000;
    public static final long MAX_RESOURCE_BYTES_PER_RUN = 2L * 1024 * 1024;

    private final RunStateRepository runs;
    private final RuntimeStateRepository state;
    private final SkillContentLoader contentLoader;
    private final TimeProvider time;

    public DefaultSkillActivationService(
            RunStateRepository runs,
            RuntimeStateRepository state,
            SkillContentLoader contentLoader,
            TimeProvider time) {
        this.runs = Objects.requireNonNull(runs);
        this.state = Objects.requireNonNull(state);
        this.contentLoader = Objects.requireNonNull(contentLoader);
        this.time = Objects.requireNonNull(time);
    }

    @Override
    public SkillActivation activate(SkillActivationRequest request) {
        AgentRun run = validateCaller(request);
        return state.skillActivation(run.id(), request.alias()).orElseGet(() -> {
            FrozenSkillBinding binding = binding(run, request);
            SkillContent content = contentLoader.load(binding, visibility(run));
            SkillActivation activation = new SkillActivation(
                    binding,
                    request.reason(),
                    request.requestedBy(),
                    time.now(),
                    content.instructions().getBytes(StandardCharsets.UTF_8).length,
                    content.estimatedTokens());
            return state.saveSkillActivation(
                    run.id(),
                    activation,
                    MAX_ACTIVATED_INSTRUCTION_BYTES_PER_RUN,
                    MAX_ACTIVATED_ESTIMATED_TOKENS_PER_RUN);
        });
    }

    @Override
    public SkillContent content(SkillActivationRequest request) {
        AgentRun run = validateCaller(request);
        SkillActivation activation = state.skillActivation(run.id(), request.alias())
                .orElseThrow(() -> new SecurityException("skill must be activated before content is read"));
        return contentLoader.load(activation.binding(), visibility(run));
    }

    @Override
    public SkillResourceRead readResource(SkillActivationRequest request, String relativePath) {
        SkillContent content = content(request);
        String normalizedPath =
                Objects.requireNonNull(relativePath, "relativePath").replace('\\', '/');
        SkillResourceRef indexed = content.binding().packageIndex().resources().stream()
                .filter(resource -> resource.relativePath().equals(normalizedPath))
                .findFirst()
                .orElseThrow(() -> new SecurityException("skill resource is not present in the frozen index"));
        if (!indexed.readableText()) throw new SecurityException("skill resource is not readable text");
        String value = content.resource(indexed.relativePath());
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (indexed.byteSize() != bytes.length || !indexed.digest().value().equals(sha256(bytes))) {
            throw new IllegalStateException("skill resource content does not match the frozen index");
        }
        state.addSkillResourceReadBytes(request.runId(), bytes.length, MAX_RESOURCE_BYTES_PER_RUN);
        return new SkillResourceRead(content.binding(), indexed, value);
    }

    private AgentRun validateCaller(SkillActivationRequest request) {
        AgentRun run = runs.find(request.runId()).orElseThrow(() -> new IllegalArgumentException("run does not exist"));
        if (!run.tenant().equals(request.tenant()) || !run.principal().equals(request.principal())) {
            throw new SecurityException("skill request caller does not own the run");
        }
        return run;
    }

    private FrozenSkillBinding binding(AgentRun run, SkillActivationRequest request) {
        var configuration = state.configuration(run.configurationSnapshot())
                .orElseThrow(() -> new IllegalStateException("run configuration snapshot is unavailable"));
        return configuration.skillBindings().stream()
                .filter(binding -> binding.alias().equals(request.alias()))
                .findFirst()
                .orElseThrow(() -> new SecurityException("skill is not allowed by the frozen run configuration"));
    }

    private static SkillVisibilityContext visibility(AgentRun run) {
        return new SkillVisibilityContext(
                run.tenant(),
                run.principal(),
                run.project(),
                run.project().isPresent(),
                EnumSet.allOf(SkillScope.class));
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
