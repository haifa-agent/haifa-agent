package io.haifa.agent.application.project.tool.web;

import io.haifa.agent.credential.api.CredentialRequirement;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record WebProviderDescriptor(
        WebProviderId id,
        String displayName,
        WebProviderCapabilities capabilities,
        String adapterKind,
        String adapterVersion,
        URI endpoint,
        Set<String> networkHosts,
        Optional<CredentialRequirement> credentialRequirement,
        Map<String, String> configuration) {
    public WebProviderDescriptor {
        Objects.requireNonNull(id, "id");
        displayName = WebValues.text(displayName, "displayName", 128);
        Objects.requireNonNull(capabilities, "capabilities");
        adapterKind = WebValues.text(adapterKind, "adapterKind", 128);
        adapterVersion = WebValues.text(adapterVersion, "adapterVersion", 64);
        endpoint = Objects.requireNonNull(endpoint, "endpoint").normalize();
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || !(endpoint.getScheme().equalsIgnoreCase("https")
                        || endpoint.getScheme().equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI");
        }
        networkHosts = WebValues.strings(networkHosts, "networkHosts");
        if (networkHosts.stream().anyMatch(host -> host.equals("*") || host.equalsIgnoreCase("internet"))) {
            throw new IllegalArgumentException("networkHosts must contain exact hosts");
        }
        String endpointHost = endpoint.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!networkHosts.contains(endpointHost)) {
            throw new IllegalArgumentException("networkHosts must contain the endpoint host");
        }
        credentialRequirement = Objects.requireNonNull(credentialRequirement, "credentialRequirement");
        configuration = WebValues.stringMap(configuration, "configuration");
    }
}
