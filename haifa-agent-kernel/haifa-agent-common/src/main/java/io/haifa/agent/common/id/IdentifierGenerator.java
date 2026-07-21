package io.haifa.agent.common.id;

/** Generates opaque identifier values outside domain aggregates. */
@FunctionalInterface
public interface IdentifierGenerator {

    String nextValue();
}
