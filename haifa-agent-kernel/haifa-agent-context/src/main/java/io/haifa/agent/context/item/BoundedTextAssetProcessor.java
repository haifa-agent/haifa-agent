package io.haifa.agent.context.item;

import io.haifa.agent.core.reference.AssetRef;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Converts caller-authorized bytes to bounded derived text without loading or retaining an Asset
 * payload.
 */
public final class BoundedTextAssetProcessor {
    private final TextAssetDerivationPolicy policy;

    public BoundedTextAssetProcessor(TextAssetDerivationPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public AssetDerivedTextContent derive(AssetRef asset, byte[] content, DerivedTextKind kind) {
        Objects.requireNonNull(asset, "asset must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        byte[] immutable = Arrays.copyOf(Objects.requireNonNull(content, "content must not be null"), content.length);
        if (immutable.length > policy.maxInputBytes()) {
            throw failure("ASSET_TEXT_INPUT_TOO_LARGE");
        }
        MediaType mediaType = parse(asset.mimeType());
        if (!policy.allows(mediaType.baseType())) {
            throw failure("ASSET_TEXT_MEDIA_TYPE_NOT_ALLOWED");
        }
        if (mediaType.charset() != null && !mediaType.charset().equals("utf-8")) {
            throw failure("ASSET_TEXT_CHARSET_NOT_SUPPORTED");
        }
        String text = decode(immutable);
        if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
            text = text.substring(1);
        }
        if (text.indexOf('\0') >= 0) {
            throw failure("ASSET_TEXT_NUL_NOT_ALLOWED");
        }
        if (text.length() > policy.maxOutputCharacters()) {
            throw failure("ASSET_TEXT_OUTPUT_TOO_LARGE");
        }
        try {
            return new AssetDerivedTextContent(asset, kind, text);
        } catch (IllegalArgumentException exception) {
            throw failure("ASSET_TEXT_EMPTY");
        }
    }

    private static String decode(byte[] content) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw failure("ASSET_TEXT_INVALID_UTF8");
        }
    }

    private static MediaType parse(String value) {
        String[] parts = Objects.requireNonNull(value, "media type must not be null")
                .toLowerCase(Locale.ROOT)
                .split(";", -1);
        String baseType = parts[0].trim();
        if (baseType.isEmpty()) {
            throw failure("ASSET_TEXT_INVALID_MEDIA_TYPE");
        }
        String charset = null;
        for (int index = 1; index < parts.length; index++) {
            String parameter = parts[index].trim();
            int separator = parameter.indexOf('=');
            if (separator < 1 || !parameter.substring(0, separator).trim().equals("charset") || charset != null) {
                throw failure("ASSET_TEXT_INVALID_MEDIA_TYPE");
            }
            charset = unquote(parameter.substring(separator + 1).trim());
            if (charset.isEmpty()) {
                throw failure("ASSET_TEXT_INVALID_MEDIA_TYPE");
            }
        }
        return new MediaType(baseType, charset);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static IllegalArgumentException failure(String code) {
        return new IllegalArgumentException(code);
    }

    private record MediaType(String baseType, String charset) {}
}
