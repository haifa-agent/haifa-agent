package io.haifa.coding.host.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Set;

/** Explicit optional v1 extensions; legacy capability bits retain their original meaning. */
public final class CodingHostCapabilities {
    private CodingHostCapabilities() {}

    public static Set<String> negotiate(JsonNode params, Set<String> available) {
        JsonNode offered = params.get("optionalCapabilities");
        if (offered == null) return Set.of();
        if (!offered.isArray() || offered.size() > 32) {
            throw CodingHostException.invalidParams("optionalCapabilities must be a bounded array");
        }
        Set<String> negotiated = new LinkedHashSet<>();
        for (JsonNode item : offered) {
            if (!item.isTextual() || !item.textValue().matches("[a-z][a-z0-9-]{0,63}")) {
                throw CodingHostException.invalidParams("Invalid optional capability name");
            }
            if (available.contains(item.textValue())) negotiated.add(item.textValue());
        }
        return Set.copyOf(negotiated);
    }
}
