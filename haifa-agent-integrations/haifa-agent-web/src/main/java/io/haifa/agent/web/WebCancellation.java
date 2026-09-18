package io.haifa.agent.web;

@FunctionalInterface
public interface WebCancellation {
    boolean isCancellationRequested();
}
