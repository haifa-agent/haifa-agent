package io.haifa.agent.sandbox.host;

public final class StdinEchoProcess {
    private StdinEchoProcess() {}

    public static void main(String[] args) throws Exception {
        System.out.write(System.in.readAllBytes());
    }
}
