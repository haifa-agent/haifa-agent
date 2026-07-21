package io.haifa.agent.runtime.core.middleware;

public record RuntimeMiddlewareOrder(int value) implements Comparable<RuntimeMiddlewareOrder> {
    @Override
    public int compareTo(RuntimeMiddlewareOrder other) {
        return Integer.compare(value, other.value);
    }
}
