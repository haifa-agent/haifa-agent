#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";

const require = createRequire(import.meta.url);
const DRIVER_PROTOCOL_VERSION = "1.2.0";
const RUN_TERMINAL_STATES = ["IDLE", "COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"];
const RECORDING_COLUMNS = 132;
const RECORDING_ROWS = 42;
const MAX_RECORDED_OUTPUT_BYTES = 1024 * 1024;
const MAX_TERMINAL_TRANSITION_WAIT_MILLIS = 120_000;

function fail(message, terminal) {
  if (terminal) terminal.kill();
  process.stderr.write(`${message}\n`);
  process.exit(20);
}

function loadPty() {
  const candidates = [
    process.env.HAIFA_NODE_PTY_MODULE,
    "@lydell/node-pty",
    "node-pty",
  ].filter(Boolean);
  const failures = [];
  for (const candidate of candidates) {
    try {
      const loaded = require(candidate);
      const implementation = loaded.default ?? loaded;
      if (typeof implementation.spawn === "function") return implementation;
      failures.push(`${candidate}: missing spawn`);
    } catch (error) {
      failures.push(`${candidate}: ${error.code ?? error.message}`);
    }
  }
  fail(`A compatible Node PTY module is required.\n${failures.join("\n")}`);
}

export function findStatusMarker(screenText, markers) {
  const visibleStatus = screenText
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .at(-1);
  if (visibleStatus == null) return null;
  return markers.find(
    (candidate) =>
      visibleStatus === candidate || visibleStatus.startsWith(`${candidate} ·`),
  ) ?? null;
}

export class VirtualScreen {
  constructor(columns, rows) {
    this.columns = columns;
    this.rows = rows;
    this.grid = this.blank();
    this.row = 0;
    this.column = 0;
    this.saved = [0, 0];
  }

  blank() {
    return Array.from({ length: this.rows }, () => Array(this.columns).fill(" "));
  }

  clear() {
    this.grid = this.blank();
    this.row = 0;
    this.column = 0;
  }

  feed(text) {
    for (let index = 0; index < text.length; ) {
      const character = text[index];
      if (character === "\u001b") {
        if (text[index + 1] === "[") {
          const match = /^\u001b\[([0-?]*)([ -/]*)([@-~])/.exec(text.slice(index));
          if (match) {
            this.csi(match[1], match[3]);
            index += match[0].length;
            continue;
          }
        }
        if (text[index + 1] === "]") {
          const endBell = text.indexOf("\u0007", index + 2);
          const endSt = text.indexOf("\u001b\\", index + 2);
          const end = endBell >= 0 && (endSt < 0 || endBell < endSt) ? endBell + 1 : endSt + 2;
          if (end > 1) {
            index = end;
            continue;
          }
        }
        index += 2;
        continue;
      }
      if (character === "\r") {
        this.column = 0;
      } else if (character === "\n") {
        this.lineFeed();
      } else if (character === "\b") {
        this.column = Math.max(0, this.column - 1);
      } else if (character === "\t") {
        this.column = Math.min(this.columns - 1, (Math.floor(this.column / 8) + 1) * 8);
      } else if (character >= " " && character !== "\u007f" && character !== "\uFFFF") {
        this.grid[this.row][this.column] = character;
        this.column += 1;
        if (this.column >= this.columns) {
          this.column = 0;
          this.lineFeed();
        }
      }
      index += 1;
    }
  }

  csi(rawParameters, finalCharacter) {
    const privateMode = rawParameters.startsWith("?");
    const values = rawParameters
      .replace(/^\?/, "")
      .split(";")
      .filter(Boolean)
      .map(Number);
    const value = (position, fallback = 1) => values[position] || fallback;
    if ((finalCharacter === "h" || finalCharacter === "l") && privateMode) {
      if (values.includes(1049) && finalCharacter === "h") this.clear();
      return;
    }
    switch (finalCharacter) {
      case "H":
      case "f":
        this.row = Math.min(this.rows - 1, value(0) - 1);
        this.column = Math.min(this.columns - 1, value(1) - 1);
        break;
      case "A":
        this.row = Math.max(0, this.row - value(0));
        break;
      case "B":
        this.row = Math.min(this.rows - 1, this.row + value(0));
        break;
      case "C":
        this.column = Math.min(this.columns - 1, this.column + value(0));
        break;
      case "D":
        this.column = Math.max(0, this.column - value(0));
        break;
      case "G":
        this.column = Math.min(this.columns - 1, value(0) - 1);
        break;
      case "d":
        this.row = Math.min(this.rows - 1, value(0) - 1);
        break;
      case "J":
        if (values.length === 0 || values[0] === 0 || values[0] === 2 || values[0] === 3) this.clear();
        break;
      case "K":
        this.grid[this.row].fill(" ", values[0] === 1 ? 0 : this.column);
        break;
      case "s":
        this.saved = [this.row, this.column];
        break;
      case "u":
        [this.row, this.column] = this.saved;
        break;
      default:
        break;
    }
  }

  lineFeed() {
    this.row += 1;
    if (this.row >= this.rows) {
      this.grid.shift();
      this.grid.push(Array(this.columns).fill(" "));
      this.row = this.rows - 1;
    }
  }

  text() {
    return this.grid.map((line) => line.join("").replace(/\s+$/, "")).join("\n");
  }
}

function waitForStatusMarker(state, markers, label, timeoutMillis) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMillis;
    const poll = () => {
      const match = findStatusMarker(state.screen.text(), markers);
      if (match) {
        resolve(match);
      } else if (state.exited) {
        reject(new Error(`UNEXPECTED EOF waiting for ${label}`));
      } else if (Date.now() >= deadline) {
        reject(new Error(`TIMEOUT waiting for ${label}`));
      } else {
        setTimeout(poll, 50);
      }
    };
    poll();
  });
}

async function typeAndSend(terminal, text) {
  for (const character of text) {
    terminal.write(character);
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
  terminal.write("\r");
}

function truncateUtf8(value, maximumBytes) {
  if (Buffer.byteLength(value, "utf8") <= maximumBytes) return value;
  let result = "";
  let size = 0;
  for (const character of value) {
    const characterBytes = Buffer.byteLength(character, "utf8");
    if (size + characterBytes > maximumBytes) break;
    result += character;
    size += characterBytes;
  }
  return result;
}

function createRecorder() {
  const startedAt = Date.now();
  const events = [];
  let outputBytes = 0;
  let truncated = false;
  return {
    record(chunk) {
      const remaining = MAX_RECORDED_OUTPUT_BYTES - outputBytes;
      if (remaining <= 0) {
        truncated = true;
        return;
      }
      const captured = truncateUtf8(chunk, remaining);
      if (captured !== chunk) truncated = true;
      if (captured.length > 0) {
        events.push([
          Number(((Date.now() - startedAt) / 1000).toFixed(6)),
          "o",
          captured,
        ]);
        outputBytes += Buffer.byteLength(captured, "utf8");
      }
    },
    finalize(recordingFile) {
      const lines = [
        JSON.stringify({
          version: 2,
          width: RECORDING_COLUMNS,
          height: RECORDING_ROWS,
          timestamp: Math.floor(startedAt / 1000),
          env: { TERM: "xterm-256color" },
        }),
        ...events.map((event) => JSON.stringify(event)),
      ];
      const content = `${lines.join("\n")}\n`;
      fs.writeFileSync(recordingFile, content, "utf8");
      return {
        format: "asciicast-v2",
        path: "session.cast",
        ansiMode: "preserved",
        sha256: createHash("sha256").update(content, "utf8").digest("hex"),
        bytes: Buffer.byteLength(content, "utf8"),
        events: events.length,
        truncated,
        columns: RECORDING_COLUMNS,
        rows: RECORDING_ROWS,
        encoding: "UTF-8",
      };
    },
    elapsedSeconds() {
      return Number(((Date.now() - startedAt) / 1000).toFixed(6));
    },
  };
}

async function main() {
  if (process.argv.length !== 13) {
    fail(
      "usage: run_terminal.mjs JAVA PYTHON JAR WORKSPACE CONFIG TRACE PROMPT " +
        "ACCEPTANCE RECORDING RESULT_JSON TIMEOUT_SECONDS",
    );
  }
  const [
    javaExecutable,
    pythonExecutable,
    jar,
    workspace,
    config,
    trace,
    promptFile,
    acceptanceScript,
    recordingFile,
    resultFile,
    timeoutValue,
  ] = process.argv.slice(2);
  const timeoutSeconds = Number.parseInt(timeoutValue, 10);
  if (!Number.isInteger(timeoutSeconds) || timeoutSeconds <= 0) fail("timeout must be positive");

  const pty = loadPty();
  const terminal = pty.spawn(
    javaExecutable,
    [
      "-jar",
      jar,
      "--terminal",
      "--workspace",
      workspace,
      "--config",
      config,
      "--approval",
      "auto",
      "--timeout",
      `PT${timeoutSeconds}S`,
      "--trace",
      "detail",
      "--trace-file",
      trace,
      "--verbose",
    ],
    {
      cwd: workspace,
      env: process.env,
      cols: RECORDING_COLUMNS,
      rows: RECORDING_ROWS,
      name: "xterm-256color",
      useConpty: process.platform === "win32",
    },
  );
  const state = {
    screen: new VirtualScreen(RECORDING_COLUMNS, RECORDING_ROWS),
    exited: false,
    exitCode: null,
  };
  const recorder = createRecorder();
  const terminalStates = [];
  const inputTimeline = [];
  terminal.onData((chunk) => {
    state.screen.feed(chunk);
    recorder.record(chunk);
    process.stdout.write(chunk);
  });
  const exited = new Promise((resolve) => {
    terminal.onExit(({ exitCode }) => {
      state.exited = true;
      state.exitCode = exitCode;
      resolve(exitCode);
    });
  });

  const timeoutMillis = timeoutSeconds * 1000;
  const transitionTimeoutMillis = Math.min(timeoutMillis, MAX_TERMINAL_TRANSITION_WAIT_MILLIS);
  const startedAt = Date.now();
  try {
    await waitForStatusMarker(state, ["IDLE"], "terminal startup", transitionTimeoutMillis);
    terminalStates.push({ state: "IDLE", atSeconds: recorder.elapsedSeconds() });
    await new Promise((resolve) => setTimeout(resolve, 500));
    const prompt = fs.readFileSync(promptFile, "utf8").trim();
    inputTimeline.push({
      action: "objective",
      atSeconds: recorder.elapsedSeconds(),
      characters: Array.from(prompt).length,
    });
    await typeAndSend(terminal, prompt);
    await waitForStatusMarker(state, ["RUNNING"], "run start", transitionTimeoutMillis);
    terminalStates.push({ state: "RUNNING", atSeconds: recorder.elapsedSeconds() });
    const terminalState = await waitForStatusMarker(
      state,
      RUN_TERMINAL_STATES,
      "autonomous run completion",
      timeoutMillis,
    );
    terminalStates.push({ state: terminalState, atSeconds: recorder.elapsedSeconds() });
  } catch (error) {
    fail(error.message, terminal);
  }
  const completedAt = Date.now();
  const acceptance = spawnSync(pythonExecutable, [acceptanceScript, workspace], {
    cwd: workspace,
    encoding: "utf8",
    timeout: Math.min(300_000, timeoutMillis),
    windowsHide: true,
    maxBuffer: 1024 * 1024,
  });
  inputTimeline.push({
    action: "quit",
    atSeconds: recorder.elapsedSeconds(),
    characters: "/quit".length,
  });
  await typeAndSend(terminal, "/quit");
  const quitTimeout = new Promise((resolve) => setTimeout(() => resolve(null), 60_000));
  const terminalExitStatus = await Promise.race([exited, quitTimeout]);
  if (terminalExitStatus == null) fail("TIMEOUT waiting for /quit", terminal);
  const recording = recorder.finalize(recordingFile);

  const payload = {
    schemaVersion: 2,
    driverProtocolVersion: DRIVER_PROTOCOL_VERSION,
    terminalBackend: process.platform === "win32" ? "conpty" : "unix-pty",
    terminalExitStatus,
    agentWallTimeSeconds: Number(((completedAt - startedAt) / 1000).toFixed(3)),
    acceptanceExitStatus: acceptance.status ?? 124,
    acceptanceStdout: (acceptance.stdout ?? "").trim(),
    acceptanceStderr: (acceptance.stderr ?? "").trim(),
    acceptancePassed: acceptance.status === 0,
    interactionCount: 1,
    humanFollowUps: 0,
    terminalStates,
    inputTimeline,
    recording,
  };
  fs.writeFileSync(resultFile, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
  process.stdout.write(
    `\nBLACK_BOX_ACCEPTANCE: ${payload.acceptancePassed ? "PASS" : "FAIL"}\n`,
  );
  process.exit(payload.acceptancePassed ? 0 : 40);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
