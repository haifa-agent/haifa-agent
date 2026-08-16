package io.haifa.agent.testing.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResolvedTestPlanTest {
    @Test
    void canonicalizesMapOrderAndVerifiesIntegrity() {
        ResolvedTestPlan first = ResolvedTestPlan.freeze(Map.of("suite", "a", "budget", 1));
        LinkedHashMap<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("budget", 1);
        reversed.put("suite", "a");
        ResolvedTestPlan second = ResolvedTestPlan.freeze(reversed);

        assertEquals(first.sha256(), second.sha256());
        first.verifyIntegrity();
        assertThrows(IllegalArgumentException.class, () -> new ResolvedTestPlan(
                        1, Map.of("schemaVersion", 1, "suite", "changed"), first.sha256())
                .verifyIntegrity());
    }

    @Test
    void bindsEveryReviewedInputAndExactApproval() {
        ResolvedTestPlan first = ResolvedTestPlan.freeze(Map.of("suite", "a"));
        ResolvedTestPlan changed = ResolvedTestPlan.freeze(Map.of("suite", "b"));

        assertNotEquals(first.sha256(), changed.sha256());
        first.requireApproved(first.sha256());
        assertThrows(IllegalArgumentException.class, () -> first.requireApproved(changed.sha256()));
    }
}
