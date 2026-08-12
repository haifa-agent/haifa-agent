package io.haifa.example.sdk.advanced;

import io.haifa.example.sdk.support.DeterministicExampleSupport;

/** Prints only the safe frozen assembly projection. */
public final class AssemblyDiagnosticsExample {
    private AssemblyDiagnosticsExample() {}

    public static void main(String[] args) {
        try (var agent = DeterministicExampleSupport.inMemory()) {
            var assembly = agent.assembly();
            System.out.printf(
                    "product=%s agent=%s runProfile=%s assemblyDigest=%s%n",
                    assembly.profile().productId().value(),
                    agent.metadata().name(),
                    assembly.profile().runProfileId(),
                    assembly.assemblyDigest());
            agent.diagnostics()
                    .forEach(diagnostic -> System.out.printf(
                            "%s %s %s%n", diagnostic.severity(), diagnostic.code(), diagnostic.safeMessage()));
        }
    }
}
