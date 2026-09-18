package io.haifa.agent.application.project.product;

@FunctionalInterface
public interface TrustedProductCallerProvider {
    TrustedProductCaller current();
}
