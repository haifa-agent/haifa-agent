package io.haifa.agent.cli;

import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.application.project.policy.CodingApprovalThreshold;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Test-only fluent builder for {@link CliConfiguration}. It replaces the retired telescoping convenience
 * constructors: tests start from {@link #defaults()} (or {@link #from(CliConfiguration)}) and override only the
 * fields they exercise. Validation stays in the record's canonical constructor.
 */
final class CliConfigurationBuilder {
    private CliConfigurationBuilder() {}

    static Builder defaults() {
        return new Builder(CliConfiguration.defaults());
    }

    static Builder from(CliConfiguration base) {
        return new Builder(base);
    }

    static final class Builder {
        private CliConfiguration.Model model;
        private List<CliConfiguration.Model> availableModels;
        private Set<String> enabledTools;
        private List<CliConfiguration.McpServer> mcpServers;
        private CliConfiguration.Web web;
        private CliConfiguration.Skills skills;
        private CliConfiguration.Execution execution;
        private ApprovalMode approval;
        private CodingApprovalThreshold approvalThreshold;
        private Duration timeout;
        private int maxIterations;
        private long maxModelCalls;
        private long maxToolCalls;
        private ProjectPersistenceConfiguration persistence;
        private int modelMaxResponseBytes;
        private Duration abandonedRunStaleness;

        private Builder(CliConfiguration base) {
            this.model = base.model();
            this.availableModels = base.availableModels();
            this.enabledTools = base.enabledTools();
            this.mcpServers = base.mcpServers();
            this.web = base.web();
            this.skills = base.skills();
            this.execution = base.execution();
            this.approval = base.approval();
            this.approvalThreshold = base.approvalThreshold();
            this.timeout = base.timeout();
            this.maxIterations = base.maxIterations();
            this.maxModelCalls = base.maxModelCalls();
            this.maxToolCalls = base.maxToolCalls();
            this.persistence = base.persistence();
            this.modelMaxResponseBytes = base.modelMaxResponseBytes();
            this.abandonedRunStaleness = base.abandonedRunStaleness();
        }

        Builder model(CliConfiguration.Model value) {
            this.model = value;
            return this;
        }

        Builder availableModels(List<CliConfiguration.Model> value) {
            this.availableModels = value;
            return this;
        }

        Builder enabledTools(Set<String> value) {
            this.enabledTools = value;
            return this;
        }

        Builder mcpServers(List<CliConfiguration.McpServer> value) {
            this.mcpServers = value;
            return this;
        }

        Builder web(CliConfiguration.Web value) {
            this.web = value;
            return this;
        }

        Builder skills(CliConfiguration.Skills value) {
            this.skills = value;
            return this;
        }

        Builder execution(CliConfiguration.Execution value) {
            this.execution = value;
            return this;
        }

        /** Sets the approval mode and derives the matching threshold, as the retired convenience constructors did. */
        Builder approval(ApprovalMode value) {
            this.approval = value;
            this.approvalThreshold = CodingApprovalThreshold.compatibleWith(
                    io.haifa.agent.policy.api.ApprovalMode.valueOf(value.name()));
            return this;
        }

        Builder approvalThreshold(CodingApprovalThreshold value) {
            this.approvalThreshold = value;
            return this;
        }

        Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        Builder maxIterations(int value) {
            this.maxIterations = value;
            return this;
        }

        Builder maxModelCalls(long value) {
            this.maxModelCalls = value;
            return this;
        }

        Builder maxToolCalls(long value) {
            this.maxToolCalls = value;
            return this;
        }

        Builder persistence(ProjectPersistenceConfiguration value) {
            this.persistence = value;
            return this;
        }

        Builder modelMaxResponseBytes(int value) {
            this.modelMaxResponseBytes = value;
            return this;
        }

        Builder abandonedRunStaleness(Duration value) {
            this.abandonedRunStaleness = value;
            return this;
        }

        CliConfiguration build() {
            return new CliConfiguration(
                    model,
                    availableModels,
                    enabledTools,
                    mcpServers,
                    web,
                    skills,
                    execution,
                    approval,
                    approvalThreshold,
                    timeout,
                    maxIterations,
                    maxModelCalls,
                    maxToolCalls,
                    persistence,
                    modelMaxResponseBytes,
                    abandonedRunStaleness);
        }
    }
}
