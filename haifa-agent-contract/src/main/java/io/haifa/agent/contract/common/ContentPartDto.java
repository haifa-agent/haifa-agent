package io.haifa.agent.contract.common;

/** Bounded external content union; it intentionally excludes tool protocol and provider parts. */
public sealed interface ContentPartDto permits TextContentPartDto, ReferenceContentPartDto {
    String contentType();
}
