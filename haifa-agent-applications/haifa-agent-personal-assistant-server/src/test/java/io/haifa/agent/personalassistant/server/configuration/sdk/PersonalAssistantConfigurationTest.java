package io.haifa.agent.personalassistant.server.configuration.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.personalassistant.application.policy.PersonalAssistantPolicyRules;
import io.haifa.agent.personalassistant.server.configuration.model.PersonalModelProxySettings;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalAssistantConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void installsAStorelessPolicyContributionForPersonalAssistant() {
        var policy = new PolicyPlatformContribution(
                new SdkContributionMetadata(
                        new ProductContributionCoordinate("pa-policy", "1.0.0"),
                        ProductCapabilities.POLICY,
                        "sha256:" + "0".repeat(64),
                        ProductProviderSuitability.PRODUCTION,
                        "Personal Assistant policy"),
                PersonalAssistantPolicyRules.conservative(),
                new DefaultPolicyDecisionService());

        assertThat(policy.rules().approvalMode()).isEqualTo(ApprovalMode.ASK);
        assertThat(policy.evaluator()).isInstanceOf(DefaultPolicyDecisionService.class);
        assertThat(PersonalAssistantPolicyRules.conservative().contentDigest())
                .isEqualTo(policy.rules().contentDigest());
        assertThat(List.of(policy.getClass().getMethods()))
                .extracting(method -> method.getName())
                .doesNotContain("snapshots", "decisions", "authorizationEvidence", "approvalGrants", "projectTrusts");
    }

    @Test
    void routesAntigravityAuthenticationTrafficThroughTheProviderProxy() {
        var provider = new PersonalAssistantProperties.ModelProvider(
                "google-antigravity",
                "Google Antigravity Direct",
                "remote",
                false,
                true,
                URI.create("https://daily-cloudcode-pa.googleapis.com/v1internal"),
                "model-auth://google-antigravity/default",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "google-gemini-generate-content", "antigravity-direct", null)),
                List.of(new PersonalAssistantProperties.ProviderModel(
                        "antigravity-gemini",
                        "Gemini via Antigravity Direct",
                        "Gemini Flash",
                        "gemini-3.7-flash",
                        "google-gemini-generate-content",
                        Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                        ModelReasoningMode.DISABLED,
                        131_072,
                        8_192)),
                URI.create("http://127.0.0.1:2081"));

        var settings = new PersonalModelProxySettings(List.of(provider), directory, new ObjectMapper());
        var client = PersonalAssistantConfiguration.authenticationHttpClient(settings, "google-antigravity");
        Proxy selected = client.proxy()
                .orElseThrow()
                .select(URI.create("https://oauth2.googleapis.com/token"))
                .getFirst();

        assertThat(selected.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat((InetSocketAddress) selected.address())
                .extracting(InetSocketAddress::getHostString, InetSocketAddress::getPort)
                .containsExactly("127.0.0.1", 2081);
    }
}
