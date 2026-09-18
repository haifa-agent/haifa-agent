package io.haifa.agent.testing.e2e;

import io.haifa.agent.application.project.product.coding.client.CodingAgentClientFactory;
import io.haifa.agent.cli.StandaloneCodingAgents;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/** Highest-level product assembly adapter; product-semantic tests receive only the standard factory contract. */
public final class StandardCodingAgentClientExtension implements ParameterResolver {
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType().equals(CodingAgentClientFactory.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return StandaloneCodingAgents.factory();
    }
}
