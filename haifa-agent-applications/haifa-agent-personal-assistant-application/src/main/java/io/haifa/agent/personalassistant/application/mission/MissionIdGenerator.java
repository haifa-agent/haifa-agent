package io.haifa.agent.personalassistant.application.mission;

@FunctionalInterface
public interface MissionIdGenerator {
    String nextId();
}
