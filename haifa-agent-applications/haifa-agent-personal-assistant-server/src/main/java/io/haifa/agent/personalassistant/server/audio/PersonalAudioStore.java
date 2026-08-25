package io.haifa.agent.personalassistant.server.audio;

import io.haifa.agent.core.content.StoredAudioContentPart;
import io.haifa.agent.model.api.AudioDataPart;
import io.haifa.agent.sdk.api.ModelAudioResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/** Bounded local audio store. Persisted conversations contain opaque references, never paths or bytes. */
public final class PersonalAudioStore implements ModelAudioResolver {
    public static final String STORE_ID = "personal-local-audio";
    public static final int MAXIMUM_AUDIO_BYTES = 10 * 1024 * 1024;
    private static final long MAXIMUM_STORE_BYTES = 1024L * 1024 * 1024;
    private static final List<String> EXTENSIONS = List.of("wav", "mp3", "aiff", "aac", "ogg", "flac");

    private final Path root;

    public PersonalAudioStore(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory must not be null");
        try {
            root = Files.createDirectories(
                            dataDirectory.resolve("audio").toAbsolutePath().normalize())
                    .toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Personal Assistant audio directory is unavailable", exception);
        }
    }

    public StoredAudioContentPart save(byte[] value, String declaredMediaType, String originalFilename) {
        byte[] bytes = Objects.requireNonNull(value, "value must not be null").clone();
        if (bytes.length < 1 || bytes.length > MAXIMUM_AUDIO_BYTES) {
            throw new IllegalArgumentException("audio must contain 1 byte to 10 MiB");
        }
        String declared = normalizeMediaType(declaredMediaType);
        validate(bytes, declared);
        ensureCapacity(bytes.length);
        String id = UUID.randomUUID().toString();
        String extension = extension(declared);
        Path target = resolve(id, extension);
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
            throw new IllegalStateException("audio could not be stored", exception);
        }
        return reference(id, declared, bytes, safeFilename(originalFilename, id + "." + extension));
    }

    public StoredAudioContentPart reference(String audioId) {
        String id = validId(audioId);
        for (String extension : EXTENSIONS) {
            Path path = resolve(id, extension);
            if (!Files.isRegularFile(path)) continue;
            try {
                byte[] bytes = Files.readAllBytes(path);
                String mediaType = mediaType(extension);
                validate(bytes, mediaType);
                return reference(id, mediaType, bytes, id + "." + extension);
            } catch (IOException exception) {
                throw new IllegalStateException("stored audio is unavailable", exception);
            }
        }
        throw new IllegalArgumentException("uploaded audio is unavailable");
    }

    @Override
    public AudioDataPart resolve(StoredAudioContentPart audio) {
        Objects.requireNonNull(audio, "audio must not be null");
        if (!STORE_ID.equals(audio.storeId())) throw new IllegalArgumentException("unsupported audio store");
        StoredAudioContentPart current = reference(audio.audioId());
        if (!current.mediaType().equals(audio.mediaType())
                || current.sizeBytes() != audio.sizeBytes()
                || !current.sha256().equals(audio.sha256())) {
            throw new IllegalStateException("stored audio no longer matches its persisted reference");
        }
        try {
            return new AudioDataPart(
                    audio.mediaType(), Files.readAllBytes(resolve(audio.audioId(), extension(audio.mediaType()))));
        } catch (IOException exception) {
            throw new IllegalStateException("stored audio is unavailable", exception);
        }
    }

    private StoredAudioContentPart reference(String id, String mediaType, byte[] bytes, String filename) {
        return new StoredAudioContentPart(STORE_ID, id, mediaType, bytes.length, digest(bytes), filename);
    }

    private void ensureCapacity(int additionalBytes) {
        try (var files = Files.list(root)) {
            long used = files.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("audio store quota cannot be calculated", exception);
                        }
                    })
                    .sum();
            if (used + additionalBytes > MAXIMUM_STORE_BYTES) {
                throw new IllegalStateException("Personal Assistant audio store quota is exhausted");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("audio store quota cannot be calculated", exception);
        }
    }

    private Path resolve(String id, String extension) {
        Path value = root.resolve(validId(id) + "." + extension).normalize();
        if (!value.getParent().equals(root)) throw new IllegalArgumentException("audio id is invalid");
        return value;
    }

    private static String validId(String value) {
        String id =
                Objects.requireNonNull(value, "audioId must not be null").trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("audioId is invalid");
        }
        return id;
    }

    private static String normalizeMediaType(String value) {
        String normalized = Objects.requireNonNull(value, "declaredMediaType must not be null")
                .split(";", 2)[0]
                .trim()
                .toLowerCase(Locale.ROOT);
        return "audio/mpeg".equals(normalized) ? "audio/mp3" : normalized;
    }

    private static void validate(byte[] bytes, String mediaType) {
        boolean valid =
                switch (mediaType) {
                    case "audio/wav" -> ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WAVE");
                    case "audio/mp3" ->
                        ascii(bytes, 0, "ID3")
                                || (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xe6) == 0xe2);
                    case "audio/aiff" ->
                        ascii(bytes, 0, "FORM") && (ascii(bytes, 8, "AIFF") || ascii(bytes, 8, "AIFC"));
                    case "audio/aac" -> bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && ((bytes[1] & 0xf6) == 0xf0);
                    case "audio/ogg" -> ascii(bytes, 0, "OggS") && contains(bytes, "vorbis", 256);
                    case "audio/flac" -> ascii(bytes, 0, "fLaC");
                    default -> false;
                };
        if (!valid) throw new IllegalArgumentException("file content does not match the declared audio media type");
    }

    private static boolean ascii(byte[] bytes, int offset, String expected) {
        byte[] value = expected.getBytes(StandardCharsets.US_ASCII);
        if (offset < 0 || bytes.length < offset + value.length) return false;
        for (int index = 0; index < value.length; index++) {
            if (bytes[offset + index] != value[index]) return false;
        }
        return true;
    }

    private static boolean contains(byte[] bytes, String expected, int limit) {
        String header = new String(bytes, 0, Math.min(bytes.length, limit), StandardCharsets.ISO_8859_1);
        return header.contains(expected);
    }

    private static String extension(String mediaType) {
        return switch (mediaType) {
            case "audio/wav" -> "wav";
            case "audio/mp3" -> "mp3";
            case "audio/aiff" -> "aiff";
            case "audio/aac" -> "aac";
            case "audio/ogg" -> "ogg";
            case "audio/flac" -> "flac";
            default -> throw new IllegalArgumentException("unsupported audio media type");
        };
    }

    private static String mediaType(String extension) {
        return switch (extension) {
            case "wav" -> "audio/wav";
            case "mp3" -> "audio/mp3";
            case "aiff" -> "audio/aiff";
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            default -> throw new IllegalArgumentException("unsupported audio extension");
        };
    }

    private static String safeFilename(String value, String fallback) {
        if (value == null) return fallback;
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
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
