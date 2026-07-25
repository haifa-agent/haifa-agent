package io.haifa.agent.store.sqlite.mybatis;

import java.util.Objects;

public record MapperXml(String resourceName, String xml) {
    public MapperXml {
        resourceName = requireText(resourceName, "resourceName");
        xml = requireText(xml, "xml");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
