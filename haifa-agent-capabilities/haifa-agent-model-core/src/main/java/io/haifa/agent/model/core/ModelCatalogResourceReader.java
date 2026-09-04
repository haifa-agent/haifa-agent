package io.haifa.agent.model.core;

import java.io.InputStream;

/** Reads one explicitly named, packaged model catalog resource. */
@FunctionalInterface
public interface ModelCatalogResourceReader {
    InputStream open(String resourceName);
}
