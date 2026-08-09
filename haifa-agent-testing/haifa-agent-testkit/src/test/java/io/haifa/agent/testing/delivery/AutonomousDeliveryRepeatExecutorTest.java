package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryRepeatExecutorTest {
    @Test
    void boundedCaseTenDoesNotOverrideFailedHiddenAcceptance() {
        assertFalse(AutonomousDeliveryRepeatExecutor.gateEligible(false, true, true, true, true));
    }

    @Test
    void gateRequiresEveryMandatoryCondition() {
        assertTrue(AutonomousDeliveryRepeatExecutor.gateEligible(true, true, true, true, true));
        assertFalse(AutonomousDeliveryRepeatExecutor.gateEligible(true, false, true, true, true));
        assertFalse(AutonomousDeliveryRepeatExecutor.gateEligible(true, true, false, true, true));
        assertFalse(AutonomousDeliveryRepeatExecutor.gateEligible(true, true, true, false, true));
        assertFalse(AutonomousDeliveryRepeatExecutor.gateEligible(true, true, true, true, false));
    }

    @Test
    void forcesUtf8ForPythonGradersOnWindows() {
        Map<String, String> environment = new HashMap<>();

        AutonomousDeliveryRepeatExecutor.configurePythonUtf8(environment);

        assertEquals("1", environment.get("PYTHONUTF8"));
        assertEquals("utf-8", environment.get("PYTHONIOENCODING"));
    }
}
