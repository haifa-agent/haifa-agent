package io.haifa.agent.personalassistant.application;

import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.haifa.agent.mcp.config.StreamableHttpDefinition;
import io.haifa.agent.mcp.tool.McpToolImportCandidate;
import io.haifa.agent.personalassistant.application.mcp.PersonalMcpPlatform;
import io.haifa.agent.personalassistant.application.tool.PersonalToolPlatform;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillDiagnostic;
import io.haifa.agent.skill.api.SkillResourceRef;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable product-facing view of the capability registrations frozen during Personal Assistant assembly.
 *
 * <p>The view contains definitions, hashes, policies, and safe transport identity. It never contains credential
 * values, live leases, MCP session identifiers, or provider response payloads.
 */
public record PersonalCapabilityRegistry(
        String toolCatalogDigest,
        String skillCatalogDigest,
        String skillResolutionPolicy,
        List<CapabilityRegistration> registrations) {
    public PersonalCapabilityRegistry {
        toolCatalogDigest = text(toolCatalogDigest, "toolCatalogDigest");
        skillCatalogDigest = text(skillCatalogDigest, "skillCatalogDigest");
        skillResolutionPolicy = text(skillResolutionPolicy, "skillResolutionPolicy");
        registrations = List.copyOf(Objects.requireNonNull(registrations, "registrations"));
    }

    public static PersonalCapabilityRegistry create(PersonalToolPlatform platform, PersonalMcpPlatform mcp) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(mcp, "mcp");
        var toolSnapshot = platform.tool().catalog().snapshot();
        var skillSnapshot = platform.skill().catalog().snapshot();
        List<CapabilityRegistration> registrations = new ArrayList<>();
        toolSnapshot.bindings().stream().map(PersonalCapabilityRegistry::tool).forEach(registrations::add);
        registrations.add(mcp(mcp));
        skillSnapshot.bindings().stream()
                .map(binding -> skill(binding, skillSnapshot.diagnostics()))
                .forEach(registrations::add);
        registrations.sort(
                Comparator.comparing(CapabilityRegistration::kind).thenComparing(CapabilityRegistration::name));
        return new PersonalCapabilityRegistry(
                toolSnapshot.digest(),
                skillSnapshot.digest().value(),
                skillSnapshot.resolutionPolicyRef(),
                registrations);
    }

    private static CapabilityRegistration tool(FrozenToolBinding binding) {
        var definition = binding.definition();
        List<CapabilityAttribute> attributes = List.of(
                attribute("Alias", binding.alias().value(), "neutral"),
                attribute("Version", definition.version().value(), "neutral"),
                attribute("Provider", definition.providerId().value(), "neutral"),
                attribute(
                        "Risk",
                        definition.risk().name(),
                        riskTone(definition.risk().name())),
                attribute("Approval", definition.approvalRequirement().name(), "neutral"),
                attribute("Execution", definition.executionMode().name(), "neutral"));
        Map<String, Object> identity = map(
                "alias", binding.alias().value(),
                "coordinate", binding.coordinate().externalForm(),
                "name", definition.name().value(),
                "version", definition.version().value(),
                "providerId", definition.providerId().value(),
                "definitionHash", binding.coordinate().definitionHash().value(),
                "providerBindingReference", binding.providerBindingReference(),
                "catalogDigest", binding.catalogDigest());
        Map<String, Object> behavior = map(
                "executionMode", definition.executionMode().name(),
                "cancellationSupported", definition.cancellationSupported(),
                "timeoutMillis", definition.timeout().toMillis(),
                "concurrencyPolicy", definition.concurrencyPolicy(),
                "idempotency", definition.idempotency().name(),
                "risk", definition.risk().name(),
                "sideEffects", names(definition.sideEffects()),
                "approvalRequirement", definition.approvalRequirement().name(),
                "deprecated", definition.deprecated());
        Map<String, Object> resources = map(
                "filesystemCapabilities", sorted(definition.resources().filesystemCapabilities()),
                "networkHosts", sorted(definition.resources().networkHosts()),
                "executionProfiles", sorted(definition.resources().executionProfiles()),
                "credentialRequirements",
                        definition.credentialRequirements().stream()
                                .map(PersonalCapabilityRegistry::credential)
                                .toList());
        Map<String, Object> schemas = map(
                "input", schema(definition.inputSchema()),
                "output", schema(definition.outputSchema()));
        return new CapabilityRegistration(
                "tool:" + binding.alias().value(),
                "TOOL",
                binding.alias().value(),
                definition.title(),
                definition.description(),
                "FROZEN",
                definition.providerId().value(),
                sorted(definition.tags()),
                attributes,
                map(
                        "identity", identity,
                        "behavior", behavior,
                        "resources", resources,
                        "schemas", schemas,
                        "provenance", definition.provenance(),
                        "tags", sorted(definition.tags())));
    }

    private static CapabilityRegistration mcp(PersonalMcpPlatform mcp) {
        var server = mcp.server();
        var snapshot = mcp.serverSnapshot();
        String transportType = server.transport() instanceof StreamableHttpDefinition
                ? "STREAMABLE_HTTP"
                : server.transport() instanceof StdioDefinition ? "STDIO" : "UNKNOWN";
        List<Map<String, Object>> importedTools = mcp.candidates().stream()
                .map(PersonalCapabilityRegistry::mcpTool)
                .toList();
        List<CapabilityAttribute> attributes = List.of(
                attribute("Server ID", server.serverId().value(), "neutral"),
                attribute("Transport", transportType, "neutral"),
                attribute("Protocol", snapshot.negotiatedProtocolVersion(), "succeeded"),
                attribute("State", "READY", "succeeded"),
                attribute("Imported tools", Integer.toString(importedTools.size()), "neutral"));
        Map<String, Object> connection = map(
                "transportType", transportType,
                "transportIdentityReference", server.transport().identityReference(),
                "targetProtocolVersion", snapshot.targetProtocolVersion(),
                "negotiatedProtocolVersion", snapshot.negotiatedProtocolVersion(),
                "serverName", snapshot.serverName(),
                "serverVersion", snapshot.serverVersion(),
                "connectTimeoutMillis",
                        server.connectionPolicy().connectTimeout().toMillis(),
                "requestTimeoutMillis",
                        server.connectionPolicy().requestTimeout().toMillis(),
                "idleTimeoutMillis", server.connectionPolicy().idleTimeout().toMillis(),
                "shutdownTimeoutMillis",
                        server.connectionPolicy().shutdownTimeout().toMillis(),
                "maximumReconnectAttempts", server.connectionPolicy().maxReconnectAttempts());
        Map<String, Object> capabilities = map(
                "tools", snapshot.toolsCapability(),
                "toolsListChanged", snapshot.toolsListChanged(),
                "resources", snapshot.resourcesCapability(),
                "prompts", snapshot.promptsCapability());
        Map<String, Object> importPolicy = map(
                "allowedTools", sorted(server.importPolicy().allowedTools()),
                "deniedTools", sorted(server.importPolicy().deniedTools()),
                "aliasNamespace", server.importPolicy().aliasNamespace());
        return new CapabilityRegistration(
                "mcp:" + server.serverId().value(),
                "MCP",
                server.serverId().value(),
                server.displayName(),
                "Reviewed MCP server registration and its imported Tool bindings.",
                "READY",
                server.transport().identityReference(),
                List.of("mcp", transportType.toLowerCase(java.util.Locale.ROOT)),
                attributes,
                map(
                        "identity",
                                map(
                                        "serverId", server.serverId().value(),
                                        "displayName", server.displayName(),
                                        "enabled", server.enabled(),
                                        "bindingVersion", server.bindingVersion(),
                                        "bindingReference", server.bindingReference(),
                                        "bindingDigest", server.bindingDigest()),
                        "connection", connection,
                        "capabilities", capabilities,
                        "importPolicy", importPolicy,
                        "importedTools", importedTools));
    }

    private static Map<String, Object> mcpTool(McpToolImportCandidate candidate) {
        var binding = candidate.binding().orElseThrow();
        var definition = candidate.definition().orElseThrow();
        return map(
                "remoteName", candidate.remoteName(),
                "alias", candidate.alias().orElseThrow().value(),
                "enabled", candidate.enabled(),
                "remoteDefinitionDigest", candidate.remoteDefinitionDigest(),
                "bindingReference", binding.bindingReference(),
                "bindingDigest", binding.bindingDigest(),
                "localName", definition.name().value(),
                "localVersion", definition.version().value(),
                "localProviderId", definition.providerId().value(),
                "localDefinitionHash", binding.localDefinitionHash().value(),
                "targetProtocolVersion", binding.targetProtocolVersion(),
                "negotiatedProtocolVersion", binding.negotiatedProtocolVersion(),
                "transportIdentityReference", binding.transportIdentityReference(),
                "credentialRequirements",
                        binding.credentialRequirements().stream()
                                .map(PersonalCapabilityRegistry::credential)
                                .toList());
    }

    private static CapabilityRegistration skill(FrozenSkillBinding binding, List<SkillDiagnostic> allDiagnostics) {
        var coordinate = binding.coordinate();
        var metadata = binding.metadata();
        List<Map<String, Object>> resources = binding.packageIndex().resources().stream()
                .map(PersonalCapabilityRegistry::resource)
                .toList();
        List<Map<String, Object>> diagnostics = allDiagnostics.stream()
                .filter(value -> value.source().equals(coordinate.source()))
                .filter(value -> value.skill().map(coordinate.name()::equals).orElse(true))
                .map(PersonalCapabilityRegistry::diagnostic)
                .toList();
        String version =
                coordinate.declaredVersion().map(value -> value.value()).orElse("unversioned");
        List<CapabilityAttribute> attributes = List.of(
                attribute("Alias", binding.alias().value(), "neutral"),
                attribute("Version", version, "neutral"),
                attribute("Scope", coordinate.scope().scope().name(), "neutral"),
                attribute("Source", coordinate.source().externalForm(), "neutral"),
                attribute("Resources", Integer.toString(resources.size()), "neutral"),
                attribute("State", "FROZEN", "succeeded"));
        return new CapabilityRegistration(
                "skill:" + binding.alias().value(),
                "SKILL",
                binding.alias().value(),
                metadata.name().value(),
                metadata.description(),
                "FROZEN",
                coordinate.source().externalForm(),
                metadata.toolHints().stream()
                        .map(value -> value.value())
                        .sorted()
                        .toList(),
                attributes,
                map(
                        "identity",
                                map(
                                        "alias", binding.alias().value(),
                                        "coordinate", coordinate.externalForm(),
                                        "scope", coordinate.scope().scope().name(),
                                        "scopeReference", coordinate.scope().externalForm(),
                                        "source", coordinate.source().externalForm(),
                                        "name", coordinate.name().value(),
                                        "declaredVersion", version,
                                        "contentDigest",
                                                coordinate.contentDigest().value(),
                                        "resourceIndexDigest",
                                                binding.resourceIndexDigest().value(),
                                        "registrationDigest",
                                                binding.registrationDigest().value(),
                                        "resolutionPolicy", binding.resolutionPolicyRef()),
                        "metadata",
                                map(
                                        "description", metadata.description(),
                                        "license", metadata.license().orElse(null),
                                        "compatibility",
                                                metadata.compatibility().orElse(null),
                                        "properties", metadata.metadata(),
                                        "toolHints",
                                                metadata.toolHints().stream()
                                                        .map(value -> value.value())
                                                        .sorted()
                                                        .toList()),
                        "package", map("resources", resources),
                        "diagnostics", diagnostics));
    }

    private static Map<String, Object> credential(CredentialRequirement value) {
        return map(
                "definitionId", value.definitionId().value(),
                "purpose", value.purpose(),
                "scopes", sorted(value.scopes()),
                "exposureMode", value.exposureMode().name());
    }

    private static Map<String, Object> resource(SkillResourceRef value) {
        return map(
                "relativePath", value.relativePath(),
                "kind", value.kind().name(),
                "mediaType", value.mediaType(),
                "digest", value.digest().value(),
                "byteSize", value.byteSize(),
                "readableText", value.readableText());
    }

    private static Map<String, Object> diagnostic(SkillDiagnostic value) {
        return map(
                "code", value.code(),
                "severity", value.severity().name(),
                "source", value.source().externalForm(),
                "skill", value.skill().map(skill -> skill.value()).orElse(null),
                "logicalPath", value.logicalPath().orElse(null),
                "message", value.message());
    }

    private static Map<String, Object> schema(io.haifa.agent.tool.api.ToolSchema value) {
        return map("id", value.id(), "version", value.version(), "document", value.document());
    }

    private static List<String> names(java.util.Collection<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().toList();
    }

    private static List<String> sorted(java.util.Collection<String> values) {
        return values.stream().sorted().toList();
    }

    private static CapabilityAttribute attribute(String label, String value, String tone) {
        return new CapabilityAttribute(label, value, tone);
    }

    private static String riskTone(String risk) {
        return switch (risk) {
            case "HIGH", "CRITICAL" -> "failed";
            case "MEDIUM" -> "waiting";
            default -> "succeeded";
        };
    }

    private static Map<String, Object> map(Object... values) {
        if (values.length % 2 != 0) throw new IllegalArgumentException("map entries must be key-value pairs");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            String key = Objects.requireNonNull((String) values[index]);
            Object value = values[index + 1];
            if (value != null) result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public record CapabilityRegistration(
            String id,
            String kind,
            String name,
            String displayName,
            String description,
            String status,
            String source,
            List<String> tags,
            List<CapabilityAttribute> attributes,
            Map<String, Object> details) {
        public CapabilityRegistration {
            id = text(id, "id");
            kind = text(kind, "kind");
            name = text(name, "name");
            displayName = text(displayName, "displayName");
            description = text(description, "description");
            status = text(status, "status");
            source = text(source, "source");
            tags = List.copyOf(tags);
            attributes = List.copyOf(attributes);
            details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
    }

    public record CapabilityAttribute(String label, String value, String tone) {
        public CapabilityAttribute {
            label = text(label, "label");
            value = text(value, "value");
            tone = text(tone, "tone");
        }
    }
}
