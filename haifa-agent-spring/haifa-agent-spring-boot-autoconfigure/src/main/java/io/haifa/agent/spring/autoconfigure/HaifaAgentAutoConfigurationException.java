package io.haifa.agent.spring.autoconfigure;

/** Safe startup exception raised when the default Agent cannot be assembled. */
public final class HaifaAgentAutoConfigurationException extends RuntimeException {
    /** Name of the credential environment variable; never its value. */
    private final String credentialEnvironmentVariable;

    HaifaAgentAutoConfigurationException(String credentialEnvironmentVariable, RuntimeException cause) {
        super("Haifa Agent auto-configuration failed", cause);
        this.credentialEnvironmentVariable = credentialEnvironmentVariable;
    }

    String credentialEnvironmentVariable() {
        return credentialEnvironmentVariable;
    }
}
