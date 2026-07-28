package io.haifa.agent.sdk.spi;

import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.sdk.product.ProductContribution;
import java.util.function.Supplier;

/**
 * Host-side persistence SPI used by the SDK assembler.
 *
 * <p>This SPI deliberately lives outside the public facade package: product applications select it at bootstrap;
 * request payloads and product-facing services never expose Runtime Core storage types.
 */
public interface SdkPersistenceContribution extends ProductContribution {
    RuntimePersistencePorts runtimePersistence();

    default <T> T inTransaction(Supplier<T> work) {
        return runtimePersistence().unitOfWork().execute(work);
    }
}
