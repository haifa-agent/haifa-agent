package io.haifa.example.sdk.advanced;

import io.haifa.example.sdk.support.DeterministicExampleSupport;

/** Prints only the safe frozen assembly projection. */
public final class AssemblyDiagnosticsExample {
    private AssemblyDiagnosticsExample() {}

    public static void main(String[] args) {
        try (var agent = DeterministicExampleSupport.inMemory()) {
            var assembly = agent.assembly();
            System.out.printf(
                    "product=%s runProfile=%s assemblyDigest=%s%n",
                    assembly.profile().productId().value(),
                    assembly.profile().runProfileId(),
                    assembly.assemblyDigest());
        }
    }
}
