package io.haifa.agent.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiModelProfileFactoryTest {
    @Test
    void verifiesGovernedLocalDialectAndRequiresProtectedContinuation() {
        var profile = GeminiModelProfileFactory.fromSnapshot(
                snapshot("cliproxyapi-antigravity", GeminiDialects.CLIPROXYAPI_ANTIGRAVITY), LocalDate.of(2026, 8, 24));
        assertThat(profile.status()).isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(profile.toolReasoningContinuationRequired()).isTrue();
        assertThat(profile.selectable()).isTrue();
    }

    @Test
    void doesNotVerifyLookalikeProviderIdentity() {
        var profile = GeminiModelProfileFactory.fromSnapshot(
                snapshot("untrusted-gateway", GeminiDialects.CLIPROXYAPI_ANTIGRAVITY), LocalDate.of(2026, 8, 24));
        assertThat(profile.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(profile.selectable()).isFalse();
    }

    private static ResolvedModelSnapshot snapshot(String provider, String dialect) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(provider),
                "1",
                new ModelDefinitionId("gemini"),
                "1",
                "gemini-3-flash",
                ModelApiStyles.GOOGLE_GEMINI_ADAPTER,
                GeminiGenerateContentModel.ADAPTER_VERSION,
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                dialect,
                URI.create("http://127.0.0.1:8317/v1beta"),
                new CredentialRef(GeminiGenerateContentModel.CLIPROXY_CREDENTIAL_REF),
                true,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                131072,
                8192,
                Map.of(),
                Map.of());
    }
}
