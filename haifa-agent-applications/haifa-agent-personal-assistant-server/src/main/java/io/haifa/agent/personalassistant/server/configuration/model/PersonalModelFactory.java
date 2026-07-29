package io.haifa.agent.personalassistant.server.configuration.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.EnvironmentCredentialResolver;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.personalassistant.server.observability.LoggingAgentChatModel;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.contribution.ShellPlatformContribution;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Creates either the production remote adapter or an explicitly enabled deterministic acceptance model. */
public final class PersonalModelFactory {
    private PersonalModelFactory() {}

    public static ModelContribution create(
            PersonalAssistantProperties.Model properties, ObjectMapper mapper, ShellPlatformContribution shell) {
        boolean deterministic = "deterministic".equals(properties.mode());
        String adapter = deterministic ? "personal-deterministic" : "openai-compatible";
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId(deterministic ? "personal-local" : "deepseek"),
                "1.0.0",
                new ModelDefinitionId("personal-chat"),
                "1.0.0",
                properties.providerModelId(),
                adapter,
                "1.0.0",
                properties.endpoint(),
                new CredentialRef(properties.credentialReference()),
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                64_000,
                8_192,
                Map.of(),
                Map.of());
        AgentChatModel model = deterministic
                ? new DeterministicAcceptanceModel(properties.providerModelId(), shell)
                : new OpenAiCompatibleChatModel(
                        adapter,
                        "1.0.0",
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(10))
                                .build(),
                        mapper,
                        new EnvironmentCredentialResolver(),
                        false,
                        4 * 1024 * 1024);
        model = new LoggingAgentChatModel(model);
        return new ModelContribution(
                new SdkContributionMetadata(
                        new ProductContributionCoordinate("haifa-personal-model", "1.0.0"),
                        ProductCapabilities.MODEL,
                        snapshot.configurationDigest(),
                        deterministic ? ProductProviderSuitability.DEVELOPMENT : ProductProviderSuitability.PRODUCTION,
                        deterministic ? "Explicit offline acceptance model" : "OpenAI-compatible Personal model"),
                model,
                snapshot);
    }

    /**
     * Test-only-by-configuration model. Markers select one public tool alias, and a following TOOL message
     * always terminates. It never becomes the default production mode.
     */
    private static final class DeterministicAcceptanceModel implements AgentChatModel {
        private final String modelId;
        private final String operatingSystem;
        private final String scriptLanguage;
        private final AtomicLong sequence = new AtomicLong();

        private DeterministicAcceptanceModel(String modelId, ShellPlatformContribution shell) {
            this.modelId = modelId;
            this.operatingSystem = shell.operatingSystem();
            this.scriptLanguage = "WINDOWS".equals(operatingSystem) ? "powershell" : "bash";
            if (!shell.scriptLanguages().contains(scriptLanguage)) {
                throw new IllegalArgumentException(
                        "deterministic acceptance model requires configured script language " + scriptLanguage);
            }
        }

        @Override
        public AgentChatResponse invoke(io.haifa.agent.model.api.AgentChatRequest request) {
            long current = sequence.incrementAndGet();
            if (request.messages().getLast().role() == ModelMessageRole.TOOL) {
                return response(current, "The requested capability completed.", List.of(), ModelFinishReason.STOP);
            }
            String prompt = request.messages().stream()
                    .filter(message -> message.role() == ModelMessageRole.USER)
                    .map(io.haifa.agent.model.api.ModelMessage::content)
                    .reduce((left, right) -> right)
                    .orElse("");
            String alias;
            Map<String, Object> arguments;
            if (prompt.contains("CPU使用率") || prompt.contains("[execution-cpu]")) {
                alias = PersonalAssistantProfile.EXECUTION_TOOL_ALIAS;
                arguments = Map.of(
                        "mode",
                        "SCRIPT",
                        "language",
                        scriptLanguage,
                        "content",
                        cpuObservationScript(),
                        "purpose",
                        "读取当前系统 CPU 使用率与逻辑处理器数量",
                        "timeoutMillis",
                        10_000);
            } else if (prompt.contains("[execution-command]")) {
                alias = PersonalAssistantProfile.EXECUTION_TOOL_ALIAS;
                arguments = Map.of(
                        "mode",
                        "COMMAND",
                        "content",
                        "$PSVersionTable.PSVersion.ToString()",
                        "purpose",
                        "读取当前 PowerShell 版本",
                        "timeoutMillis",
                        5_000);
            } else if (prompt.contains("[execution-script]")) {
                alias = PersonalAssistantProfile.EXECUTION_TOOL_ALIAS;
                arguments = Map.of(
                        "mode",
                        "SCRIPT",
                        "language",
                        scriptLanguage,
                        "content",
                        argumentEchoScript(),
                        "args",
                        List.of("first argument", "second'argument"),
                        "purpose",
                        argumentEchoPurpose(),
                        "timeoutMillis",
                        5_000);
            } else if (prompt.contains("[execution-timeout]")) {
                alias = PersonalAssistantProfile.EXECUTION_TOOL_ALIAS;
                arguments = Map.of(
                        "mode",
                        "SCRIPT",
                        "language",
                        scriptLanguage,
                        "content",
                        timeoutScript(),
                        "purpose",
                        "验证执行超时与进程终止",
                        "timeoutMillis",
                        1_000);
            } else if (prompt.contains("[skill]")) {
                alias = PersonalAssistantProfile.SKILL_LOAD_ALIAS;
                arguments = Map.of("skill", PersonalAssistantProfile.BUNDLED_SKILL_ALIAS);
            } else if (prompt.contains("[mcp]")) {
                alias = PersonalAssistantProfile.MCP_TOOL_ALIAS;
                arguments = Map.of("text", "offline MCP verification");
            } else if (prompt.contains("[tool]")) {
                alias = PersonalAssistantProfile.PRODUCT_TOOL_ALIAS;
                arguments = Map.of("items", List.of("review the plan", "confirm completion"));
            } else {
                return response(current, "Personal Assistant is ready.", List.of(), ModelFinishReason.STOP);
            }
            return response(
                    current,
                    "",
                    List.of(new ModelToolCall(
                            new ProviderToolCallCorrelationId("personal-call-" + current), alias, arguments)),
                    ModelFinishReason.TOOL_CALLS);
        }

        private AgentChatResponse response(
                long id, String content, List<ModelToolCall> calls, ModelFinishReason reason) {
            return new AgentChatResponse(
                    "personal-response-" + id,
                    modelId,
                    content,
                    calls,
                    reason,
                    ModelUsage.unpriced(12, Math.max(1, content.length() / 4)),
                    "",
                    Map.of("deterministic", true));
        }

        private String cpuObservationScript() {
            return switch (operatingSystem) {
                case "WINDOWS" ->
                    """
                        $sample = Get-CimInstance Win32_Processor |
                          Measure-Object -Property LoadPercentage -Average
                        [pscustomobject]@{
                          CpuUsagePercent = [math]::Round($sample.Average, 1)
                          LogicalProcessors = [Environment]::ProcessorCount
                        } | ConvertTo-Json -Compress
                        """;
                case "MACOS" ->
                    """
                        cpu_usage=$(top -l 2 -n 0 | awk '/CPU usage/ { idle=$7 } END {
                          gsub("%", "", idle)
                          printf "%.1f", 100 - idle
                        }')
                        logical_processors=$(sysctl -n hw.logicalcpu)
                        printf '{"cpuUsagePercent":%s,"logicalProcessors":%s}\n' \
                          "$cpu_usage" "$logical_processors"
                        """;
                default ->
                    """
                        read -r _ user nice system idle iowait irq softirq steal _ < /proc/stat
                        total_before=$((user + nice + system + idle + iowait + irq + softirq + steal))
                        idle_before=$((idle + iowait))
                        sleep 1
                        read -r _ user nice system idle iowait irq softirq steal _ < /proc/stat
                        total_after=$((user + nice + system + idle + iowait + irq + softirq + steal))
                        idle_after=$((idle + iowait))
                        total_delta=$((total_after - total_before))
                        idle_delta=$((idle_after - idle_before))
                        cpu_usage=$((100 * (total_delta - idle_delta) / total_delta))
                        logical_processors=$(grep -c '^processor' /proc/cpuinfo)
                        printf '{"cpuUsagePercent":%s,"logicalProcessors":%s}\n' \
                          "$cpu_usage" "$logical_processors"
                        """;
            };
        }

        private String argumentEchoScript() {
            return "WINDOWS".equals(operatingSystem) ? "$args -join '|'" : "printf '%s|%s' \"$1\" \"$2\"";
        }

        private String argumentEchoPurpose() {
            return "验证 " + ("WINDOWS".equals(operatingSystem) ? "PowerShell" : "Bash") + " 脚本参数通过 stdin 安全传递";
        }

        private String timeoutScript() {
            return "WINDOWS".equals(operatingSystem)
                    ? "Start-Sleep -Seconds 5; 'unexpected completion'"
                    : "sleep 5; printf 'unexpected completion'";
        }
    }
}
