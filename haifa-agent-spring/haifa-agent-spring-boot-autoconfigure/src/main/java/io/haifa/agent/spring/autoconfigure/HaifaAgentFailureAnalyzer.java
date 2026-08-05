package io.haifa.agent.spring.autoconfigure;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Produces a credential-safe startup diagnostic for failed Agent assembly. */
public final class HaifaAgentFailureAnalyzer extends AbstractFailureAnalyzer<HaifaAgentAutoConfigurationException> {
    /** Creates the safe startup failure analyzer. */
    public HaifaAgentFailureAnalyzer() {}

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, HaifaAgentAutoConfigurationException cause) {
        String variable = cause.credentialEnvironmentVariable();
        return new FailureAnalysis(
                "The default Haifa Agent could not be assembled. Credential values were not logged.",
                "Set the " + variable
                        + " environment variable, correct haifa.agent model settings, provide your own HaifaAgent bean,"
                        + " or set haifa.agent.enabled=false.",
                cause);
    }
}
