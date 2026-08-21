package io.haifa.agent.sandbox.host;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProcessTreeParent {
    private ProcessTreeParent() {}

    public static void main(String[] args) throws Exception {
        String javaExecutable = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString();
        Process child =
                new ProcessBuilder(javaExecutable, "-cp", ".", "io.haifa.agent.sandbox.host.SleepProcess").start();
        Files.writeString(Path.of(args[0]), Long.toString(child.pid()));
        Thread.sleep(60_000);
    }
}
