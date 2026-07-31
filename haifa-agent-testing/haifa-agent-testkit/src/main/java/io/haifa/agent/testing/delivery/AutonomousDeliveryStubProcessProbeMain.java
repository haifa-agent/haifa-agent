package io.haifa.agent.testing.delivery;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Process-tree fixture used by the executable Stub Gate to prove parent-first-exit convergence. */
public final class AutonomousDeliveryStubProcessProbeMain {
    private AutonomousDeliveryStubProcessProbeMain() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1 || !java.util.List.of("parent-exits", "child").contains(arguments[0])) {
            throw new IllegalArgumentException("expected parent-exits or child");
        }
        if (arguments[0].equals("parent-exits")) {
            String java = Path.of(System.getProperty("java.home"), "bin", executable("java"))
                    .toString();
            new ProcessBuilder(
                            java,
                            "-cp",
                            System.getProperty("java.class.path"),
                            AutonomousDeliveryStubProcessProbeMain.class.getName(),
                            "child")
                    .start();
            Thread.sleep(500);
            return;
        }
        Thread.sleep(TimeUnit.MINUTES.toMillis(5));
    }

    private static String executable(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("windows") ? name + ".exe" : name;
    }
}
