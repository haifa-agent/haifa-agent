package io.haifa.agent.testing.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RunModeTest {
    @Test
    void appliesModeSpecificGovernance() {
        assertMode("dev", false, false, false, false);
        assertMode("live", true, true, true, false);
        assertMode("release", true, true, true, true);
    }

    @Test
    void lowerModesCannotSatisfyHigherMinimums() {
        assertFalse(RunMode.LIVE.atLeast(RunMode.RELEASE));
        assertFalse(RunMode.DEV.atLeast(RunMode.LIVE));
        assertFalse(RunMode.DEV.atLeast(RunMode.RELEASE));
    }

    @org.junit.jupiter.api.Test
    void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> RunMode.parse("unsafe"));
    }

    private static void assertMode(
            String value, boolean budget, boolean externalRoot, boolean planApproval, boolean fullInventory) {
        RunMode mode = RunMode.parse(value);
        assertTrue(mode.atLeast(RunMode.DEV));
        org.junit.jupiter.api.Assertions.assertEquals(budget, mode.requiresBudgetApproval());
        org.junit.jupiter.api.Assertions.assertEquals(externalRoot, mode.requiresExternalRunRoot());
        org.junit.jupiter.api.Assertions.assertEquals(planApproval, mode.requiresPlanApproval());
        org.junit.jupiter.api.Assertions.assertEquals(fullInventory, mode.requiresFullAssetInventory());
    }
}
