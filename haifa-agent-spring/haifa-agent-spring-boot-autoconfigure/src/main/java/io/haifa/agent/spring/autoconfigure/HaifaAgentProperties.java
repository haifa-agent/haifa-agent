package io.haifa.agent.spring.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** External configuration for the safe-default Haifa Agent Spring Boot integration. */
@ConfigurationProperties("haifa.agent")
public final class HaifaAgentProperties {
    /** Whether the safe-default Agent should be auto-configured. */
    private boolean enabled = true;

    /** Trusted system instructions frozen into each Run. */
    private String instructions;

    /** Trusted display name; it does not enter Prompt or routing. */
    private String name;

    /** Safe model adapter settings; model identity and Thinking mode are intentionally fixed. */
    private final Model model = new Model();

    /** Creates properties with safe Starter defaults. */
    public HaifaAgentProperties() {}

    /**
     * Returns whether default Agent auto-configuration is enabled.
     *
     * @return whether auto-configuration is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether default Agent auto-configuration is enabled.
     *
     * @param enabled whether auto-configuration is enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns trusted system instructions, or {@code null} to use the Starter default.
     *
     * @return trusted instructions or {@code null}
     */
    public String getInstructions() {
        return instructions;
    }

    /**
     * Sets trusted system instructions frozen into each Run.
     *
     * @param instructions trusted system instructions
     */
    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns safe model adapter settings.
     *
     * @return model adapter settings
     */
    public Model getModel() {
        return model;
    }

    /** Configuration that is safe to expose as ordinary application properties. */
    public static final class Model {
        /** Name of the environment variable containing the DeepSeek API key. */
        private String credentialEnvironmentVariable = "DEEPSEEK_API_KEY";

        /** Bounded HTTP connection timeout for the model adapter. */
        private Duration connectTimeout = Duration.ofSeconds(10);

        /** Creates model settings with safe Starter defaults. */
        public Model() {}

        /**
         * Returns the credential environment-variable name.
         *
         * @return credential environment-variable name
         */
        public String getCredentialEnvironmentVariable() {
            return credentialEnvironmentVariable;
        }

        /**
         * Sets the credential environment-variable name, never the credential value.
         *
         * @param credentialEnvironmentVariable environment-variable name
         */
        public void setCredentialEnvironmentVariable(String credentialEnvironmentVariable) {
            this.credentialEnvironmentVariable = credentialEnvironmentVariable;
        }

        /**
         * Returns the model adapter connection timeout.
         *
         * @return connection timeout
         */
        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        /**
         * Sets the model adapter connection timeout.
         *
         * @param connectTimeout positive connection timeout
         */
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }
    }
}
