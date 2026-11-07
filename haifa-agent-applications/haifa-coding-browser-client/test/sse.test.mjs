import test from "node:test";
import assert from "node:assert/strict";
import { readSse } from "../dist/index.js";
const encoded = (value) => new TextEncoder().encode(value);
function stream(chunks) {
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk);
      controller.close();
    },
  });
}
async function collect(chunks, maximum = 65536) {
  const frames = [];
  for await (const value of readSse(
    stream(chunks),
    "presentation",
    maximum,
    new AbortController().signal,
  ))
    frames.push(value);
  return frames;
}
test("UTF-8 and CRLF framing survive every byte split, multiline data, comments and EOF", async () => {
  const text =
    ': heartbeat\r\n\r\nevent: presentation\r\ndata: {"text":\r\ndata: "中文 😀"}\r\n\r\nevent: presentation\rdata: second\r\nevent: presentation\r\n\r\nevent: presentation\ndata: incomplete';
  const bytes = encoded(text);
  const expected = ['{"text":\n"中文 😀"}', "second"];
  assert.deepEqual(
    await collect([...bytes].map((b) => Uint8Array.of(b))),
    expected,
  );
  for (let split = 0; split <= bytes.length; split++)
    assert.deepEqual(
      await collect([bytes.slice(0, split), bytes.slice(split)]),
      expected,
    );
});
test("invalid UTF-8, unknown channels and byte overflow fail closed", async () => {
  await assert.rejects(collect([Uint8Array.of(255)]), {
    code: "SSE_INVALID_UTF8",
  });
  await assert.rejects(
    collect([encoded("event: tool-preview\ndata: {}\n\n")]),
    { code: "SSE_CHANNEL_MISMATCH" },
  );
  await assert.rejects(
    collect(
      [encoded("event: presentation\ndata: " + "汉".repeat(3000) + "\n\n")],
      4096,
    ),
    (e) => ["SSE_LINE_TOO_LARGE", "SSE_FRAME_TOO_LARGE"].includes(e.code),
  );
});
test("abort cancels an owned pending reader and never dispatches a partial event", async () => {
  let cancelled = 0;
  const body = new ReadableStream({
    start(c) {
      c.enqueue(encoded("event: presentation\ndata: partial"));
    },
    cancel() {
      cancelled++;
    },
  });
  const controller = new AbortController(),
    iterator = readSse(body, "presentation", 1024, controller.signal);
  const pending = iterator.next();
  controller.abort();
  assert.equal((await pending).done, true);
  assert.equal(cancelled, 1);
});
