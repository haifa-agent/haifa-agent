package io.haifa.agent.sandbox.localnative;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class LocalNativeProcessSupport {
    private LocalNativeProcessSupport() {}

    static void runProbe(List<String> argv) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(true);
            builder.environment().clear();
            process = builder.start();
            process.getOutputStream().close();
            process.getInputStream().readNBytes(4096);
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                if (process.isAlive()) process.destroyForcibly();
                throw unavailable();
            }
        } catch (IOException exception) {
            throw unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) process.destroyForcibly();
            throw unavailable();
        }
    }

    private static LocalNativeSandboxException unavailable() {
        return new LocalNativeSandboxException(
                "SANDBOX_ADAPTER_UNAVAILABLE", "local-native sandbox platform preflight failed");
    }
}
