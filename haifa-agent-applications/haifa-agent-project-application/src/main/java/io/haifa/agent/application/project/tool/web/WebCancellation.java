package io.haifa.agent.application.project.tool.web;

@FunctionalInterface
public interface WebCancellation {
    boolean isCancellationRequested();
}
