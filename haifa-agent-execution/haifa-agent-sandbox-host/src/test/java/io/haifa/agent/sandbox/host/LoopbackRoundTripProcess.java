package io.haifa.agent.sandbox.host;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class LoopbackRoundTripProcess {
    private LoopbackRoundTripProcess() {}

    public static void main(String[] args) throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 1, loopback)) {
            var exchange = executor.submit(() -> {
                try (Socket accepted = server.accept();
                        var input = new BufferedReader(
                                new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                        var output = new OutputStreamWriter(accepted.getOutputStream(), StandardCharsets.UTF_8)) {
                    String request = input.readLine();
                    output.write("pong:" + request + "\n");
                    output.flush();
                    return request;
                }
            });

            try (var client = new Socket(loopback, server.getLocalPort());
                    var output = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);
                    var input = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
                client.setSoTimeout((int) Duration.ofSeconds(5).toMillis());
                output.write("ping\n");
                output.flush();
                if (!"pong:ping".equals(input.readLine())) {
                    throw new IllegalStateException("loopback response mismatch");
                }
            }

            if (!"ping".equals(exchange.get(5, TimeUnit.SECONDS))) {
                throw new IllegalStateException("loopback request mismatch");
            }
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
        System.out.print("loopback-round-trip-ok");
    }
}
