package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.content.ArtifactRefPart;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.ImageUrlContentPart;
import io.haifa.agent.core.content.StoredAudioContentPart;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallId;

/** Closed, versioned projection of every current Core ContentPart subtype. */
public record ContentPartPayload(
        String kind,
        String text,
        String format,
        AssetPayload asset,
        ArtifactPayload artifact,
        String summary,
        String toolCallId,
        String providerCorrelationId,
        String toolName,
        String toolVersion,
        ImageUrlPayload imageUrl,
        StoredImagePayload storedImage,
        StoredAudioPayload storedAudio) {

    public static ContentPartPayload from(ContentPart part) {
        return switch (part) {
            case TextPart value ->
                new ContentPartPayload(
                        "text",
                        value.text(),
                        value.format(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
            case AssetRefPart value ->
                new ContentPartPayload(
                        "asset-ref",
                        null,
                        null,
                        AssetPayload.from(value.asset()),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
            case ArtifactRefPart value ->
                new ContentPartPayload(
                        "artifact-ref",
                        null,
                        null,
                        null,
                        ArtifactPayload.from(value.artifact()),
                        value.summary(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
            case ImageUrlContentPart value ->
                new ContentPartPayload(
                        "image-url",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        ImageUrlPayload.from(value),
                        null,
                        null);
            case StoredImageContentPart value ->
                new ContentPartPayload(
                        "stored-image",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        StoredImagePayload.from(value),
                        null);
            case StoredAudioContentPart value ->
                new ContentPartPayload(
                        "stored-audio",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        StoredAudioPayload.from(value));
            case ToolCallPart value ->
                new ContentPartPayload(
                        "tool-call-ref",
                        null,
                        null,
                        null,
                        null,
                        null,
                        value.toolCallId().value(),
                        value.providerCorrelationId().value(),
                        value.toolName(),
                        value.toolVersion(),
                        null,
                        null,
                        null);
            case ToolResultPart value ->
                new ContentPartPayload(
                        "tool-result-ref",
                        null,
                        null,
                        null,
                        null,
                        value.summary(),
                        value.toolCallId().value(),
                        value.providerCorrelationId().value(),
                        null,
                        null,
                        null,
                        null,
                        null);
        };
    }

    public ContentPart toDomain() {
        return switch (kind) {
            case "text" -> new TextPart(text, format);
            case "asset-ref" -> new AssetRefPart(asset.toDomain());
            case "artifact-ref" -> new ArtifactRefPart(artifact.toDomain(), summary);
            case "image-url" -> imageUrl.toDomain();
            case "stored-image" -> storedImage.toDomain();
            case "stored-audio" -> storedAudio.toDomain();
            case "tool-call-ref" ->
                new ToolCallPart(
                        new ToolCallId(toolCallId),
                        new ProviderToolCallCorrelationId(providerCorrelationId),
                        toolName,
                        toolVersion);
            case "tool-result-ref" ->
                new ToolResultPart(
                        new ToolCallId(toolCallId), new ProviderToolCallCorrelationId(providerCorrelationId), summary);
            default -> throw new IllegalArgumentException("unknown content part kind: " + kind);
        };
    }
}
