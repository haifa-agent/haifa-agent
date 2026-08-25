package io.haifa.agent.personalassistant.server.image;

import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.model.api.ImageDataPart;
import io.haifa.agent.sdk.api.ModelImageResolver;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Bounded local image store. Persisted conversations contain opaque references, never paths or bytes. */
public final class PersonalImageStore implements ModelImageResolver {
    public static final String STORE_ID = "personal-local";
    public static final int MAXIMUM_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final long MAXIMUM_STORE_BYTES = 1024L * 1024 * 1024;
    private static final List<String> EXTENSIONS = List.of("png", "jpg", "webp", "gif");

    private final Path root;

    public PersonalImageStore(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory must not be null");
        try {
            root = Files.createDirectories(
                            dataDirectory.resolve("images").toAbsolutePath().normalize())
                    .toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Personal Assistant image directory is unavailable", exception);
        }
    }

    public StoredImageContentPart save(byte[] value, String declaredMediaType, String originalFilename) {
        byte[] bytes = Objects.requireNonNull(value, "value must not be null").clone();
        if (bytes.length < 1 || bytes.length > MAXIMUM_IMAGE_BYTES) {
            throw new IllegalArgumentException("image must contain 1 byte to 10 MiB");
        }
        String detected = detect(bytes);
        String declared = Objects.requireNonNull(declaredMediaType, "declaredMediaType must not be null")
                .split(";", 2)[0]
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!detected.equals(declared)) throw new IllegalArgumentException("declared image media type is incorrect");
        ensureCapacity(bytes.length);
        String id = UUID.randomUUID().toString();
        Path target = resolve(id, extension(detected));
        Path temporary = resolve(id, "tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw new IllegalStateException("image could not be stored", exception);
        }
        return reference(id, detected, bytes, safeFilename(originalFilename, id + "." + extension(detected)));
    }

    public StoredImageContentPart reference(String imageId) {
        String id = validId(imageId);
        for (String extension : EXTENSIONS) {
            Path path = resolve(id, extension);
            if (!Files.isRegularFile(path)) continue;
            try {
                byte[] bytes = Files.readAllBytes(path);
                String mediaType = detect(bytes);
                if (!extension(mediaType).equals(extension))
                    throw new IllegalStateException("stored image type mismatch");
                return reference(id, mediaType, bytes, id + "." + extension);
            } catch (IOException exception) {
                throw new IllegalStateException("stored image is unavailable", exception);
            }
        }
        throw new IllegalArgumentException("uploaded image is unavailable");
    }

    /** Reads an opaque local image reference and revalidates its media type before returning bytes. */
    public ImageDataPart read(String imageId) {
        return resolve(reference(imageId));
    }

    @Override
    public ImageDataPart resolve(StoredImageContentPart image) {
        Objects.requireNonNull(image, "image must not be null");
        if (!STORE_ID.equals(image.storeId())) throw new IllegalArgumentException("unsupported image store");
        try {
            byte[] bytes = Files.readAllBytes(resolve(image.imageId(), extension(image.mediaType())));
            String mediaType = detect(bytes);
            StoredImageContentPart current = reference(image.imageId(), mediaType, bytes, image.originalFilename());
            if (!current.mediaType().equals(image.mediaType())
                    || current.sizeBytes() != image.sizeBytes()
                    || !current.sha256().equals(image.sha256())) {
                throw new IllegalStateException("stored image no longer matches its persisted reference");
            }
            return new ImageDataPart(mediaType, bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("stored image is unavailable", exception);
        }
    }

    private StoredImageContentPart reference(String id, String mediaType, byte[] bytes, String filename) {
        return new StoredImageContentPart(STORE_ID, id, mediaType, bytes.length, digest(bytes), filename);
    }

    private void ensureCapacity(int additionalBytes) {
        try (var files = Files.list(root)) {
            long used = files.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("image store quota cannot be calculated", exception);
                        }
                    })
                    .sum();
            if (used + additionalBytes > MAXIMUM_STORE_BYTES) {
                throw new IllegalStateException("Personal Assistant image store quota is exhausted");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("image store quota cannot be calculated", exception);
        }
    }

    private Path resolve(String id, String extension) {
        Path value = root.resolve(validId(id) + "." + extension).normalize();
        if (!value.getParent().equals(root)) throw new IllegalArgumentException("image id is invalid");
        return value;
    }

    private static String validId(String value) {
        String id =
                Objects.requireNonNull(value, "imageId must not be null").trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("imageId is invalid");
        }
        return id;
    }

    private static String safeFilename(String value, String fallback) {
        if (value == null) return fallback;
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidEncoding) {
            decoded = value;
        }
        String normalized = decoded.replace('\\', '/');
        normalized = normalized
                .substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (normalized.isBlank()) return fallback;
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private static String detect(byte[] bytes) {
        if (starts(bytes, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) return "image/png";
        if (starts(bytes, new int[] {0xff, 0xd8, 0xff})) return "image/jpeg";
        if (bytes.length >= 12
                && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))
            return "image/webp";
        if (bytes.length >= 6) {
            String header = new String(bytes, 0, 6, java.nio.charset.StandardCharsets.US_ASCII);
            if (header.equals("GIF87a") || header.equals("GIF89a")) {
                int frames = gifFrames(bytes);
                if (frames < 1) throw new IllegalArgumentException("GIF image contains no frame");
                if (frames > 1) throw new IllegalArgumentException("animated GIF images are not supported");
                return "image/gif";
            }
        }
        throw new IllegalArgumentException("file content is not a supported image");
    }

    private static int gifFrames(byte[] bytes) {
        if (bytes.length < 13) return 0;
        int packed = bytes[10] & 0xff;
        int index = 13 + ((packed & 0x80) == 0 ? 0 : 3 * (1 << ((packed & 0x07) + 1)));
        int frames = 0;
        while (index < bytes.length) {
            int marker = bytes[index++] & 0xff;
            if (marker == 0x3b) return frames;
            if (marker == 0x21) {
                if (index >= bytes.length) return 0;
                index++;
                index = skipGifSubBlocks(bytes, index);
            } else if (marker == 0x2c) {
                frames++;
                if (index + 9 > bytes.length) return 0;
                int imagePacked = bytes[index + 8] & 0xff;
                index += 9;
                if ((imagePacked & 0x80) != 0) index += 3 * (1 << ((imagePacked & 0x07) + 1));
                if (index >= bytes.length) return 0;
                index++;
                index = skipGifSubBlocks(bytes, index);
            } else {
                return 0;
            }
            if (index < 0) return 0;
        }
        return 0;
    }

    private static int skipGifSubBlocks(byte[] bytes, int index) {
        while (index < bytes.length) {
            int length = bytes[index++] & 0xff;
            if (length == 0) return index;
            if (index + length > bytes.length) return -1;
            index += length;
        }
        return -1;
    }

    private static boolean starts(byte[] bytes, int[] signature) {
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if ((bytes[index] & 0xff) != signature[index]) return false;
        }
        return true;
    }

    private static String extension(String mediaType) {
        return switch (mediaType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> throw new IllegalArgumentException("unsupported image media type");
        };
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
