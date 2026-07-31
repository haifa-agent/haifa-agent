#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { execFileSync } from "node:child_process";
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
        "Usage: node scripts/terminal-ui-conpty-acceptance.mjs " +
          "--run-root <outside-repository-path> --attempt <1|2|3> " +
          "[--jar <path>] [--node-pty <module-directory>]",
      );
    }
    result.set(key, value);
  }
  return result;
}

function requireAbsolutePath(value, label) {
  if (!value) throw new Error(`${label} is required`);
  if (!path.isAbsolute(value)) throw new Error(`${label} must be an absolute path`);
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
    .replace(/\uFFFF/g, "")
    .replace(/\r/g, "")
    .replace(/[ \t]+\n/g, "\n");
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function writeCharacters(terminal, text) {
  if (text.startsWith("\u001b") && text.length > 1) {
    // A real terminal delivers a special key's CSI sequence as one key event.
    // Splitting ESC [ A makes tui4j correctly interpret ESC as "close selector".
    terminal.write(text);
    await sleep(80);
    return;
  }
  for (const character of text) {
    terminal.write(character);
    await sleep(character === "\r" ? 100 : 10);
  }
}

async function waitFor(check, timeoutMillis, description) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    if (check()) return;
    await sleep(50);
  }
  throw new Error(`Timed out waiting for ${description}`);
}

function writeJson(file, value) {
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function runGit(workspace, args, executable = "git.exe") {
  return execFileSync(executable, args, {
    cwd: workspace,
    encoding: "utf8",
    windowsHide: true,
  });
}

function createFixture(workspace, gitExecutable) {
  fs.mkdirSync(path.join(workspace, "src", "main", "java", "sample"), { recursive: true });
  fs.mkdirSync(path.join(workspace, "src", "test", "java", "sample"), { recursive: true });
  fs.mkdirSync(path.join(workspace, "exports"), { recursive: true });
  fs.writeFileSync(
    path.join(workspace, "AGENTS.md"),
    [
      "# ConPTY acceptance workspace",
      "",
      "Work only inside this synthetic workspace.",
      "Use workspace-relative paths and run verify.ps1 before finishing a coding task.",
      "",
    ].join("\n"),
    "utf8",
  );
  fs.writeFileSync(
    path.join(workspace, "src", "main", "java", "sample", "Clamp.java"),
    [
      "package sample;",
      "",
      "public final class Clamp {",
      "    private Clamp() {}",
      "",
      "    public static int clamp(int value, int minimum, int maximum) {",
      "        if (value < minimum) return value;",
      "        if (value > maximum) return value;",
      "        return value;",
      "    }",
      "}",
      "",
    ].join("\n"),
    "utf8",
  );
  fs.writeFileSync(
    path.join(workspace, "src", "test", "java", "sample", "ClampTest.java"),
    [
      "package sample;",
      "",
      "public final class ClampTest {",
      "    public static void main(String[] args) {",
      "        require(Clamp.clamp(-4, 0, 10) == 0, \"lower boundary\");",
      "        require(Clamp.clamp(14, 0, 10) == 10, \"upper boundary\");",
      "        require(Clamp.clamp(6, 0, 10) == 6, \"inside interval\");",
      "    }",
      "",
      "    private static void require(boolean condition, String message) {",
      "        if (!condition) throw new AssertionError(message);",
      "    }",
      "}",
      "",
    ].join("\n"),
    "utf8",
  );
  fs.writeFileSync(
    path.join(workspace, "verify.ps1"),
    [
      "$ErrorActionPreference = 'Stop'",
      "$classes = Join-Path $PSScriptRoot '.classes'",
      "New-Item -ItemType Directory -Force -Path $classes | Out-Null",
      "& javac -d $classes `",
      "  (Join-Path $PSScriptRoot 'src/main/java/sample/Clamp.java') `",
      "  (Join-Path $PSScriptRoot 'src/test/java/sample/ClampTest.java')",
      "if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }",
      "& java -cp $classes sample.ClampTest",
      "exit $LASTEXITCODE",
      "",
    ].join("\r\n"),
    "utf8",
  );
  runGit(workspace, ["init", "--initial-branch=main"], gitExecutable);
  runGit(workspace, ["config", "user.name", "Haifa ConPTY Test"], gitExecutable);
  runGit(workspace, ["config", "user.email", "conpty-test@invalid.local"], gitExecutable);
  runGit(workspace, ["add", "AGENTS.md", "src", "verify.ps1"], gitExecutable);
  runGit(workspace, ["commit", "-m", "fixture: initialize conpty acceptance workspace"], gitExecutable);
}

function writeConfiguration(file, databasePath, approvalMode, provider) {
  fs.writeFileSync(
    file,
    [
      "models:",
      "  default: conpty-test-model",
      "  providers:",
      "    - id: deepseek",
      "      displayName: ConPTY Test Provider",
      `      endpoint: ${provider.endpoint}`,
      `      credentialRef: env://${provider.credentialEnvironment}`,
      "      models:",
      "        - id: conpty-test-model",
      "          displayName: ConPTY Test Model",
      "          providerModelId: deepseek-chat",
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
      `  mode: ${approvalMode}`,
      "",
      "execution:",
      "  provider: host-guarded",
      "  network: allow",
      "  shell: powershell",
      "  defaultTimeoutMillis: 120000",
      "  maxTimeoutMillis: 600000",
      "  maxOutputLines: 2000",
      "  maxOutputBytes: 51200",
      "  maxProcesses: 8",
      "  inheritEnvironment:",
      "    - PATH",
      "    - PATHEXT",
      "    - USERPROFILE",
      "    - TEMP",
      "    - TMP",
      "    - SystemRoot",
      "    - SystemDrive",
      "    - ProgramData",
      "    - JAVA_HOME",
      "",
      "runtime:",
      "  maxIterations: 64",
      "  maxToolCalls: 40",
      "  maxWallTimeMillis: 600000",
      "",
      "persistence:",
      "  mode: SQLITE",
      `  databasePath: ${databasePath.replaceAll("\\", "\\\\")}`,
      "  protectorRef: env://HAIFA_CONTINUATION_KEY",
      "",
    ].join("\n"),
    "utf8",
  );
}

async function startStubProvider() {
  const requests = [];
  const server = http.createServer((request, response) => {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => {
      let body = {};
      try {
        body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      } catch {
        // The response below remains deterministic; malformed input is recorded without echoing it.
      }
      const messages = Array.isArray(body.messages) ? body.messages : [];
      const latestUser = messages
        .toReversed()
        .find((message) => message?.role === "user" && typeof message?.content === "string");
      const longOutput = latestUser?.content?.includes("GATE_B_LONG_OUTPUT") === true;
      const governanceReadOnly = messages.some(
        (message) => message?.role === "user" && message?.content?.includes("GOVERNANCE_READ_ONLY"),
      );
      const governanceToolObserved = messages.some((message) => message?.role === "tool");
      const requestGovernanceTool = governanceReadOnly && !governanceToolObserved;
      const content = longOutput
        ? Array.from(
            { length: 40 },
            (_, index) => `STUB-LONG-LINE-${String(index + 1).padStart(2, "0")} ${"x".repeat(72)}`,
          ).join("\n")
        : "READY";
      requests.push({
        method: request.method,
        path: request.url,
        stream: body.stream === true,
        messageCount: messages.length,
        longOutput,
        requestGovernanceTool,
      });
      const events = [
        requestGovernanceTool
          ? {
              id: `gate-b-stub-${requests.length}`,
              model: "deepseek-chat",
              choices: [
                {
                  index: 0,
                  delta: {
                    tool_calls: [
                      {
                        index: 0,
                        id: "governance-read-only-1",
                        type: "function",
                        function: {
                          name: "file_search",
                          arguments: '{"path":".","query":"Clamp","maxResults":10}',
                        },
                      },
                    ],
                  },
                  finish_reason: "tool_calls",
                },
              ],
            }
          : {
              id: `gate-b-stub-${requests.length}`,
              model: "deepseek-chat",
              choices: [{ index: 0, delta: { content }, finish_reason: "stop" }],
            },
        {
          id: `gate-b-stub-${requests.length}`,
          model: "deepseek-chat",
          choices: [],
          usage: { prompt_tokens: 8, completion_tokens: longOutput ? 320 : 1 },
        },
      ];
      response.writeHead(200, {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-cache",
        Connection: "close",
      });
      for (const event of events) response.write(`data: ${JSON.stringify(event)}\n\n`);
      response.end("data: [DONE]\n\n");
    });
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  if (address == null || typeof address === "string") {
    server.close();
    throw new Error("Loopback Stub Provider did not expose a TCP port");
  }
  return {
    endpoint: `http://127.0.0.1:${address.port}`,
    credentialEnvironment: "HAIFA_CONPTY_STUB_KEY",
    credential: "local-conpty-stub-key",
    requests,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

class VirtualScreen {
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

const argumentsByName = parseArguments(process.argv.slice(2));
const runRoot = requireAbsolutePath(argumentsByName.get("--run-root"), "--run-root");
const attempt = Number(argumentsByName.get("--attempt"));
if (!Number.isInteger(attempt) || attempt < 1 || attempt > 3) {
  throw new Error("--attempt must be 1, 2, or 3");
}
const mode = argumentsByName.get("--mode") ?? "full";
if (!["full", "approval", "viewport", "governance"].includes(mode)) {
  throw new Error("--mode must be full, approval, viewport, or governance");
}
const providerMode = argumentsByName.get("--provider") ?? "deepseek";
if (!["deepseek", "stub"].includes(providerMode)) {
  throw new Error("--provider must be deepseek or stub");
}
const approvalMode = mode === "approval" || mode === "governance" ? "ask" : "auto";
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
const javaExecutable = argumentsByName.get("--java") ?? "java.exe";
const gitExecutable = argumentsByName.get("--git") ?? "git.exe";
const provider = providerMode === "stub"
  ? await startStubProvider()
  : {
      endpoint: "https://api.deepseek.com",
      credentialEnvironment: "DEEPSEEK_API_KEY",
      credential: process.env.DEEPSEEK_API_KEY?.trim(),
      requests: [],
      close: async () => {},
    };
if (!provider.credential) throw new Error(`${provider.credentialEnvironment} is required`);
if (isInside(repositoryRoot, runRoot)) throw new Error("--run-root must be outside the source repository");
if (fs.existsSync(runRoot)) throw new Error("--run-root must not already exist");
if (!fs.existsSync(jar) || !fs.statSync(jar).isFile()) throw new Error(`CLI jar is unavailable: ${jar}`);

const workspace = path.join(runRoot, "workspace");
const dataDirectory = path.join(runRoot, "data");
const artifacts = path.join(runRoot, "artifacts");
const configuration = path.join(runRoot, "terminal.yaml");
const database = path.join(dataDirectory, "coding-terminal.db");
const traceFile = path.join(artifacts, "trace-detail.log");
const ansiFile = path.join(artifacts, "terminal.ansi");
const textFile = path.join(artifacts, "terminal.txt");
const castFile = path.join(artifacts, "terminal.cast");
const interactionFile = path.join(artifacts, "interaction.jsonl");
const screensFile = path.join(artifacts, "screens.jsonl");
const manifestFile = path.join(artifacts, "manifest.json");
const gitStatusFile = path.join(artifacts, "git-status.txt");
const gitDiffFile = path.join(artifacts, "git-diff.patch");
fs.mkdirSync(workspace, { recursive: true });
fs.mkdirSync(dataDirectory, { recursive: true });
fs.mkdirSync(artifacts, { recursive: true });
createFixture(workspace, gitExecutable);
writeConfiguration(configuration, database, approvalMode, provider);

const startedAt = Date.now();
const outputEvents = [];
const interactionEvents = [];
const screenEvents = [];
const screen = new VirtualScreen(120, 40);
let terminalOutput = "";
let exited = null;
let failure = null;

function hasTerminalText(marker) {
  return terminalOutput.toLocaleLowerCase("en-US").includes(marker.toLocaleLowerCase("en-US"));
}

const continuationKey =
  process.env.HAIFA_CONTINUATION_KEY?.trim() ?? crypto.randomBytes(32).toString("base64");
const child = pty.module.spawn(
  javaExecutable,
  [
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
    "-jar",
    jar,
    "--terminal",
    "--workspace",
    workspace,
    "--config",
    configuration,
    "--approval",
    approvalMode,
    "--trace",
    "detail",
    "--trace-file",
    traceFile,
    "--verbose",
  ],
  {
    name: "xterm-256color",
    cols: 120,
    rows: 40,
    cwd: workspace,
    env: {
      ...process.env,
      [provider.credentialEnvironment]: provider.credential,
      ...(providerMode === "stub" ? { HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL: "true" } : {}),
      HAIFA_CONTINUATION_KEY: continuationKey,
      TERM: "xterm-256color",
    },
  },
);

function captureScreen(label) {
  screenEvents.push({
    elapsedSeconds: (Date.now() - startedAt) / 1000,
    label,
    screen: screen.text(),
  });
}

child.onData((data) => {
  const elapsedSeconds = (Date.now() - startedAt) / 1000;
  terminalOutput += data;
  outputEvents.push([elapsedSeconds, "o", data]);
  screen.feed(data);
});
const exitPromise = new Promise((resolve) => {
  child.onExit((event) => {
    exited = event;
    resolve(event);
  });
});

async function send(text, label, settleMillis = 500) {
  const elapsedSeconds = (Date.now() - startedAt) / 1000;
  interactionEvents.push({
    elapsedSeconds,
    label,
    kind: "input",
    text: text.replace(/\r/g, "<ENTER>").replace(/\u001b/g, "<ESC>"),
  });
  outputEvents.push([elapsedSeconds, "i", text]);
  await writeCharacters(child, text);
  await sleep(settleMillis);
  captureScreen(label);
}

async function sendAndWait(text, label, marker, timeoutMillis) {
  const start = terminalOutput.length;
  await send(text, label);
  await waitFor(() => terminalOutput.slice(start).includes(marker), timeoutMillis, `${label}: ${marker}`);
  // A marker can arrive before the remainder of the same tui4j diff frame.
  await sleep(300);
  captureScreen(`${label}:observed`);
}

async function sendAndWaitForTraceStop(text, label, timeoutMillis) {
  const traceStart = fs.existsSync(traceFile) ? fs.readFileSync(traceFile, "utf8").length : 0;
  await send(text, label);
  await waitFor(() => {
    if (!fs.existsSync(traceFile)) return false;
    return fs.readFileSync(traceFile, "utf8").slice(traceStart).includes("finishReason=STOP");
  }, timeoutMillis, `${label}: trace finishReason=STOP`);
  // The Runtime writes the model STOP trace before the final checkpoint/UI-idle update.
  // Keep a human-scale pause so the next selector is not opened during that final repaint.
  await sleep(5_000);
  captureScreen(`${label}:observed`);
}

const observations = {};
try {
  await waitFor(() => hasTerminalText("Haifa Coding Agent"), 20_000, "Terminal UI startup");
  captureScreen("startup");

  let start = terminalOutput.length;
  await send("/help\r", "help");
  observations.helpOpened = terminalOutput.slice(start).includes("Commands");
  if (observations.helpOpened) await send("\u001b", "help-close");

  await sendAndWait("/commands\r", "commands", "Commands", 5_000);
  await send("\u001b", "commands-close");

  await sendAndWaitForTraceStop(
    mode === "governance"
      ? "GOVERNANCE_READ_ONLY：使用 file_search 查找 Clamp，然后只回复 READY。\r"
      : "只回复 READY，不调用任何工具。\r",
    "seed-session",
    120_000,
  );
  observations.seedRunCompleted = true;

  if (mode === "viewport") {
    for (let index = 1; index <= 12; index += 1) {
      await sendAndWait(
        `!Write-Output VIEWPORT-LINE-${index}\r`,
        `viewport-shell-${index}`,
        `VIEWPORT-LINE-${index} [succeeded]`,
        20_000,
      );
    }
    observations.viewportBounded = true;
  } else if (mode === "approval" || mode === "governance") {
    if (mode === "governance") {
      await sendAndWait(
        "!Write-Output APPROVAL-DENIED\r",
        "shell-denied-approval",
        "Run governed shell command?",
        20_000,
      );
      await sendAndWait(
        "\r",
        "shell-denied-confirm",
        "Shell command denied",
        20_000,
      );
      observations.deniedShellRejected = true;
    }
    await sendAndWait(
      "!Write-Output APPROVAL-INCLUDED\r",
      "shell-included-approval",
      "Run governed shell command?",
      20_000,
    );
    await send("\u001b[A", "shell-included-approval-select");
    await sendAndWait(
      "\r",
      "shell-included-approval-confirm",
      "Shell result added to Session context",
      20_000,
    );
    await sendAndWait(
      "!!Write-Output APPROVAL-EXCLUDED\r",
      "shell-excluded-approval",
      "Run governed shell command?",
      20_000,
    );
    await send("\u001b[A", "shell-excluded-approval-select");
    await sendAndWait(
      "\r",
      "shell-excluded-approval-confirm",
      "Shell result excluded from model context",
      20_000,
    );
    await sendAndWait(
      "!javac -version\r",
      "shell-javac-approval",
      "Run governed shell command?",
      20_000,
    );
    await send("\u001b[A", "shell-javac-approval-select");
    await sendAndWait(
      "\r",
      "shell-javac-approval-confirm",
      "Shell result added to Session context",
      20_000,
    );
    await sendAndWait(
      "!powershell -NoProfile -Command \"Write-Output NESTED-POWERSHELL\"\r",
      "shell-powershell-approval",
      "Run governed shell command?",
      20_000,
    );
    await send("\u001b[A", "shell-powershell-approval-select");
    await sendAndWait(
      "\r",
      "shell-powershell-approval-confirm",
      "Shell result added to Session context",
      20_000,
    );
    observations.approvalSelectorsCompleted = true;
    observations.windowsCommandResolutionCompleted =
      terminalOutput.includes("javac 21") && terminalOutput.includes("NESTED-POWERSHELL");
  } else {
  await sendAndWait("/rename conpty-live-session\r", "rename", "Session renamed", 10_000);

  await sendAndWait("/resume conpty-live\r", "resume", "Resume session", 10_000);
  await send("\r", "resume-select");

  await sendAndWait("/compact\r", "compact", "Session context compacted", 20_000);
  fs.appendFileSync(
    path.join(workspace, "AGENTS.md"),
    "\nReload marker: conpty-acceptance-v2\n",
    "utf8",
  );
  await sendAndWait(
    "/reload\r",
    "reload",
    "Resources reloaded for future new Runs",
    10_000,
  );
  await sendAndWait(
    "!Write-Output INCLUDED-SHELL\r",
    "shell-included",
    "Shell result added to Session context",
    20_000,
  );
  await sendAndWait(
    "!!Write-Output EXCLUDED-SHELL\r",
    "shell-excluded",
    "Shell result excluded from model context",
    20_000,
  );
  await sendAndWait(
    "/export exports/session.jsonl\r",
    "export",
    "Session exported",
    20_000,
  );
  if (providerMode === "stub") {
    await sendAndWaitForTraceStop(
      "GATE_B_LONG_OUTPUT：输出 40 行有编号的安全测试文本。\r",
      "long-model-output",
      60_000,
    );
    await waitFor(
      () => terminalOutput.includes("STUB-LONG-LINE-40"),
      10_000,
      "long-model-output: final line",
    );
    await sleep(300);
    captureScreen("long-model-output:final");
    observations.longModelOutputCompleted = true;
  } else {
    await sendAndWaitForTraceStop(
      "修复 src/main/java/sample/Clamp.java：小于 minimum 时返回 minimum，大于 maximum 时返回 maximum，" +
        "保持公开 API。必须运行 powershell -NoProfile -ExecutionPolicy Bypass -File verify.ps1，验证通过后停止工具调用并总结。\r",
      "live-coding",
      600_000,
    );
    observations.liveCodingCompleted = true;
  }
  await sleep(1_000);
  captureScreen("full-final-stable");
  const finalFrame = screen.text();
  observations.finalFrameHeaderVisible = finalFrame.includes("HAIFA CODING AGENT");
  observations.finalFrameFooterVisible = finalFrame.includes("Footer  Enter sends");
  const finalFooterLines = finalFrame
    .split("\n")
    .filter((line) => line.includes("sandbox: frozen profile"));
  observations.finalFrameLifecycleConsistent =
    finalFooterLines.some((line) => line.includes("COMPLETED")) &&
    finalFooterLines.every((line) => !line.includes("RUNNING"));
  }
  await send("/quit\r", "quit");
  await Promise.race([
    exitPromise,
    sleep(15_000).then(() => {
      throw new Error("Timed out waiting for Terminal UI exit");
    }),
  ]);
} catch (error) {
  failure = error;
  captureScreen("failure");
  try {
    child.kill();
  } catch {
    // Best-effort cleanup. The original failure remains authoritative.
  }
  await Promise.race([exitPromise, sleep(3_000)]);
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
    ...outputEvents.filter((event) => event[1] === "o").map((event) => JSON.stringify(event)),
    "",
  ].join("\n"),
  "utf8",
);
fs.writeFileSync(
  interactionFile,
  interactionEvents.map((event) => JSON.stringify(event)).join("\n") + "\n",
  "utf8",
);
fs.writeFileSync(
  screensFile,
  screenEvents.map((event) => JSON.stringify(event)).join("\n") + "\n",
  "utf8",
);

const gitStatus = runGit(workspace, ["status", "--short"], gitExecutable);
const gitDiff = runGit(workspace, ["diff", "--no-ext-diff", "--", "AGENTS.md", "src"], gitExecutable);
fs.writeFileSync(gitStatusFile, gitStatus, "utf8");
fs.writeFileSync(gitDiffFile, gitDiff, "utf8");

const exportFile = path.join(workspace, "exports", "session.jsonl");
const secretScanFiles = [ansiFile, textFile, traceFile, exportFile, gitDiffFile].filter((file) =>
  fs.existsSync(file),
);
const keyLeakFiles = secretScanFiles.filter((file) =>
  fs.readFileSync(file, "utf8").includes(provider.credential),
);
const commonAssertions = {
  started: hasTerminalText("Haifa Coding Agent"),
  alternateScreenEntered: terminalOutput.includes("\u001b[?1049h"),
  alternateScreenExited: terminalOutput.includes("\u001b[?1049l"),
  helpOpened: observations.helpOpened === true,
  commandSelectorVisible: terminalOutput.includes("Commands"),
  seedRunCompleted: observations.seedRunCompleted === true,
  noCommandUnknown: !terminalOutput.includes("COMMAND_UNKNOWN"),
  noKeyLeak: keyLeakFiles.length === 0,
  exitedSuccessfully: exited?.exitCode === 0,
};
const assertions = mode === "viewport" ? {
  ...commonAssertions,
  viewportBounded: observations.viewportBounded === true,
  latestViewportLineVisible: terminalOutput.includes("VIEWPORT-LINE-12"),
  sqliteCreated: fs.existsSync(database) && fs.statSync(database).size > 0,
} : mode === "approval" || mode === "governance" ? {
  ...commonAssertions,
  ...(mode === "governance"
    ? {
        deniedShellRejected: observations.deniedShellRejected === true,
        deniedShellNotExecuted: !terminalOutput.includes("APPROVAL-DENIED [succeeded]"),
      }
    : {}),
  approvalSelectorsCompleted: observations.approvalSelectorsCompleted === true,
  windowsCommandResolutionCompleted: observations.windowsCommandResolutionCompleted === true,
  includedShellCompleted: terminalOutput.includes("Shell result added to Session context"),
  excludedShellCompleted: terminalOutput.includes("Shell result excluded from model context"),
  sqliteCreated: fs.existsSync(database) && fs.statSync(database).size > 0,
} : {
  ...commonAssertions,
  sessionRenamed: terminalOutput.includes("Session renamed"),
  sessionResumed: terminalOutput.includes("Resume session"),
  sessionCompacted: terminalOutput.includes("Session context compacted"),
  resourcesReloaded: terminalOutput.includes("Resources reloaded for future new Runs"),
  includedShellCompleted: terminalOutput.includes("Shell result added to Session context"),
  excludedShellCompleted: terminalOutput.includes("Shell result excluded from model context"),
  sessionExported: terminalOutput.includes("Session exported") && fs.existsSync(exportFile),
  sqliteCreated: fs.existsSync(database) && fs.statSync(database).size > 0,
  finalFrameHeaderVisible: observations.finalFrameHeaderVisible === true,
  finalFrameFooterVisible: observations.finalFrameFooterVisible === true,
  finalFrameLifecycleConsistent: observations.finalFrameLifecycleConsistent === true,
  ...(providerMode === "stub"
    ? {
        longModelOutputCompleted: observations.longModelOutputCompleted === true,
        longModelOutputVisible: terminalOutput.includes("STUB-LONG-LINE-40"),
      }
    : {
        liveCodingCompleted: observations.liveCodingCompleted === true,
        clampChanged: gitDiff.includes("return minimum;") && gitDiff.includes("return maximum;"),
      }),
  noUnexpectedSourceChanges: gitStatus
    .split(/\r?\n/)
    .filter(Boolean)
    .every((line) =>
      [
        " M AGENTS.md",
        " M src/main/java/sample/Clamp.java",
        "?? .classes/",
        "?? exports/",
      ].some((prefix) => line.startsWith(prefix)),
    ),
};
const manifest = {
  schema: "haifa.terminal-ui-conpty-acceptance/1",
  attempt,
  mode,
  providerMode,
  startedAt: new Date(startedAt).toISOString(),
  completedAt: new Date().toISOString(),
  repositoryRoot,
  runRoot,
  workspace,
  database,
  configuration,
  jar,
  jarSha256: sha256(jar),
  nodePtySource: pty.source,
  terminal: { columns: 120, rows: 40, term: "xterm-256color" },
  exit: exited,
  assertions,
  passed: failure == null && Object.values(assertions).every(Boolean),
  failure: failure?.message ?? null,
  secretScan: {
    files: secretScanFiles,
    providerCredentialMatchFiles: keyLeakFiles.length,
  },
  stubProvider: {
    requestCount: provider.requests.length,
    requests: provider.requests,
  },
  artifacts: {
    ansi: ansiFile,
    text: textFile,
    cast: castFile,
    interaction: interactionFile,
    screens: screensFile,
    trace: traceFile,
    gitStatus: gitStatusFile,
    gitDiff: gitDiffFile,
    export: exportFile,
    sqlite: database,
  },
};
writeJson(manifestFile, manifest);
await provider.close();
process.stdout.write(`${JSON.stringify(manifest, null, 2)}\n`);
process.exit(manifest.passed ? 0 : 1);
