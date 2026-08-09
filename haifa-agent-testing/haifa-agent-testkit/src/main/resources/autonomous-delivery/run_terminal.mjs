#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const DRIVER_PROTOCOL_VERSION = "1.2.0";
const RUN_TERMINAL_STATES = ["IDLE", "COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"];
const RECORDING_COLUMNS = 132;
const RECORDING_ROWS = 42;
const MAX_RECORDED_OUTPUT_BYTES = 1024 * 1024;

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

function waitForMarker(state, marker, label, timeoutMillis) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMillis;
    const poll = () => {
      const markerIndex = state.output.indexOf(marker, state.markerOffset);
      if (markerIndex >= 0) {
        state.markerOffset = markerIndex + marker.length;
        resolve();
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

function waitForAnyMarker(state, markers, label, timeoutMillis) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMillis;
    const poll = () => {
      const matches = markers
        .map((marker) => ({ marker, index: state.output.indexOf(marker, state.markerOffset) }))
        .filter((match) => match.index >= 0)
        .sort((left, right) => left.index - right.index);
      if (matches.length > 0) {
        const match = matches[0];
        state.markerOffset = match.index + match.marker.length;
        resolve(match.marker);
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
  const state = { output: "", markerOffset: 0, exited: false, exitCode: null };
  const recorder = createRecorder();
  const terminalStates = [];
  const inputTimeline = [];
  terminal.onData((chunk) => {
    const combined = state.output + chunk;
    const removedCharacters = Math.max(0, combined.length - 1024 * 1024);
    state.output = combined.slice(removedCharacters);
    state.markerOffset = Math.max(0, state.markerOffset - removedCharacters);
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
  const startedAt = Date.now();
  try {
    await waitForMarker(state, "IDLE", "terminal startup", timeoutMillis);
    terminalStates.push({ state: "IDLE", atSeconds: recorder.elapsedSeconds() });
    await new Promise((resolve) => setTimeout(resolve, 500));
    const prompt = fs.readFileSync(promptFile, "utf8").trim();
    inputTimeline.push({
      action: "objective",
      atSeconds: recorder.elapsedSeconds(),
      characters: Array.from(prompt).length,
    });
    await typeAndSend(terminal, prompt);
    await waitForMarker(state, "RUNNING", "run start", timeoutMillis);
    terminalStates.push({ state: "RUNNING", atSeconds: recorder.elapsedSeconds() });
    const terminalState = await waitForAnyMarker(
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

await main();
