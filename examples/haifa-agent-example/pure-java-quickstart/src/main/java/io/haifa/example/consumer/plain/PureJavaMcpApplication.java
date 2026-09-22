package io.haifa.example.consumer.plain;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.starter.HaifaAgentStarter;
import io.haifa.agent.starter.McpServerSpec;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Complete pure Java consumer application that uses a remote MCP server as a Tool source.
 *
 * <p>Haifa is the MCP Client here: it connects the declared server, imports only the allowlisted
 * Tools under a stable name prefix, and releases every connection when the Agent closes.
 *
 * <p>This example needs an MCP server that nobody can assume is running, so it skips itself unless
 * {@code PARTNER_MCP_URL} points at a Streamable HTTP MCP endpoint. Set {@code PARTNER_MCP_TOKEN} as
 * well when that server requires a bearer token, and adjust {@link #ALLOWED_TOOLS} to the Tool names
 * your server actually publishes.
 */
public final class PureJavaMcpApplication {
    /** Environment variable holding the Streamable HTTP MCP endpoint; absent means skip. */
    public static final String ENDPOINT_ENVIRONMENT_VARIABLE = "PARTNER_MCP_URL";

    /** Environment variable holding the optional bearer token for that endpoint. */
    public static final String TOKEN_ENVIRONMENT_VARIABLE = "PARTNER_MCP_TOKEN";

    private static final List<String> ALLOWED_TOOLS = List.of("search_courses", "search_policies", "search_jobs");
    private static final String QUESTION = "上海有哪些适合钣金工和电焊工的工作岗位";

    private PureJavaMcpApplication() {}

    public static void main(String[] arguments) throws Exception {
        String endpoint = System.getenv(ENDPOINT_ENVIRONMENT_VARIABLE);
        if (endpoint == null || endpoint.isBlank()) {
            System.out.println("Skipped: set " + ENDPOINT_ENVIRONMENT_VARIABLE
                    + " to a Streamable HTTP MCP endpoint to run this example.");
            return;
        }
        run(endpoint, System.getenv(TOKEN_ENVIRONMENT_VARIABLE));
    }

    /**
     * Declares the MCP server, builds the Agent and answers one question with its Tools.
     *
     * @param endpoint Streamable HTTP MCP endpoint
     * @param bearerToken bearer token, or {@code null} when the server needs no credential
     */
    public static void run(String endpoint, String bearerToken) throws Exception {
        var search = McpServerSpec.streamableHttp("enterprise-search", URI.create(endpoint))
                .allowTools(ALLOWED_TOOLS)
                .toolNamePrefix("enterprise")
                .readOnly()
                .required();
        if (bearerToken != null && !bearerToken.isBlank()) {
            search = search.bearerTokenFromEnvironment(TOKEN_ENVIRONMENT_VARIABLE);
        }

        try (var agent = HaifaAgentStarter.builder()
                .name("enterprise-search-agent")
                .instructions(
                        """
                        You are an enterprise search assistant.
                        Use available search tools for real business data.
                        Base answers on tool results.
                        Do not invent business facts.
                        """)
                .mcpServer(search)
                .build()) {
            agent.diagnostics().forEach(diagnostic -> System.out.println(
                    "[" + diagnostic.severity() + "] " + diagnostic.code() + ": " + diagnostic.safeMessage()));

            var chat = agent.chat(QUESTION);
            approveToolCalls(agent, chat.runId());
            System.out.println(chat.await().text());
        }
    }

    /**
     * Approves the Tool calls this example expects.
     *
     * <p>The Starter installs the standard approval preset, so any Tool that reaches the network —
     * every remote MCP Tool — asks before it runs. A real application shows the prompt to a person;
     * an unattended service configures its own Policy rules through {@code haifa-agent-sdk} rather
     * than approving blindly the way this example does.
     */
    private static void approveToolCalls(HaifaAgent agent, AgentRunId runId) throws InterruptedException {
        int approvals = 0;
        while (!agent.runs()
                .find(runId)
                .map(snapshot -> snapshot.status().isTerminal())
                .orElse(false)) {
            var pending = agent.runs().pendingInteraction(runId);
            if (pending.isEmpty()) {
                Thread.sleep(50);
                continue;
            }
            var interaction = pending.orElseThrow();
            System.out.println("Approving: " + interaction.safePrompt());
            agent.runs()
                    .respond(new InteractionResponseSubmission(
                            new InteractionResponseId("mcp-approval-" + (++approvals)),
                            interaction.requestId(),
                            runId,
                            interaction.revision(),
                            InteractionAction.APPROVE,
                            List.of(),
                            "mcp-approval-key-" + approvals,
                            Instant.now()));
        }
    }
}
