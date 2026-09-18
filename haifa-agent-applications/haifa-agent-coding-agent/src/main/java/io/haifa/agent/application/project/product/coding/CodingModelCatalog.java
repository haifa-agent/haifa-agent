package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.List;
import java.util.Optional;

/** Product boundary over the trusted host-configured model allowlist. */
public interface CodingModelCatalog {
    String defaultModelId();

    List<CodingModelOption> available(TenantRef tenant, PrincipalRef principal);

    Optional<CodingModelOption> find(TenantRef tenant, PrincipalRef principal, String modelId);

    static CodingModelCatalog fixed(String modelId, String displayName) {
        CodingModelOption option =
                new CodingModelOption(modelId, displayName, "configured", "Configured", java.util.Set.of(), 1);
        return new CodingModelCatalog() {
            @Override
            public String defaultModelId() {
                return option.id();
            }

            @Override
            public List<CodingModelOption> available(TenantRef tenant, PrincipalRef principal) {
                return List.of(option);
            }

            @Override
            public Optional<CodingModelOption> find(TenantRef tenant, PrincipalRef principal, String requested) {
                return option.id().equals(requested) ? Optional.of(option) : Optional.empty();
            }
        };
    }
}
