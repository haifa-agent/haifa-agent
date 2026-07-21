package io.haifa.agent.core.run;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Stable cancellation or timeout reason retained with a terminal run. */
public record RunTerminationReason(String code, String description) {
    public RunTerminationReason {
        code = requireText(code, "code");
        description = requireText(description, "description");
    }
}
