package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.content.ContentPart;
import java.util.List;

public record ContentPartsPayload(List<ContentPartPayload> parts) {
    public static ContentPartsPayload from(List<ContentPart> parts) {
        return new ContentPartsPayload(
                parts.stream().map(ContentPartPayload::from).toList());
    }

    public List<ContentPart> toDomain() {
        return parts.stream().map(ContentPartPayload::toDomain).toList();
    }
}
