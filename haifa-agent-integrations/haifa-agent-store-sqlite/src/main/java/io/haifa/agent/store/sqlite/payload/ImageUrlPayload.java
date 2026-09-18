package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.content.ImageUrlContentPart;
import java.net.URI;

public record ImageUrlPayload(String url) {
    static ImageUrlPayload from(ImageUrlContentPart value) {
        return new ImageUrlPayload(value.url().toASCIIString());
    }

    ImageUrlContentPart toDomain() {
        return new ImageUrlContentPart(URI.create(url));
    }
}
