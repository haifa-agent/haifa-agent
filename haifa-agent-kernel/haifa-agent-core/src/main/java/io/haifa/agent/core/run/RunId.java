package io.haifa.agent.core.run;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed Agent run identifier. */
public record RunId(String value) implements Identifier {

    public RunId {
        value = Identifiers.requireValid(value, "runId");
    }

    public static RunId create() {
        return new RunId(Identifiers.randomValue());
    }
}
