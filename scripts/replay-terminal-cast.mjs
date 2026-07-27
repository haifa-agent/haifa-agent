#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);

function parseArguments(values) {
  const parsed = new Map();
  for (let index = 0; index < values.length; index += 2) {
    if (!values[index]?.startsWith("--") || values[index + 1] == null) {
      throw new Error(
        "Usage: node scripts/replay-terminal-cast.mjs " +
          "--cast <terminal.cast> --labels <screens.jsonl> " +
          "--output <xterm-screens.jsonl> --xterm <module-directory>",
      );
    }
    parsed.set(values[index], values[index + 1]);
  }
  return parsed;
}

function absolute(value, label) {
  if (!value || !path.isAbsolute(value)) throw new Error(`${label} must be an absolute path`);
  return path.resolve(value);
}

function jsonLines(file) {
  return fs
    .readFileSync(file, "utf8")
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

function write(terminal, data) {
  return new Promise((resolve) => terminal.write(data, resolve));
}

function screen(terminal) {
  const buffer = terminal.buffer.active;
  return Array.from({ length: terminal.rows }, (_, row) => {
    const line = buffer.getLine(buffer.viewportY + row);
    return line ? line.translateToString(true) : "";
  }).join("\n");
}

const parsed = parseArguments(process.argv.slice(2));
const castFile = absolute(parsed.get("--cast"), "--cast");
const labelsFile = absolute(parsed.get("--labels"), "--labels");
const outputFile = absolute(parsed.get("--output"), "--output");
const xtermDirectory = absolute(parsed.get("--xterm"), "--xterm");
const { Terminal } = require(xtermDirectory);

const cast = jsonLines(castFile);
const header = cast.shift();
if (header.version !== 2) throw new Error("Only asciicast v2 is supported");
const labels = jsonLines(labelsFile);
const outputEvents = cast.filter((event) => event[1] === "o");
const terminal = new Terminal({
  cols: header.width,
  rows: header.height,
  scrollback: 10_000,
  allowProposedApi: true,
});

const snapshots = [];
let eventIndex = 0;
for (const label of labels) {
  while (
    eventIndex < outputEvents.length &&
    Number(outputEvents[eventIndex][0]) <= Number(label.elapsedSeconds)
  ) {
    await write(terminal, outputEvents[eventIndex][2]);
    eventIndex += 1;
  }
  snapshots.push({
    elapsedSeconds: label.elapsedSeconds,
    label: label.label,
    screen: screen(terminal),
  });
}
terminal.dispose();
fs.mkdirSync(path.dirname(outputFile), { recursive: true });
fs.writeFileSync(
  outputFile,
  snapshots.map((snapshot) => JSON.stringify(snapshot)).join("\n") + "\n",
  "utf8",
);
console.log(
  JSON.stringify(
    {
      schema: "haifa.terminal-cast-xterm-replay/1",
      cast: castFile,
      labels: labelsFile,
      output: outputFile,
      snapshots: snapshots.length,
      terminal: { columns: header.width, rows: header.height },
    },
    null,
    2,
  ),
);
