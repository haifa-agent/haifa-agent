package io.haifa.agent.model.api;

import io.haifa.agent.core.content.ImageUrlContentPart;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** External HTTPS image reference. The adapter passes the URL to the selected model provider without fetching it. */
public record ImageUrlPart(URI url) implements ModelImagePart {
    public static final int MAXIMUM_URL_CHARACTERS = 2_048;

    public ImageUrlPart {
        url = new ImageUrlContentPart(Objects.requireNonNull(url, "url must not be null")).url();
    }

    @Override
    public String toString() {
        return "ImageUrlPart[host=" + url.getHost().toLowerCase(Locale.ROOT) + "]";
    }
}
