package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CriticalPathCatalogTest {
    @Test
    void criticalPathV1ContainsExactlyElevenStableCases() {
        assertEquals(
                IntStream.rangeClosed(1, 11)
                        .mapToObj(value -> "CP-%02d".formatted(value))
                        .sorted()
                        .toList(),
                CriticalPathCatalog.cases().stream()
                        .map(CriticalPathCase::caseId)
                        .sorted()
                        .toList());
    }

    @Test
    void unknownCaseFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> CriticalPathCatalog.require("CP-99"));
    }
}
