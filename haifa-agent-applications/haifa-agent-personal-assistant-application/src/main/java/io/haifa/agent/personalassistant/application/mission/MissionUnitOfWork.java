package io.haifa.agent.personalassistant.application.mission;

import java.util.function.Supplier;

@FunctionalInterface
public interface MissionUnitOfWork {
    <T> T execute(Supplier<T> work);
}
