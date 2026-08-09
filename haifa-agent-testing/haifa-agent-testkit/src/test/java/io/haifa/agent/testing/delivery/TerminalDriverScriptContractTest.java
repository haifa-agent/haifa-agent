package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TerminalDriverScriptContractTest {
    @Test
    void windowsDriverRecognizesStatesOnlyOnTheTuiStatusRow() throws IOException {
        String driver = resource("autonomous-delivery/run_terminal.mjs");

        assertTrue(driver.contains("STATUS_ROW_SEQUENCE"));
        assertTrue(driver.contains("findStatusMarker"));
        assertTrue(driver.contains("visibleStatus.startsWith"));
        assertFalse(driver.contains("state.output.indexOf(marker"));
    }

    @Test
    void posixDriverRecognizesStatesOnlyOnTheTuiStatusRow() throws IOException {
        String driver = resource("autonomous-delivery/run_terminal.py");

        assertTrue(driver.contains("STATUS_ROW_SEQUENCE"));
        assertTrue(driver.contains("status_pattern"));
        assertFalse(driver.contains("expect_exact(marker.encode"));
    }

    private static String resource(String name) throws IOException {
        try (InputStream input =
                TerminalDriverScriptContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("missing testkit resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
