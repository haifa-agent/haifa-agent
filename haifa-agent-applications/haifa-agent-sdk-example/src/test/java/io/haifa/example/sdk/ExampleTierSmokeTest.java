package io.haifa.example.sdk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.haifa.example.sdk.advanced.SafeErrorHandlingExample;
import io.haifa.example.sdk.basic.AgentReuseLifecycleExample;
import io.haifa.example.sdk.basic.MultiTurnConversationExample;
import io.haifa.example.sdk.intermediate.ComplexRecordSchemaExample;
import io.haifa.example.sdk.intermediate.MultiModelProviderExample;
import io.haifa.example.sdk.intermediate.MultiToolCollaborationExample;
import io.haifa.example.sdk.intermediate.PromptDiagnosticsExample;
import io.haifa.example.sdk.intermediate.StarterCustomizationExample;
import io.haifa.example.sdk.intermediate.StructuredOutputExample;
import io.haifa.example.sdk.intermediate.TypedJavaToolExample;
import io.haifa.example.sdk.intermediate.TypedModelConfigurationExample;
import org.junit.jupiter.api.Test;

class ExampleTierSmokeTest {
    @Test
    void runsNetworkFreeBasicAndIntermediateExamples() {
        assertDoesNotThrow(() -> MultiTurnConversationExample.main(new String[0]));
        assertDoesNotThrow(() -> AgentReuseLifecycleExample.main(new String[0]));
        assertDoesNotThrow(() -> TypedJavaToolExample.main(new String[0]));
        assertDoesNotThrow(() -> StructuredOutputExample.main(new String[0]));
        assertDoesNotThrow(() -> MultiToolCollaborationExample.main(new String[0]));
        assertDoesNotThrow(() -> ComplexRecordSchemaExample.main(new String[0]));
        assertDoesNotThrow(() -> StarterCustomizationExample.main(new String[0]));
        assertDoesNotThrow(() -> MultiModelProviderExample.main(new String[0]));
        assertDoesNotThrow(() -> TypedModelConfigurationExample.main(new String[0]));
        assertDoesNotThrow(() -> PromptDiagnosticsExample.main(new String[0]));
        assertDoesNotThrow(() -> SafeErrorHandlingExample.main(new String[0]));
    }
}
