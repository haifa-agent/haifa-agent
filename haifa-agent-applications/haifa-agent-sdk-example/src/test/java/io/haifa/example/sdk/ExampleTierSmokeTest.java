package io.haifa.example.sdk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.haifa.example.sdk.basic.MultiTurnConversationExample;
import io.haifa.example.sdk.intermediate.MultiModelProviderExample;
import io.haifa.example.sdk.intermediate.TypedJavaToolExample;
import org.junit.jupiter.api.Test;

class ExampleTierSmokeTest {
    @Test
    void runsNetworkFreeBasicAndIntermediateExamples() {
        assertDoesNotThrow(() -> MultiTurnConversationExample.main(new String[0]));
        assertDoesNotThrow(() -> TypedJavaToolExample.main(new String[0]));
        assertDoesNotThrow(() -> MultiModelProviderExample.main(new String[0]));
    }
}
