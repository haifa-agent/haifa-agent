package io.haifa.example.sdk.advanced;

import io.haifa.example.sdk.support.DeterministicExampleSupport;

/** Prints only the safe frozen product projection. */
public final class AssemblyDiagnosticsExample {
    private AssemblyDiagnosticsExample() {}

    public static void main(String[] args) {
        try (var agent = DeterministicExampleSupport.inMemory()) {
            var profile = agent.profile();
            System.out.printf(
                    "product=%s agent=%s runProfile=%s%n",
                    profile.productId().value(), agent.metadata().name(), profile.runProfileId());
            agent.diagnostics()
                    .forEach(diagnostic -> System.out.printf(
                            "%s %s %s%n", diagnostic.severity(), diagnostic.code(), diagnostic.safeMessage()));
        }
    }
}
