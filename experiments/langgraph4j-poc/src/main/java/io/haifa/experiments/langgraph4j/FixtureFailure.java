package io.haifa.experiments.langgraph4j;

import java.util.Objects;

record FixtureFailure(String code, String nodeId) {
    FixtureFailure {
        code = Objects.requireNonNull(code, "code must not be null");
        nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
    }
}
