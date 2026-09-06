package io.haifa.agent.project.spi;

import io.haifa.agent.project.binding.WorkspaceLocationRef;

/** Provider-neutral presence check; physical locations remain private to each provider adapter. */
public interface WorkspaceLocationStore {
    boolean contains(WorkspaceLocationRef reference);
}
