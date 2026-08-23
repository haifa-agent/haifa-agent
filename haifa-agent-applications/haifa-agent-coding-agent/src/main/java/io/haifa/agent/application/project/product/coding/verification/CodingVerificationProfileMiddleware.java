package io.haifa.agent.application.project.product.coding.verification;

import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.prompt.PromptRole;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddleware;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareOrder;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import java.util.Objects;
import java.util.Set;

/** Adds the frozen Session verification profile without changing persisted user messages. */
public final class CodingVerificationProfileMiddleware implements AgentRuntimeMiddleware {
    private final CodingVerificationProfileProvider profiles;

    public CodingVerificationProfileMiddleware(CodingVerificationProfileProvider profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
    }

    @Override
    public RuntimePhase phase() {
        return RuntimePhase.BEFORE_CONTEXT_BUILD;
    }

    @Override
    public RuntimeMiddlewareOrder order() {
        return new RuntimeMiddlewareOrder(250);
    }

    @Override
    public void apply(RuntimeMiddlewareContext context) {
        CodingSessionVerificationConfiguration configuration =
                profiles.configurationFor(context.run().id());
        context.addPrompt(new PromptComponent(
                new PromptComponentId("coding-session-verification"),
                configuration.schemaVersion(),
                PromptLayer.RUNTIME_CONTROL,
                PromptRole.RUNTIME,
                "configurationDigest=" + configuration.digest() + "\n"
                        + configuration.profile().instructionText(),
                false,
                Set.of("coding-session", "verification")));
    }
}
