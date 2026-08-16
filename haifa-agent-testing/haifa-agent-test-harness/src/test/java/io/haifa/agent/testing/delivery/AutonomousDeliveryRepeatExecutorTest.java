package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
