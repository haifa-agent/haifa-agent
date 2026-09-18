package io.haifa.agent.sandbox.host;

public final class LargeOutputProcess {
    private LargeOutputProcess() {}

    public static void main(String[] args) throws Exception {
        System.out.print("BEGIN-");
        while (true) {
            System.out.print("x".repeat(4096));
            System.out.flush();
            Thread.sleep(1);
        }
    }
}
