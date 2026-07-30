package io.haifa.agent.personalassistant.application.tool;

import io.haifa.agent.execution.core.tool.TrustedScriptArguments;
import io.haifa.agent.execution.core.tool.TrustedSkillScriptToolProvider;
import io.haifa.agent.execution.core.tool.TrustedSkillScriptToolSpec;
import io.haifa.agent.personalassistant.application.execution.PersonalExecutionPlatform;
import io.haifa.agent.personalassistant.application.skill.PersonalSkillPlatform;
import io.haifa.agent.personalassistant.application.trust.PersonalTrustedScriptManifest;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillPackageReviewGrant;
import io.haifa.agent.skill.api.SkillScriptExecutionGrant;
import io.haifa.agent.skill.api.SkillTrustDigests;
import io.haifa.agent.skill.api.SkillTrustGrantState;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolCatalog;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Personal finance reference vertical built only from the shared fixed trusted-script facility. */
final class PersonalTrustedFinanceTools {
    private static final Set<String> SUPPORTED = Set.of("stocks_market_data", "excel_recalculate", "dcf_validate");
    private static final String PRODUCT_ID = "haifa-personal-assistant";

    private PersonalTrustedFinanceTools() {}

    static Prepared prepare(PersonalSkillPlatform skills, PersonalExecutionPlatform execution) {
        List<Entry> entries = new ArrayList<>();
        for (PersonalTrustedScriptManifest.ScriptExecution manifest :
                skills.trustManifest().scripts()) {
            if (!SUPPORTED.contains(manifest.capability())) {
                throw new IllegalArgumentException("unsupported Personal trusted script capability");
            }
            SkillPackageReviewGrant packageGrant = skills.packageTrust().packageReviewGrants().stream()
                    .filter(candidate -> candidate.id().equals(manifest.packageReviewGrantId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "trusted script capability references an unavailable package grant"));
            FrozenSkillBinding skill = skills.catalog().snapshot().bindings().stream()
                    .filter(binding -> binding.coordinate().equals(packageGrant.coordinate()))
                    .filter(binding -> binding.packageReviewGrantId()
                            .filter(packageGrant.id()::equals)
                            .isPresent())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "trusted script capability references an unavailable Skill binding"));
            if (!manifest.executionConfigurationDigest()
                    .equals(execution.provider().configurationIdentity())) {
                throw new IllegalArgumentException("trusted script execution configuration digest has drifted");
            }
            String currentSandbox =
                    SkillTrustDigests.sandbox(execution.provider().sandboxProfileIdentity());
            if (!manifest.sandboxDigest().equals(currentSandbox)) {
                throw new IllegalArgumentException("trusted script sandbox digest has drifted");
            }
            ToolDefinition definition =
                    definition(manifest, execution.provider().configurationIdentity(), manifest.sandboxDigest());
            TrustedSkillScriptToolSpec spec = new TrustedSkillScriptToolSpec(
                    definition,
                    skill,
                    manifest.scriptRelativePath(),
                    manifest.scriptContentDigest(),
                    manifest.runtimeRef(),
                    manifest.executionConfigurationDigest(),
                    manifest.sandboxDigest(),
                    Set.copyOf(manifest.capabilities()),
                    definition.timeout(),
                    purpose(manifest.capability()),
                    arguments -> mapArguments(manifest.capability(), arguments));
            entries.add(new Entry(
                    new ToolAlias(manifest.capability()),
                    "personal-trusted-script:" + manifest.id() + ":v" + manifest.version(),
                    manifest,
                    packageGrant,
                    spec));
        }
        if (entries.isEmpty()) return new Prepared(Optional.empty(), List.of());
        var provider = new TrustedSkillScriptToolProvider(
                execution.provider(),
                skills.contentLoader(),
                entries.stream().map(Entry::spec).toList());
        return new Prepared(Optional.of(provider), entries);
    }

    static SkillTrustSnapshot freezeTrust(PersonalSkillPlatform skills, Prepared prepared, ToolCatalog catalog) {
        List<SkillScriptExecutionGrant> grants = prepared.entries().stream()
                .map(entry -> scriptGrant(entry, catalog))
                .toList();
        return new SkillTrustSnapshot(
                skills.trustManifest().digest(), skills.packageTrust().packageReviewGrants(), grants);
    }

    private static SkillScriptExecutionGrant scriptGrant(Entry entry, ToolCatalog catalog) {
        FrozenToolBinding binding = catalog.snapshot().bindings().stream()
                .filter(candidate -> candidate.alias().equals(entry.alias()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("trusted script Tool was not frozen"));
        PersonalTrustedScriptManifest.ScriptExecution manifest = entry.manifest();
        boolean diagnosticBootstrap = manifest.state() == SkillTrustGrantState.REVOKED
                && manifest.expectedToolDefinitionHash().equals("0".repeat(64));
        if (!diagnosticBootstrap
                && !binding.coordinate().definitionHash().value().equals(manifest.expectedToolDefinitionHash())) {
            throw new IllegalArgumentException("trusted script Tool definition hash has drifted");
        }
        if (!Set.copyOf(manifest.networkHosts())
                .equals(binding.definition().resources().networkHosts())) {
            throw new IllegalArgumentException("trusted script network host policy has drifted");
        }
        return new SkillScriptExecutionGrant(
                manifest.id(),
                1,
                manifest.version(),
                entry.packageGrant().id(),
                entry.packageGrant().tenant(),
                entry.packageGrant().principal(),
                PRODUCT_ID,
                manifest.scope(),
                Optional.empty(),
                entry.packageGrant().coordinate(),
                entry.packageGrant().registrationDigest(),
                entry.packageGrant().packageDigest(),
                manifest.scriptRelativePath(),
                manifest.scriptContentDigest(),
                binding.coordinate(),
                binding.providerBindingReference(),
                binding.catalogDigest(),
                SkillTrustDigests.argumentPolicy(binding.coordinate()),
                manifest.runtimeRef(),
                SkillTrustDigests.executionProfile(
                        manifest.runtimeRef(),
                        binding.definition().resources().executionProfiles().stream()
                                .sorted()
                                .toList()),
                manifest.sandboxDigest(),
                manifest.capabilities(),
                manifest.networkHosts(),
                manifest.issuedInstant(),
                manifest.expiresInstant(),
                manifest.revokedInstant(),
                manifest.state(),
                manifest.reviewerRef(),
                manifest.reviewSourceRef(),
                "TRUSTED_SKILL_SCRIPT_REVIEWED");
    }

    private static ToolDefinition definition(
            PersonalTrustedScriptManifest.ScriptExecution manifest,
            String executionConfigurationDigest,
            String sandboxDigest) {
        Capability capability = Capability.valueOf(manifest.capability().toUpperCase(Locale.ROOT));
        Set<String> hosts = Set.copyOf(manifest.networkHosts());
        Set<String> expectedCapabilities =
                switch (capability) {
                    case STOCKS_MARKET_DATA, DCF_VALIDATE -> Set.of("execution.run");
                    case EXCEL_RECALCULATE -> Set.of("execution.run", "workspace.write");
                };
        if (!Set.copyOf(manifest.capabilities()).equals(expectedCapabilities)) {
            throw new IllegalArgumentException("trusted script capability set exceeds the fixed business envelope");
        }
        if (capability != Capability.STOCKS_MARKET_DATA && !hosts.isEmpty()) {
            throw new IllegalArgumentException("local workbook capabilities cannot request network hosts");
        }
        if (capability == Capability.STOCKS_MARKET_DATA && hosts.isEmpty()) {
            throw new IllegalArgumentException("market data capability requires explicit network hosts");
        }
        Set<ToolSideEffect> effects =
                switch (capability) {
                    case STOCKS_MARKET_DATA -> Set.of(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.NETWORK_ACCESS);
                    case EXCEL_RECALCULATE ->
                        Set.of(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.FILE_READ, ToolSideEffect.FILE_WRITE);
                    case DCF_VALIDATE -> Set.of(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.FILE_READ);
                };
        return new ToolDefinition(
                new ToolName(manifest.capability().replace('_', '.')),
                new SemanticVersion("1.0.0"),
                TrustedSkillScriptToolProvider.PROVIDER_ID,
                title(capability),
                description(capability),
                inputSchema(capability),
                outputSchema(capability),
                ToolExecutionMode.HOST_PROCESS,
                true,
                Duration.ofSeconds(25),
                "per-workspace-trusted-script",
                capability == Capability.EXCEL_RECALCULATE
                        ? ToolIdempotency.NON_IDEMPOTENT
                        : ToolIdempotency.IDEMPOTENT,
                ToolRisk.HIGH,
                effects,
                new ToolResourceRequirements(
                        expectedCapabilities, hosts, Set.of(executionConfigurationDigest, "sandbox@" + sandboxDigest)),
                List.of(),
                ToolApprovalRequirement.ALWAYS,
                PRODUCT_ID,
                false,
                Set.of("personal", "trusted-skill-script"));
    }

    private static ToolSchema inputSchema(Capability capability) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required;
        switch (capability) {
            case STOCKS_MARKET_DATA -> {
                properties.put(
                        "action",
                        Map.of("type", "string", "enum", List.of("quote", "search", "history", "compare", "crypto")));
                properties.put("symbol", tokenSchema(32));
                properties.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 128));
                properties.put(
                        "symbols", Map.of("type", "array", "minItems", 2, "maxItems", 8, "items", tokenSchema(32)));
                properties.put("range", Map.of("type", "string", "enum", List.of("1mo", "3mo", "6mo", "1y", "5y")));
                properties.put("currency", tokenSchema(8));
                required = List.of("action");
            }
            case EXCEL_RECALCULATE -> {
                properties.put("workbook", workbookSchema());
                properties.put("timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 20));
                required = List.of("workbook");
            }
            case DCF_VALIDATE -> {
                properties.put("workbook", workbookSchema());
                required = List.of("workbook");
            }
            default -> throw new IllegalStateException("unsupported capability");
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("$schema", ToolSchema.DRAFT_2020_12);
        document.put("type", "object");
        document.put("additionalProperties", false);
        document.put("properties", Map.copyOf(properties));
        document.put("required", required);
        return new ToolSchema(
                "haifa.personal." + capability.name().toLowerCase(Locale.ROOT) + ".input",
                "1.0.0",
                Map.copyOf(document));
    }

    private static ToolSchema outputSchema(Capability capability) {
        return new ToolSchema(
                "haifa.personal." + capability.name().toLowerCase(Locale.ROOT) + ".output",
                "1.0.0",
                Map.of(
                        "$schema",
                        ToolSchema.DRAFT_2020_12,
                        "type",
                        "object",
                        "additionalProperties",
                        false,
                        "properties",
                        Map.ofEntries(
                                Map.entry("status", Map.of("type", "string")),
                                Map.entry("mode", Map.of("type", "string")),
                                Map.entry("language", Map.of("type", "string")),
                                Map.entry("exitCode", Map.of("type", "integer")),
                                Map.entry("timedOut", Map.of("type", "boolean")),
                                Map.entry("cancelled", Map.of("type", "boolean")),
                                Map.entry("stdoutSummary", Map.of("type", "string")),
                                Map.entry("stderrSummary", Map.of("type", "string")),
                                Map.entry("truncated", Map.of("type", "boolean")),
                                Map.entry("durationMillis", Map.of("type", "integer", "minimum", 0))),
                        "required",
                        List.of(
                                "status",
                                "mode",
                                "timedOut",
                                "cancelled",
                                "stdoutSummary",
                                "stderrSummary",
                                "truncated",
                                "durationMillis")));
    }

    private static TrustedScriptArguments mapArguments(String capability, Map<String, Object> arguments) {
        return switch (Capability.valueOf(capability.toUpperCase(Locale.ROOT))) {
            case STOCKS_MARKET_DATA -> stockArguments(arguments);
            case EXCEL_RECALCULATE -> {
                String workbook = safeWorkbook(arguments.get("workbook"));
                yield TrustedScriptArguments.atWorkspaceRootWithInputs(
                        List.of(workbook, Integer.toString(integer(arguments.get("timeoutSeconds"), 15, 1, 20))),
                        List.of(ProjectPath.of(workbook)));
            }
            case DCF_VALIDATE -> {
                String workbook = safeWorkbook(arguments.get("workbook"));
                yield TrustedScriptArguments.atWorkspaceRootWithInputs(
                        List.of(workbook), List.of(ProjectPath.of(workbook)));
            }
        };
    }

    private static TrustedScriptArguments stockArguments(Map<String, Object> values) {
        String action = text(values.get("action"), "action", 16).toLowerCase(Locale.ROOT);
        List<String> argv = new ArrayList<>();
        argv.add(action);
        switch (action) {
            case "quote" -> argv.add(symbol(values.get("symbol")));
            case "search" -> argv.add(text(values.get("query"), "query", 128));
            case "history" -> {
                argv.add(symbol(values.get("symbol")));
                argv.add("--range");
                argv.add(enumValue(
                        values.getOrDefault("range", "1mo"), "range", Set.of("1mo", "3mo", "6mo", "1y", "5y")));
            }
            case "compare" -> {
                Object raw = values.get("symbols");
                if (!(raw instanceof List<?> list) || list.size() < 2 || list.size() > 8) {
                    throw new IllegalArgumentException("symbols must contain two to eight entries");
                }
                list.forEach(item -> argv.add(symbol(item)));
            }
            case "crypto" -> {
                argv.add(symbol(values.get("symbol")));
                argv.add("--vs");
                argv.add(symbol(values.getOrDefault("currency", "USD")));
            }
            default -> throw new IllegalArgumentException("unsupported market data action");
        }
        return TrustedScriptArguments.atWorkspaceRoot(argv);
    }

    static String safeWorkbook(Object value) {
        String path = text(value, "workbook", 512).replace('\\', '/');
        if (path.startsWith("/")
                || path.startsWith("//")
                || path.contains(":")
                || path.equals("..")
                || path.startsWith("../")
                || path.endsWith("/..")
                || path.contains("/../")
                || !path.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("workbook must be a Workspace-relative .xlsx path");
        }
        return path;
    }

    private static String symbol(Object value) {
        String symbol = text(value, "symbol", 32).toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9][A-Z0-9.^=-]{0,31}")) {
            throw new IllegalArgumentException("symbol is invalid");
        }
        return symbol;
    }

    private static String enumValue(Object value, String field, Set<String> allowed) {
        String result = text(value, field, 32).toLowerCase(Locale.ROOT);
        if (!allowed.contains(result)) throw new IllegalArgumentException(field + " is invalid");
        return result;
    }

    private static int integer(Object value, int fallback, int minimum, int maximum) {
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("numeric argument is invalid");
        int result = number.intValue();
        if (result < minimum || result > maximum)
            throw new IllegalArgumentException("numeric argument is out of range");
        return result;
    }

    private static String text(Object value, String field, int maximum) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return text.trim();
    }

    private static Map<String, Object> tokenSchema(int maximum) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maximum);
    }

    private static Map<String, Object> workbookSchema() {
        return Map.of(
                "type",
                "string",
                "minLength",
                6,
                "maxLength",
                512,
                "pattern",
                "^(?![A-Za-z]:)(?![/\\\\])(?!.*(?:^|[/\\\\])\\.\\.(?:[/\\\\]|$)).+\\.[xX][lL][sS][xX]$");
    }

    private static String purpose(String capability) {
        return switch (Capability.valueOf(capability.toUpperCase(Locale.ROOT))) {
            case STOCKS_MARKET_DATA -> "Read bounded market data through a reviewed Skill script";
            case EXCEL_RECALCULATE -> "Recalculate one Workspace workbook through a reviewed Skill script";
            case DCF_VALIDATE -> "Validate one Workspace DCF workbook through a reviewed Skill script";
        };
    }

    private static String title(Capability capability) {
        return switch (capability) {
            case STOCKS_MARKET_DATA -> "Read market data";
            case EXCEL_RECALCULATE -> "Recalculate workbook";
            case DCF_VALIDATE -> "Validate DCF workbook";
        };
    }

    private static String description(Capability capability) {
        return switch (capability) {
            case STOCKS_MARKET_DATA ->
                "Run a fixed reviewed market-data script with a bounded action and symbol schema.";
            case EXCEL_RECALCULATE ->
                "Run a fixed reviewed recalculation script for one Workspace-relative xlsx workbook.";
            case DCF_VALIDATE -> "Run a fixed reviewed DCF validation script for one Workspace-relative xlsx workbook.";
        };
    }

    enum Capability {
        STOCKS_MARKET_DATA,
        EXCEL_RECALCULATE,
        DCF_VALIDATE
    }

    record Entry(
            ToolAlias alias,
            String providerBindingReference,
            PersonalTrustedScriptManifest.ScriptExecution manifest,
            SkillPackageReviewGrant packageGrant,
            TrustedSkillScriptToolSpec spec) {}

    record Prepared(Optional<TrustedSkillScriptToolProvider> provider, List<Entry> entries) {
        Prepared {
            provider = Objects.requireNonNull(provider);
            entries = List.copyOf(entries);
        }

        Set<String> aliases() {
            return entries.stream()
                    .map(entry -> entry.alias().value())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
