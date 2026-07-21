package io.haifa.agent.core.content;

/** Extensible message content represented by stable Core-native value types. */
public sealed interface ContentPart permits TextPart, AssetRefPart, ArtifactRefPart {

    String contentType();
}
