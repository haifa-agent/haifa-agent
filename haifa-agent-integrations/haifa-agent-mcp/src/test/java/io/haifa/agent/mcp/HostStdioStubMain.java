package io.haifa.agent.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** JDK-only MCP stdio stub copied into a guarded Host workspace by the component test. */
public final class HostStdioStubMain {
    private static final Pattern ID = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|-?\\d+)");
    private static final Pattern METHOD = Pattern.compile("\\\"method\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private HostStdioStubMain() {}

    public static void main(String[] args) throws Exception {
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                var id = ID.matcher(line);
                var method = METHOD.matcher(line);
                if (!id.find() || !method.find()) continue;
                String result =
                        switch (method.group(1)) {
                            case "initialize" ->
                                "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{\"listChanged\":false}},\"serverInfo\":{\"name\":\"host-stdio-stub\",\"version\":\"1.0.0\"}}";
                            case "tools/list" ->
                                "{\"tools\":[{\"name\":\"echo\",\"description\":\"echo\",\"inputSchema\":{\"type\":\"object\"},\"outputSchema\":{\"type\":\"object\"}}]}";
                            case "tools/call" ->
                                "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"structuredContent\":{\"value\":\"hello\"},\"isError\":false}";
                            default -> "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"unsupported\"}]}";
                        };
                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":" + result + "}");
                System.out.flush();
            }
        }
    }
}
