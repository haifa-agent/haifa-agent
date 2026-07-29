package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PythonJsonAcceptanceGraderTest {
    @Test
    void parsesBoundedStructuredFailureWithoutRawOutput() throws Exception {
        byte[] output =
                """
                {"case":"01-python-debug","passed":false,"checks":{"visible":false},"failures":["visible"]}
                """
                        .getBytes(StandardCharsets.UTF_8);

        AutonomousDeliveryAcceptanceGrade grade = new PythonJsonAcceptanceGrader()
                .parse(AutonomousDeliveryCaseCatalog.loadVerified().require("01"), output, 1, 25);

        assertFalse(grade.passed());
    }

    @Test
    void rejectsNonJsonOracleOutput() {
        assertThrows(java.io.IOException.class, () -> new PythonJsonAcceptanceGrader()
                .parse(
                        AutonomousDeliveryCaseCatalog.loadVerified().require("01"),
                        "not-json".getBytes(StandardCharsets.UTF_8),
                        1,
                        25));
    }
}
