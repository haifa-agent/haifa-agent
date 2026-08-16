package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.haifa.agent.testing.evidence.Sha256Digests;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentProfileManifestLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesStandardConfigurationAndDerivesCredentialEnvironmentNames() throws Exception {
        Path configuration = writeConfiguration();
        writeManifest(Sha256Digests.file(configuration));

        ResolvedAgentProfile profile = new AgentProfileManifestLoader().load(temporaryDirectory, "coding-primary");

        assertEquals("coding-primary", profile.profileId());
        assertEquals(configuration.toAbsolutePath().normalize(), profile.configurationPath());
        assertEquals(List.of("MODEL_API_KEY", "MODEL_ENDPOINT", "WEB_API_KEY"), profile.requiredEnvironmentNames());
        assertEquals(List.of("MODEL_API_KEY", "WEB_API_KEY"), profile.credentialEnvironmentNames());
        assertFalse(profile.agentAssemblyDigest().isBlank());
    }

    @Test
    void rejectsConfigurationChangedAfterProfileReview() throws Exception {
        Path configuration = writeConfiguration();
        writeManifest(Sha256Digests.file(configuration));
        Files.writeString(configuration, "provider: changed\n");

        assertThrows(IllegalArgumentException.class, () -> new AgentProfileManifestLoader()
                .load(temporaryDirectory, "coding-primary"));
    }

    private Path writeConfiguration() throws Exception {
        Path configuration = temporaryDirectory.resolve("environments/coding-primary.yaml");
        Files.createDirectories(configuration.getParent());
        Files.writeString(
                configuration,
                """
                provider:
                  endpoint: ${MODEL_ENDPOINT}
                  region: ${MODEL_REGION:default-region}
                  credentialRef: env://MODEL_API_KEY
                web:
                  credentialRef: env://WEB_API_KEY
                duplicate:
                  credentialRef: env://MODEL_API_KEY
                """);
        return configuration;
    }

    private void writeManifest(String configurationSha256) throws Exception {
        Path manifest = temporaryDirectory.resolve("agent-profiles/coding-primary.yaml");
        Files.createDirectories(manifest.getParent());
        Files.writeString(
                manifest,
                """
                schemaVersion: 1
                profileId: coding-primary
                compatibleAgentBaselineCommit: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                configurationRef: environments/coding-primary.yaml
                configurationSha256: %s
                """
                        .formatted(configurationSha256));
    }
}
