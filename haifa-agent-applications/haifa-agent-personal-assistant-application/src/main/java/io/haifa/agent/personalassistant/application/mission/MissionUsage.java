package io.haifa.agent.personalassistant.application.mission;

/** Authoritative Runtime usage accumulated only when a Run settlement is committed. */
public record MissionUsage(long modelTokens, long modelCalls, long toolCalls) {
    public static final MissionUsage NONE = new MissionUsage(0, 0, 0);

    public MissionUsage {
        if (modelTokens < 0 || modelCalls < 0 || toolCalls < 0) {
            throw new IllegalArgumentException("Mission usage must not be negative");
        }
    }
}
