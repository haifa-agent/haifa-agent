package io.haifa.agent.sdk.tool;

import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable metadata used to derive one Java Tool definition and frozen binding. */
public final class JavaToolSpec<I extends Record, O extends Record> {
    private final ToolName name;
    private final ToolAlias alias;
    private final SemanticVersion version;
    private final ToolProviderId providerId;
    private final Class<I> inputType;
    private final Class<O> outputType;
    private final String title;
    private final String description;
    private final Duration timeout;
    private final String concurrencyPolicy;
    private final ToolIdempotency idempotency;
    private final ToolRisk risk;
    private final Set<ToolSideEffect> sideEffects;
    private final ToolResourceRequirements resources;
    private final List<CredentialRequirement> credentialRequirements;
    private final ToolApprovalRequirement approvalRequirement;
    private final String provenance;
    private final Set<String> tags;

    private JavaToolSpec(Builder<I, O> builder) {
        name = new ToolName(builder.name);
        alias = new ToolAlias(builder.alias);
        version = new SemanticVersion(builder.version);
        providerId = new ToolProviderId(builder.providerId);
        inputType = requireRecord(builder.inputType, "inputType");
        outputType = requireRecord(builder.outputType, "outputType");
        title = text(builder.title, "title");
        description = text(builder.description, "description");
        timeout = Objects.requireNonNull(builder.timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        concurrencyPolicy = text(builder.concurrencyPolicy, "concurrencyPolicy");
        idempotency = Objects.requireNonNull(builder.idempotency, "idempotency must not be null");
        risk = Objects.requireNonNull(builder.risk, "risk must not be null");
        sideEffects = Set.copyOf(builder.sideEffects);
        resources = Objects.requireNonNull(builder.resources, "resources must not be null");
        credentialRequirements = List.copyOf(builder.credentialRequirements);
        approvalRequirement =
                Objects.requireNonNull(builder.approvalRequirement, "approvalRequirement must not be null");
        provenance = text(builder.provenance, "provenance");
        tags = Set.copyOf(builder.tags);
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

    public ToolProviderId providerId() {
        return providerId;
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

    public String concurrencyPolicy() {
        return concurrencyPolicy;
    }

    public ToolIdempotency idempotency() {
        return idempotency;
    }

    public ToolRisk risk() {
        return risk;
    }

    public Set<ToolSideEffect> sideEffects() {
        return sideEffects;
    }

    public ToolResourceRequirements resources() {
        return resources;
    }

    public List<CredentialRequirement> credentialRequirements() {
        return credentialRequirements;
    }

    public ToolApprovalRequirement approvalRequirement() {
        return approvalRequirement;
    }

    public String provenance() {
        return provenance;
    }

    public Set<String> tags() {
        return tags;
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
        private String alias;
        private String version = "1.0.0";
        private String providerId;
        private String title;
        private String description;
        private Duration timeout = Duration.ofSeconds(30);
        private String concurrencyPolicy = "per-run";
        private ToolIdempotency idempotency = ToolIdempotency.UNKNOWN;
        private ToolRisk risk = ToolRisk.MEDIUM;
        private final Set<ToolSideEffect> sideEffects = new LinkedHashSet<>();
        private ToolResourceRequirements resources = ToolResourceRequirements.none();
        private List<CredentialRequirement> credentialRequirements = List.of();
        private ToolApprovalRequirement approvalRequirement = ToolApprovalRequirement.POLICY;
        private String provenance = "java-sdk";
        private final Set<String> tags = new LinkedHashSet<>();

        private Builder(String name, Class<I> inputType, Class<O> outputType) {
            this.name = text(name, "name");
            this.inputType = Objects.requireNonNull(inputType, "inputType must not be null");
            this.outputType = Objects.requireNonNull(outputType, "outputType must not be null");
            this.alias = defaultAlias(this.name);
            this.providerId = "java." + this.name;
            this.title = this.name;
            this.description = this.name;
        }

        public Builder<I, O> alias(String value) {
            alias = value;
            return this;
        }

        public Builder<I, O> version(String value) {
            version = value;
            return this;
        }

        public Builder<I, O> providerId(String value) {
            providerId = value;
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

        public Builder<I, O> concurrencyPolicy(String value) {
            concurrencyPolicy = value;
            return this;
        }

        public Builder<I, O> idempotency(ToolIdempotency value) {
            idempotency = value;
            return this;
        }

        public Builder<I, O> risk(ToolRisk value) {
            risk = value;
            return this;
        }

        public Builder<I, O> sideEffects(ToolSideEffect... values) {
            sideEffects.clear();
            sideEffects.addAll(Arrays.asList(values));
            return this;
        }

        public Builder<I, O> resources(ToolResourceRequirements value) {
            resources = value;
            return this;
        }

        public Builder<I, O> credentialRequirements(List<CredentialRequirement> values) {
            credentialRequirements = List.copyOf(values);
            return this;
        }

        public Builder<I, O> approvalRequirement(ToolApprovalRequirement value) {
            approvalRequirement = value;
            return this;
        }

        public Builder<I, O> provenance(String value) {
            provenance = value;
            return this;
        }

        public Builder<I, O> tags(String... values) {
            tags.clear();
            tags.addAll(Arrays.asList(values));
            return this;
        }

        /** Applies low-risk, side-effect-free defaults for a deterministic pure function. */
        public Builder<I, O> pure() {
            idempotency = ToolIdempotency.PURE;
            risk = ToolRisk.LOW;
            sideEffects.clear();
            resources = ToolResourceRequirements.none();
            credentialRequirements = List.of();
            approvalRequirement = ToolApprovalRequirement.NEVER;
            return this;
        }

        public JavaToolSpec<I, O> build() {
            return new JavaToolSpec<>(this);
        }

        private static String defaultAlias(String name) {
            String value = name.replace('.', '_').replace('-', '_');
            if (value.length() > 64) {
                throw new IllegalArgumentException("derived alias exceeds 64 characters; configure alias explicitly");
            }
            return value;
        }
    }
}
