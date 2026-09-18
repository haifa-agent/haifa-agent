package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Test
    void freezesResolvedToolchainPathForOracleProcess() {
        Map<String, String> environment = new HashMap<>(Map.of("PATH", "untrusted"));

        PythonJsonAcceptanceGrader.configureOracleEnvironment(environment, "resolved-toolchain-path");

        assertEquals("resolved-toolchain-path", environment.get("PATH"));
    }

    @Test
    void enablesUtf8BeforeIsolatedPythonMode() {
        assertEquals(
                List.of("python", "-X", "utf8", "-I", "acceptance.py", "workspace"),
                PythonJsonAcceptanceGrader.oracleCommand(
                        Path.of("python"), Path.of("acceptance.py"), Path.of("workspace")));
    }
}
