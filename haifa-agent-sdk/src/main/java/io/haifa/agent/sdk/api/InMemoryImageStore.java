package io.haifa.agent.sdk.api;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.model.api.ImageDataPart;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded in-memory image store for SDK users, examples, and test environments. */
public final class InMemoryImageStore implements ModelImageResolver {
    public static final String DEFAULT_STORE_ID = "in-memory";
    public static final int MAXIMUM_IMAGE_BYTES = 10 * 1024 * 1024;

    private final String storeId;
    private final IdentifierGenerator ids;
    private final Map<String, ImageDataPart> entries = new ConcurrentHashMap<>();

    public InMemoryImageStore() {
        this(DEFAULT_STORE_ID, new UuidV7IdentifierGenerator());
    }

    public InMemoryImageStore(String storeId) {
        this(storeId, new UuidV7IdentifierGenerator());
    }

    public InMemoryImageStore(IdentifierGenerator ids) {
        this(DEFAULT_STORE_ID, ids);
    }

    public InMemoryImageStore(String storeId, IdentifierGenerator ids) {
        this.storeId = Objects.requireNonNull(storeId, "storeId must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    /**
     * Stores raw image bytes in memory and produces a valid domain {@link StoredImageContentPart}.
     *
     * @param bytes raw image bytes (1 byte to 10 MiB)
     * @param mediaType standard media type (e.g. "image/png", "image/jpeg", "image/webp")
     * @param originalFilename original file name for reference
     * @return a domain StoredImageContentPart referencing this stored image
     */
    public StoredImageContentPart store(byte[] bytes, String mediaType, String originalFilename) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        Objects.requireNonNull(originalFilename, "originalFilename must not be null");
        if (bytes.length < 1 || bytes.length > MAXIMUM_IMAGE_BYTES) {
            throw new IllegalArgumentException("image must contain 1 byte to 10 MiB");
        }
        String id = ids.nextValue();
        String sha256 = "sha256:" + digest(bytes);
        var part = new StoredImageContentPart(storeId, id, mediaType, bytes.length, sha256, originalFilename);
        entries.put(id, new ImageDataPart(part.mediaType(), bytes.clone()));
        return part;
    }

    @Override
    public ImageDataPart resolve(StoredImageContentPart image) {
        Objects.requireNonNull(image, "image must not be null");
        if (!storeId.equals(image.storeId())) {
            throw new IllegalArgumentException("unsupported storeId: " + image.storeId() + ", expected: " + storeId);
        }
        ImageDataPart part = entries.get(image.imageId());
        if (part == null) {
            throw new IllegalStateException("image is unavailable: " + image.imageId());
        }
        return part;
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
