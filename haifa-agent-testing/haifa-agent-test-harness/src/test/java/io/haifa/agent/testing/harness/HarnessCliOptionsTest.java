package io.haifa.agent.testing.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HarnessCliOptionsTest {
    @Test
    void exposesOnlyPlanAndRunActions() {
        HarnessCliOptions plan = HarnessCliOptions.parse(
                new String[] {
                    "plan",
                    "--run-root",
                    "runs",
                    "--suite",
                    "suite-v1",
                    "--profile",
                    "profile-v1",
                    "--platform",
                    "windows-primary",
                    "--mode",
                    "live"
                },
                Map.of());
        HarnessCliOptions run = HarnessCliOptions.parse(
                new String[] {"run", "--plan", "execution-plan.json", "--approve-budget", "5"}, Map.of());

        assertEquals("plan", plan.action());
        assertEquals(RunMode.LIVE, plan.mode());
        assertEquals(Path.of("execution-plan.json"), run.plan());
        assertThrows(
                IllegalArgumentException.class, () -> HarnessCliOptions.parse(new String[] {"phase-1-gate"}, Map.of()));
    }

    @Test
    void rejectsLegacyExecuteAndUnknownOptions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HarnessCliOptions.parse(
                        new String[] {
                            "plan",
                            "--run-root",
                            "runs",
                            "--suite",
                            "suite-v1",
                            "--profile",
                            "profile-v1",
                            "--platform",
                            "windows-primary",
                            "--mode",
                            "live",
                            "--execute",
                            "true"
                        },
                        Map.of()));
    }
}
