package io.haifa.agent.sandbox.localnative;

import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MacSeatbeltSandboxIT {
    @TempDir
    Path temporary;

    @Test
    void enforcesWorkspaceSensitiveNetworkAndProcessTreeBoundaries() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Assumptions.assumeTrue(os.contains("mac") || os.contains("darwin"));
        LocalNativeOsIsolationSupport.verify(temporary, "mac-seatbelt");
    }
}
