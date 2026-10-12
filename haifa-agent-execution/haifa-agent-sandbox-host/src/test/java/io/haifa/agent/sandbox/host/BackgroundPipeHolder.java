package io.haifa.agent.sandbox.host;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Starts a child that inherits this process's stdout/stderr (the Sandbox output pipes), records the child pid,
 * lingers briefly so the host can observe the descendant while this process is still alive, then exits. The child
 * keeps the inherited pipes open after this process is gone, reproducing a command that leaves a surviving
 * descendant holding the output pipe.
 */
public final class BackgroundPipeHolder {
    private BackgroundPipeHolder() {}

    public static void main(String[] args) throws Exception {
        String javaExecutable = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString();
        Process child = new ProcessBuilder(javaExecutable, "-cp", ".", "io.haifa.agent.sandbox.host.SleepProcess")
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        Files.writeString(Path.of(args[0]), Long.toString(child.pid()));
        Thread.sleep(2_000);
    }
}
