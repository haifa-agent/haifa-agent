package io.haifa.agent.execution.core.tool;

import java.util.Map;

/** Product-owned deterministic mapping from validated business arguments to a bounded argv. */
@FunctionalInterface
public interface TrustedScriptArgumentMapper {
    TrustedScriptArguments map(Map<String, Object> validatedArguments);
}
