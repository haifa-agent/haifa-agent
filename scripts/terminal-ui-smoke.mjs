#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "..");

function parseArguments(values) {
  const result = new Map();
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index];
    const value = values[index + 1];
    if (!key?.startsWith("--") || value == null) {
      throw new Error(
        "Usage: node scripts/terminal-ui-smoke.mjs --run-root <outside-repository-path> " +
          "[--jar <path>] [--node-pty <module-directory>]",
      );
    }
    result.set(key, value);
  }
  return result;
}

function requireAbsolutePath(value, label) {
  if (!value) {
    throw new Error(`${label} is required`);
  }
  if (!path.isAbsolute(value)) {
    throw new Error(`${label} must be an absolute path`);
  }
  return path.resolve(value);
}

function isInside(parent, candidate) {
  const relative = path.relative(parent, candidate);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

function loadPty(moduleDirectory) {
  const candidates = [
    moduleDirectory,
    process.env.HAIFA_NODE_PTY_MODULE,
    "@lydell/node-pty",
    "node-pty",
  ].filter(Boolean);
  const failures = [];
  for (const candidate of candidates) {
    try {
      return { module: require(candidate), source: candidate };
    } catch (error) {
      failures.push(`${candidate}: ${error.code ?? error.message}`);
    }
  }
  throw new Error(`A Node ConPTY module is required.\n${failures.join("\n")}`);
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function stripTerminalControls(value) {
  return value
    .replace(/\u001b\][^\u0007]*(?:\u0007|\u001b\\)/g, "")
    .replace(/\u001bP[\s\S]*?\u001b\\/g, "")
    .replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, "")
    .replace(/\u001b[@-_]/g, "")
    .replace(/\r/g, "")
    .replace(/[ \t]+\n/g, "\n");
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function quoteForCmd(value) {
  if (value.includes('"')) {
    throw new Error("CLI arguments must not contain double quotes");
  }
  return `"${value}"`;
}

async function writeCharacters(terminal, text) {
  for (const character of text) {
    terminal.write(character);
    await sleep(character === "\r" ? 80 : 12);
  }
}

async function waitFor(check, timeoutMillis, description) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    if (check()) {
      return;
    }
    await sleep(50);
  }
  throw new Error(`Timed out waiting for ${description}`);
}

const argumentsByName = parseArguments(process.argv.slice(2));
const runRoot = requireAbsolutePath(argumentsByName.get("--run-root"), "--run-root");
const jar = path.resolve(
  argumentsByName.get("--jar") ??
    path.join(
      repositoryRoot,
      "haifa-agent-applications",
      "haifa-agent-cli",
      "target",
      "haifa-agent-cli-0.1.0-SNAPSHOT.jar",
    ),
);
const pty = loadPty(argumentsByName.get("--node-pty"));

if (isInside(repositoryRoot, runRoot)) {
  throw new Error("--run-root must be outside the source repository");
}
if (fs.existsSync(runRoot)) {
  throw new Error("--run-root must not already exist");
}
if (!fs.existsSync(jar) || !fs.statSync(jar).isFile()) {
  throw new Error(`CLI jar is unavailable: ${jar}`);
}

const workspace = path.join(runRoot, "workspace");
const exportsDirectory = path.join(workspace, "exports");
const artifacts = path.join(runRoot, "artifacts");
const configuration = path.join(runRoot, "offline-terminal.yaml");
const traceFile = path.join(artifacts, "trace.jsonl");
const ansiFile = path.join(artifacts, "terminal.ansi");
const textFile = path.join(artifacts, "terminal.txt");
const castFile = path.join(artifacts, "terminal.cast");
const interactionFile = path.join(artifacts, "interaction.jsonl");
const manifestFile = path.join(artifacts, "manifest.json");

fs.mkdirSync(exportsDirectory, { recursive: true });
fs.mkdirSync(artifacts, { recursive: true });
fs.writeFileSync(
  path.join(workspace, "AGENTS.md"),
  [
    "# Terminal smoke project instruction",
    "",
    "This synthetic workspace is used only for an offline Terminal UI smoke test.",
    "",
  ].join("\n"),
  "utf8",
);
fs.writeFileSync(
  configuration,
  [
    "model:",
    "  providerId: deepseek",
    "  modelId: deepseek-chat",
    "  endpoint: https://api.deepseek.com",
    "  credentialRef: env://HAIFA_TERMINAL_SMOKE_MODEL_KEY",
    "",
    "tools:",
    "  enabled:",
    "    - file.list",
    "    - file.stat",
    "    - file.read",
    "    - file.search",
    "    - file.create",
    "    - file.write",
    "    - file.delete",
    "    - file.move",
    "    - execution.run",
    "",
    "approval:",
    "  mode: auto",
    "",
    "execution:",
    "  provider: host-guarded",
    "  network: allow",
    "  shell: powershell",
    "",
    "runtime:",
    "  maxIterations: 5",
    "  maxToolCalls: 5",
    "  maxWallTimeMillis: 30000",
    "",
    "persistence:",
    "  mode: MEMORY",
    "",
  ].join("\n"),
  "utf8",
);

const startedAt = Date.now();
const outputEvents = [];
const interactionEvents = [];
let terminalOutput = "";
let exited = null;

function hasTerminalText(marker) {
  return terminalOutput.toLocaleLowerCase("en-US").includes(marker.toLocaleLowerCase("en-US"));
}

const javaCommand = [
  "java.exe",
  "-jar",
  quoteForCmd(jar),
  "--terminal",
  "--workspace",
  quoteForCmd(workspace),
  "--config",
  quoteForCmd(configuration),
  "--trace",
  "jsonl",
  "--trace-file",
  quoteForCmd(traceFile),
  "--verbose",
].join(" ");
const child = pty.module.spawn(
  "cmd.exe",
  ["/d", "/q", "/v:on"],
  {
    name: "xterm-256color",
    cols: 120,
    rows: 40,
    cwd: repositoryRoot,
    env: {
      ...process.env,
      HAIFA_TERMINAL_SMOKE_MODEL_KEY: "offline-smoke-key-not-used",
      TERM: "xterm-256color",
    },
  },
);

child.onData((data) => {
  const elapsedSeconds = (Date.now() - startedAt) / 1000;
  terminalOutput += data;
  outputEvents.push([elapsedSeconds, "o", data]);
});
const exitPromise = new Promise((resolve) => {
  child.onExit((event) => {
    exited = event;
    resolve(event);
  });
});
await writeCharacters(child, `chcp 65001>nul && ${javaCommand} & exit /b !errorlevel!\r`);

async function send(text, settleMillis = 600) {
  const elapsedSeconds = (Date.now() - startedAt) / 1000;
  interactionEvents.push({
    elapsedSeconds,
    kind: "input",
    text: text.replace(/\r/g, "<ENTER>").replace(/\u001b/g, "<ESC>"),
  });
  outputEvents.push([elapsedSeconds, "i", text]);
  await writeCharacters(child, text);
  await sleep(settleMillis);
}

let failure = null;
try {
  await waitFor(() => hasTerminalText("Haifa Coding Agent"), 15_000, "Terminal UI startup");
  await send("/commands\r");
  await send("\u001b");
  await send("/reload\r");
  await send("/session\r");
  await send("\u001b");
  await send("/resume definitely-missing\r");
  await send("/tree\r");
  await send("/fork\r");
  await send("/clone\r");
  await send("/quit\r");
  await Promise.race([
    exitPromise,
    sleep(10_000).then(() => {
      throw new Error("Timed out waiting for Terminal UI exit");
    }),
  ]);
} catch (error) {
  failure = error;
  try {
    child.kill();
  } catch {
    // Best-effort cleanup. The original failure remains authoritative.
  }
  await Promise.race([exitPromise, sleep(2_000)]);
}

fs.writeFileSync(ansiFile, terminalOutput, "utf8");
fs.writeFileSync(textFile, stripTerminalControls(terminalOutput), "utf8");
fs.writeFileSync(
  castFile,
  [
    JSON.stringify({
      version: 2,
      width: 120,
      height: 40,
      timestamp: Math.floor(startedAt / 1000),
      env: { SHELL: "powershell", TERM: "xterm-256color" },
    }),
    ...outputEvents.map((event) => JSON.stringify(event)),
    "",
  ].join("\n"),
  "utf8",
);
fs.writeFileSync(
  interactionFile,
  interactionEvents.map((event) => JSON.stringify(event)).join("\n") + "\n",
  "utf8",
);

const assertions = {
  started: hasTerminalText("Haifa Coding Agent"),
  alternateScreenEntered: terminalOutput.includes("\u001b[?1049h"),
  alternateScreenExited: terminalOutput.includes("\u001b[?1049l"),
  commandSelectorVisible: terminalOutput.includes("Commands"),
  emptySessionHandled: terminalOutput.includes("SESSION_LIST_EMPTY"),
  deferredCapabilityVisible: terminalOutput.includes("CAPABILITY_NOT_IMPLEMENTED"),
  noUnknownCommand: !terminalOutput.includes("COMMAND_UNKNOWN"),
  exitedSuccessfully: exited?.exitCode === 0,
};
const manifest = {
  schema: "haifa.terminal-ui-smoke/1",
  startedAt: new Date(startedAt).toISOString(),
  completedAt: new Date().toISOString(),
  repositoryRoot,
  runRoot,
  workspace,
  jar,
  jarSha256: sha256(jar),
  nodePtySource: pty.source,
  exit: exited,
  assertions,
  passed: failure == null && Object.values(assertions).every(Boolean),
  failure: failure?.message ?? null,
  artifacts: {
    ansi: ansiFile,
    text: textFile,
    cast: castFile,
    interaction: interactionFile,
    trace: traceFile,
  },
};
fs.writeFileSync(manifestFile, JSON.stringify(manifest, null, 2) + "\n", "utf8");

process.stdout.write(`${JSON.stringify(manifest, null, 2)}\n`);
process.exit(manifest.passed ? 0 : 1);
