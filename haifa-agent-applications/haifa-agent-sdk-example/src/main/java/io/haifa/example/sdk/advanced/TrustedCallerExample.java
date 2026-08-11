package io.haifa.example.sdk.advanced;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.starter.HaifaAgentStarter;
import java.util.Set;

/** Supplies identity only from an authenticated host boundary. */
public final class TrustedCallerExample {
    private TrustedCallerExample() {}

    public static void main(String[] args) {
        SdkCallerProvider callers = () -> new SdkCaller(
                new TenantRef("authenticated-tenant"),
                new PrincipalRef("authenticated-user", "user"),
                Set.of("memory:read"));
        try (var agent = HaifaAgentStarter.builder()
                .model(ExampleAgentFactory.model("trusted-answer"), ExampleAgentFactory.snapshot())
                .callerProvider(callers)
                .build()) {
            System.out.println(agent.assembly().profile().productId().value());
        }
    }
}
