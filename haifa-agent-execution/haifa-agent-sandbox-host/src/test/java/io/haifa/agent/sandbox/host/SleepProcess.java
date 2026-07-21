package io.haifa.agent.sandbox.host;

public final class SleepProcess {
    private SleepProcess() {}

    public static void main(String[] args) throws Exception {
        Thread.sleep(30_000);
    }
}
