package io.haifa.agent.core.run;

/** Operating mode for cumulative consumable quota governance. */
public enum QuotaMode {
    DISABLED,
    OBSERVE_ONLY,
    WARN,
    HARD_STOP
}
