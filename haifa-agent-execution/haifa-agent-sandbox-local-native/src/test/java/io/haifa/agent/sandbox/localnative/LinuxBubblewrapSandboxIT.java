package io.haifa.agent.sandbox.localnative;

import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxBubblewrapSandboxIT {
    @TempDir
    Path temporary;

    @Test
    void enforcesWorkspaceSensitiveNetworkAndProcessTreeBoundaries() throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"));
        LocalNativeOsIsolationSupport.verify(temporary, "linux-bubblewrap");
    }

    @Test
    void allowsWorkspaceScratchLoopbackAndNaturalChildCompletion() throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"));
        LocalNativeOsIsolationSupport.verifyLinuxHappyPath(temporary);
    }
}
