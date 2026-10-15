package io.haifa.agent.sdk.tool;

import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.IDN;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable metadata used to derive one Java Tool definition and frozen binding.
 *
 * <p>This is the ordinary SDK entry for typed in-process Java Tools: it declares the Tool name,
 * input/output record types, a human title and description, a timeout, and whether the Tool is a pure
 * function or declares side effects. Everything else a {@link io.haifa.agent.tool.api.ToolDefinition}
 * can carry (provider identity, concurrency policy, resource requirements other than network hosts, credential requirements,
 * approval requirement, provenance, tags) is fixed by the SDK Tool platform and is not mirrored here;
 * a Tool that needs those fields registers itself through the Tool API instead.
 *
 * <p>Declaring the Tool as {@link Builder#pure()} is the only way to lower its risk, idempotency and
 * approval requirement. Any other declaration keeps the conservative defaults: medium risk, unknown
 * idempotency and policy-decided approval. Declaring side effects also drops the pure declaration, so
 * a side-effecting Tool can never keep the "never needs approval" state.
 *
 * <p>{@link Builder#networkAccess(String...)} declares logical service hosts for a read-oriented Tool.
 * This metadata does not intercept a Tool's own HTTP client. It always declares
 * {@link ToolSideEffect#NETWORK_ACCESS}, is never pure, and keeps the
 * same conservative defaults. Hosts are exact, lowercase DNS names; empty, blank, wildcard and
 * otherwise invalid hosts fail closed, and {@code pure()} removes the declaration so no contradictory
 * network metadata survives.
 */
public final class JavaToolSpec<I extends Record, O extends Record> {
    private final ToolName name;
    private final ToolAlias alias;
    private final SemanticVersion version;
    private final Class<I> inputType;
    private final Class<O> outputType;
    private final String title;
    private final String description;
    private final Duration timeout;
    private final boolean pure;
    private final Set<ToolSideEffect> sideEffects;
    private final Set<String> networkHosts;

    private JavaToolSpec(Builder<I, O> builder) {
        name = new ToolName(builder.name);
        alias = new ToolAlias(name.value());
        version = new SemanticVersion(builder.version);
        inputType = requireRecord(builder.inputType, "inputType");
        outputType = requireRecord(builder.outputType, "outputType");
        title = text(builder.title, "title");
        description = text(builder.description, "description");
        timeout = Objects.requireNonNull(builder.timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        pure = builder.pure;
        sideEffects = Set.copyOf(builder.sideEffects);
        networkHosts = Set.copyOf(builder.networkHosts);
        if (sideEffects.contains(ToolSideEffect.NETWORK_ACCESS) && networkHosts.isEmpty()) {
            throw new IllegalArgumentException("NETWORK_ACCESS requires constrained hosts");
        }
    }

    public static <I extends Record, O extends Record> Builder<I, O> builder(
            String name, Class<I> inputType, Class<O> outputType) {
        return new Builder<>(name, inputType, outputType);
    }

    public ToolName name() {
        return name;
    }

    public ToolAlias alias() {
        return alias;
    }

    public SemanticVersion version() {
        return version;
    }

    public Class<I> inputType() {
        return inputType;
    }

    public Class<O> outputType() {
        return outputType;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public Duration timeout() {
        return timeout;
    }

    public boolean pure() {
        return pure;
    }

    public Set<ToolSideEffect> sideEffects() {
        return sideEffects;
    }

    /**
     * Exact hosts a {@link Builder#networkAccess(String...)} Tool may reach, or an empty set when the
     * Tool declares no network access.
     */
    public Set<String> networkHosts() {
        return networkHosts;
    }

    /** A pure Tool is {@code PURE}; every other Tool keeps the conservative {@code UNKNOWN}. */
    public ToolIdempotency idempotency() {
        return pure ? ToolIdempotency.PURE : ToolIdempotency.UNKNOWN;
    }

    /** A pure Tool is {@code LOW} risk; every other Tool stays {@code MEDIUM}. */
    public ToolRisk risk() {
        return pure ? ToolRisk.LOW : ToolRisk.MEDIUM;
    }

    /** A pure Tool never needs approval; every other Tool is decided by Policy. */
    public ToolApprovalRequirement approvalRequirement() {
        return pure ? ToolApprovalRequirement.NEVER : ToolApprovalRequirement.POLICY;
    }

    private static <T extends Record> Class<T> requireRecord(Class<T> type, String field) {
        Objects.requireNonNull(type, field + " must not be null");
        if (!type.isRecord()) {
            throw new IllegalArgumentException(field + " must be a Java record");
        }
        return type;
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    public static final class Builder<I extends Record, O extends Record> {
        private final String name;
        private final Class<I> inputType;
        private final Class<O> outputType;
        private String version = "1.0.0";
        private String title;
        private String description;
        private Duration timeout = Duration.ofSeconds(30);
        private boolean pure;
        private final Set<ToolSideEffect> sideEffects = new LinkedHashSet<>();
        private Set<String> networkHosts = Set.of();

        private Builder(String name, Class<I> inputType, Class<O> outputType) {
            this.name = text(name, "name");
            this.inputType = Objects.requireNonNull(inputType, "inputType must not be null");
            this.outputType = Objects.requireNonNull(outputType, "outputType must not be null");
            this.title = this.name;
            this.description = this.name;
        }

        public Builder<I, O> version(String value) {
            version = value;
            return this;
        }

        public Builder<I, O> title(String value) {
            title = value;
            return this;
        }

        public Builder<I, O> description(String value) {
            description = value;
            return this;
        }

        public Builder<I, O> timeout(Duration value) {
            timeout = value;
            return this;
        }

        /** Declares a deterministic, side-effect-free function; it also clears any network declaration. */
        public Builder<I, O> pure() {
            pure = true;
            sideEffects.clear();
            networkHosts = Set.of();
            return this;
        }

        /**
         * Declares a read-oriented Tool that reaches exactly the named hosts. It always declares
         * {@link ToolSideEffect#NETWORK_ACCESS}, is never pure, and keeps the conservative risk,
         * idempotency and approval defaults. Empty, blank, wildcard and invalid hosts fail closed.
         *
         * <p>Declaring this alongside a writing side effect is allowed but the Tool is not a read-only
         * network Tool, so it is not eligible for read-only auto approval.
         */
        public Builder<I, O> networkAccess(String... hosts) {
            Set<String> validated = validateNetworkHosts(hosts);
            networkHosts = validated;
            sideEffects.remove(ToolSideEffect.NETWORK_ACCESS);
            sideEffects.add(ToolSideEffect.NETWORK_ACCESS);
            pure = false;
            return this;
        }

        /**
         * Declares the Tool's side effects. Any declared side effect also drops the pure declaration,
         * so the Tool keeps policy-decided approval. {@link ToolSideEffect#NETWORK_ACCESS} is only
         * accepted when constrained hosts were declared with {@link #networkAccess(String...)}.
         */
        public Builder<I, O> sideEffects(ToolSideEffect... values) {
            sideEffects.clear();
            sideEffects.addAll(Arrays.asList(values));
            if (sideEffects.contains(ToolSideEffect.NETWORK_ACCESS) && networkHosts.isEmpty()) {
                throw new IllegalArgumentException(
                        "Java Tools cannot declare NETWORK_ACCESS without constrained hosts; use networkAccess(hosts)");
            }
            if (!sideEffects.contains(ToolSideEffect.NETWORK_ACCESS)) networkHosts = Set.of();
            if (!sideEffects.isEmpty()) pure = false;
            return this;
        }

        public JavaToolSpec<I, O> build() {
            return new JavaToolSpec<>(this);
        }

        private static Set<String> validateNetworkHosts(String... hosts) {
            if (hosts == null || hosts.length == 0) {
                throw new IllegalArgumentException("networkAccess requires at least one host");
            }
            Set<String> validated = new LinkedHashSet<>();
            for (String host : hosts) {
                if (host == null) {
                    throw new IllegalArgumentException("networkAccess host must not be null");
                }
                String candidate = host.trim();
                if (candidate.isEmpty()) {
                    throw new IllegalArgumentException("networkAccess host must not be blank");
                }
                if (candidate.indexOf('*') >= 0) {
                    throw new IllegalArgumentException("networkAccess host must be an exact host, not a wildcard");
                }
                if (candidate.contains("://")
                        || candidate.contains("/")
                        || candidate.contains(":")
                        || candidate.contains("@")
                        || candidate.chars().anyMatch(Character::isWhitespace)
                        || candidate.startsWith(".")
                        || candidate.endsWith(".")) {
                    throw new IllegalArgumentException("networkAccess host is invalid");
                }
                String ascii;
                try {
                    ascii = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("networkAccess host is invalid", exception);
                }
                if (ascii.isEmpty() || ascii.length() > 253) {
                    throw new IllegalArgumentException("networkAccess host is invalid");
                }
                validated.add(ascii);
            }
            return Set.copyOf(validated);
        }
    }
}
