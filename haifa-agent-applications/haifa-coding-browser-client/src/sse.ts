import { CodingClientError } from "./errors.js";

/** Incremental SSE framing; delivery boundaries never define UTF-8 characters, lines or events. */
export async function* readSse(
  stream: ReadableStream<Uint8Array>,
  event: string,
  maximumDataBytes: number,
  signal: AbortSignal,
): AsyncGenerator<string> {
  const reader = stream.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  const encoder = new TextEncoder();
  let line = "",
    eventName = "",
    data: string[] = [],
    frameBytes = 0,
    lineBytes = 0,
    skipLf = false;
  const cancel = () => {
    void reader.cancel().catch(() => {});
  };
  signal.addEventListener("abort", cancel, { once: true });
  function consumeLine(): string | null {
    const value = line;
    line = "";
    lineBytes = 0;
    if (value === "") {
      const result = data.length ? data.join("\n") : null;
      const source = eventName;
      data = [];
      eventName = "";
      frameBytes = 0;
      if (result !== null) {
        if (source !== event)
          throw new CodingClientError("SSE_CHANNEL_MISMATCH");
        if (encoder.encode(result).length > maximumDataBytes)
          throw new CodingClientError("SSE_FRAME_TOO_LARGE");
      }
      return result;
    }
    if (value.startsWith(":")) return null;
    const colon = value.indexOf(":");
    const field = colon < 0 ? value : value.slice(0, colon);
    let content = colon < 0 ? "" : value.slice(colon + 1);
    if (content.startsWith(" ")) content = content.slice(1);
    if (field === "data") data.push(content);
    else if (field === "event") eventName = content;
    return null;
  }
  try {
    for (;;) {
      if (signal.aborted) return;
      const chunk = await reader.read();
      if (signal.aborted) return;
      let text: string;
      try {
        text = chunk.done
          ? decoder.decode()
          : decoder.decode(chunk.value, { stream: true });
      } catch {
        throw new CodingClientError("SSE_INVALID_UTF8");
      }
      for (const character of text) {
        if (character === "\n" && skipLf) {
          skipLf = false;
          continue;
        }
        skipLf = false;
        frameBytes += encoder.encode(character).length;
        if (frameBytes > maximumDataBytes + 2048)
          throw new CodingClientError("SSE_FRAME_TOO_LARGE");
        if (character === "\r" || character === "\n") {
          const value = consumeLine();
          skipLf = character === "\r";
          if (value !== null) yield value;
        } else {
          line += character;
          lineBytes += encoder.encode(character).length;
          if (lineBytes > maximumDataBytes + 512)
            throw new CodingClientError("SSE_LINE_TOO_LARGE");
        }
      }
      // An unterminated final event is discarded per SSE framing, never partially applied.
      if (chunk.done) return;
    }
  } catch (error) {
    if (signal.aborted) return;
    if (error instanceof CodingClientError) throw error;
    throw new CodingClientError("SSE_STREAM_UNAVAILABLE");
  } finally {
    signal.removeEventListener("abort", cancel);
    cancel();
    reader.releaseLock();
  }
}
