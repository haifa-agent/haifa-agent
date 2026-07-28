#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const launcher =
  "io.haifa.agent.application.coding.terminal.tui4j.Tui4jTerminalSpikeLauncher";

function argumentsOf(values) {
  const result = new Map();
  for (let index = 0; index < values.length; index += 2) {
    if (!values[index]?.startsWith("--") || values[index + 1] == null) {
      throw new Error(
        "Usage: node scripts/terminal-ui-tui4j-spike.mjs " +
          "--run-root <outside-repository-path> --classpath <test-classpath> " +
          "[--node-pty <module-directory>]",
      );
    }
    result.set(values[index], values[index + 1]);
  }
  return result;
}

function absolute(value, label) {
  if (!value || !path.isAbsolute(value)) throw new Error(`${label} must be an absolute path`);
  return path.resolve(value);
}

function isInside(parent, candidate) {
  const relative = path.relative(parent, candidate);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

function loadPty(explicitPath) {
  const candidates = [
    explicitPath,
    process.env.HAIFA_NODE_PTY_MODULE,
    "@lydell/node-pty",
    "node-pty",
  ].filter(Boolean);
  const failures = [];
  for (const candidate of candidates) {
    try {
      return { api: require(candidate), source: candidate };
    } catch (error) {
      failures.push(`${candidate}: ${error.code ?? error.message}`);
    }
  }
  throw new Error(`A Node ConPTY module is required.\n${failures.join("\n")}`);
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function waitFor(check, timeoutMillis, description) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    if (check()) return;
    await sleep(50);
  }
  throw new Error(`Timed out waiting for ${description}`);
}

function stripControls(value) {
  return value
    .replace(/\u001b\][^\u0007]*(?:\u0007|\u001b\\)/g, "")
    .replace(/\u001bP[\s\S]*?\u001b\\/g, "")
    .replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, "")
    .replace(/\u001b[@-_]/g, "")
    .replace(/\r/g, "")
    .replace(/[ \t]+\n/g, "\n");
}

class VirtualScreen {
  constructor(columns, rows) {
    this.columns = columns;
    this.rows = rows;
    this.row = 0;
    this.column = 0;
    this.grid = this.blank();
  }

  blank() {
    return Array.from({ length: this.rows }, () => Array(this.columns).fill(" "));
  }

  resize(columns, rows) {
    const previous = this.grid;
    this.columns = columns;
    this.rows = rows;
    this.grid = this.blank();
    for (let row = 0; row < Math.min(previous.length, rows); row += 1) {
      for (let column = 0; column < Math.min(previous[row].length, columns); column += 1) {
        this.grid[row][column] = previous[row][column];
      }
    }
    this.row = Math.min(this.row, rows - 1);
    this.column = Math.min(this.column, columns - 1);
  }

  clear() {
    this.row = 0;
    this.column = 0;
    this.grid = this.blank();
  }

  feed(text) {
    for (let index = 0; index < text.length; ) {
      if (text[index] === "\u001b" && text[index + 1] === "[") {
        const match = /^\u001b\[([0-?]*)([ -/]*)([@-~])/.exec(text.slice(index));
        if (match) {
          this.csi(match[1], match[3]);
          index += match[0].length;
          continue;
        }
      }
      const codePoint = text.codePointAt(index);
      const character = String.fromCodePoint(codePoint);
      if (character === "\r") {
        this.column = 0;
      } else if (character === "\n") {
        this.lineFeed();
      } else if (character === "\b") {
        this.column = Math.max(0, this.column - 1);
      } else if (character >= " " && character !== "\u007f" && character !== "\uFFFF") {
        this.grid[this.row][this.column] = character;
        this.column += 1;
        if (this.column >= this.columns) {
          this.column = 0;
          this.lineFeed();
        }
      }
      index += character === "\u001b" ? 2 : character.length;
    }
  }

  csi(rawParameters, finalCharacter) {
    const values = rawParameters
      .replace(/^\?/, "")
      .split(";")
      .filter(Boolean)
      .map(Number);
    const value = (position, fallback = 1) => values[position] || fallback;
    if (rawParameters.startsWith("?") && finalCharacter === "h" && values.includes(1049)) {
      this.clear();
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
      case "J":
        if (values.length === 0 || values[0] === 0) {
          this.grid[this.row].fill(" ", this.column);
          for (let row = this.row + 1; row < this.rows; row += 1) {
            this.grid[row].fill(" ");
          }
        } else if (values[0] === 1) {
          for (let row = 0; row < this.row; row += 1) this.grid[row].fill(" ");
          this.grid[this.row].fill(" ", 0, this.column + 1);
        } else if (values[0] === 2 || values[0] === 3) {
          this.grid = this.blank();
        }
        break;
      case "K":
        this.grid[this.row].fill(" ", this.column);
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

const options = argumentsOf(process.argv.slice(2));
const runRoot = absolute(options.get("--run-root"), "--run-root");
const classpath = options.get("--classpath");
if (!classpath) throw new Error("--classpath is required");
if (isInside(repositoryRoot, runRoot)) {
  throw new Error("--run-root must be outside the source repository");
}
if (fs.existsSync(runRoot)) throw new Error("--run-root must not already exist");
const pty = loadPty(options.get("--node-pty"));
fs.mkdirSync(runRoot, { recursive: true });

async function scenario(name, exitInput, launcherArguments = []) {
  const directory = path.join(runRoot, name);
  fs.mkdirSync(directory, { recursive: true });
  const startedAt = Date.now();
  const outputEvents = [];
  const interactions = [];
  const screens = [];
  const screen = new VirtualScreen(120, 40);
  let output = "";
  let exit = null;
  let failure = null;
  const child = pty.api.spawn(
    "java.exe",
    [
      "-Dfile.encoding=UTF-8",
      "-Dstdout.encoding=UTF-8",
      "-Dstderr.encoding=UTF-8",
      "-cp",
      classpath,
      launcher,
      ...launcherArguments,
    ],
    {
      name: "xterm-256color",
      cols: 120,
      rows: 40,
      cwd: repositoryRoot,
      env: { ...process.env, TERM: "xterm-256color" },
    },
  );
  child.onData((data) => {
    output += data;
    outputEvents.push([(Date.now() - startedAt) / 1000, "o", data]);
    screen.feed(data);
  });
  const exitPromise = new Promise((resolve) => {
    child.onExit((event) => {
      exit = event;
      resolve(event);
    });
  });

  function capture(label) {
    screens.push({
      elapsedSeconds: (Date.now() - startedAt) / 1000,
      label: `${name}:${label}`,
      columns: screen.columns,
      rows: screen.rows,
      screen: screen.text(),
    });
  }

  async function send(text, label) {
    interactions.push({
      elapsedSeconds: (Date.now() - startedAt) / 1000,
      label,
      text: text
        .replace(/\r/g, "<ENTER>")
        .replace(/\u001b/g, "<ESC>")
        .replace(/\u0003/g, "<CTRL-C>"),
    });
    outputEvents.push([(Date.now() - startedAt) / 1000, "i", text]);
    child.write(text);
    await sleep(500);
    capture(label);
  }

  try {
    await waitFor(
      () => output.includes("HAIFA CODING AGENT") || output.includes("SPIKE_RENDER_FAILURE"),
      15_000,
      `${name} startup`,
    );
    capture("startup");
    if (name === "normal") {
      child.resize(80, 24);
      screen.resize(80, 24);
      await sleep(500);
      capture("80x24");
      await send("\u001b[200~第一行 😀 e\u0301\n第二行\u001b[201~", "unicode-paste");
      child.resize(180, 50);
      screen.resize(180, 50);
      await sleep(500);
      capture("180x50");
    }
    if (exitInput != null) await send(exitInput, "exit");
    await Promise.race([
      exitPromise,
      sleep(10_000).then(() => {
        throw new Error(`Timed out waiting for ${name} exit`);
      }),
    ]);
  } catch (error) {
    failure = error;
    try {
      child.kill();
    } catch {
      // Best-effort cleanup; preserve the original failure.
    }
    await Promise.race([exitPromise, sleep(2_000)]);
  }

  const ansiFile = path.join(directory, "terminal.ansi");
  const castFile = path.join(directory, "terminal.cast");
  const interactionFile = path.join(directory, "interaction.jsonl");
  const screensFile = path.join(directory, "screens.jsonl");
  fs.writeFileSync(ansiFile, output, "utf8");
  fs.writeFileSync(path.join(directory, "terminal.txt"), stripControls(output), "utf8");
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
    interactions.map((event) => JSON.stringify(event)).join("\n") + "\n",
    "utf8",
  );
  fs.writeFileSync(
    screensFile,
    screens.map((event) => JSON.stringify(event)).join("\n") + "\n",
    "utf8",
  );
  const requiredRegions = [
    "HAIFA CODING AGENT",
    "Diagnostics",
    "You",
    "Pending messages",
    "Status",
    "Widgets above",
    "Widgets below",
    "Footer",
  ];
  const resizeScreens = screens.filter((event) =>
    ["normal:startup", "normal:80x24", "normal:180x50"].includes(event.label),
  );
  return {
    name,
    exit,
    failure: failure?.message ?? null,
    assertions: {
      alternateScreenEntered: output.includes("\u001b[?1049h"),
      alternateScreenExited: output.includes("\u001b[?1049l"),
      runtimeActionInjected:
        name !== "normal" ||
        output.includes("simulated runtime action delivered through Program.send()"),
      unicodeVisible: name !== "normal" || output.includes("第一行 😀 e\u0301"),
      noInvalidCharacters: !output.includes("\uFFFF") && !output.includes("\uFFFD"),
      prototypeRegionsVisibleAfterResize:
        name !== "normal" ||
        (resizeScreens.length === 3 &&
          resizeScreens.every((event) =>
            requiredRegions.every((region) => event.screen.includes(region)),
          )),
      expectedFailureVisible:
        name !== "failure" || output.includes("SPIKE_RENDER_FAILURE"),
      expectedExit:
        name === "failure" ? exit != null : exit?.exitCode === 0,
    },
    artifacts: { ansi: ansiFile, cast: castFile, interaction: interactionFile, screens: screensFile },
  };
}

const results = [];
results.push(await scenario("normal", "q"));
results.push(await scenario("escape", "\u001b"));
results.push(await scenario("interrupt", "\u0003"));
results.push(await scenario("failure", null, ["--fail-after-init"]));
const assertions = Object.fromEntries(
  results.flatMap((result) =>
    Object.entries(result.assertions).map(([key, value]) => [`${result.name}.${key}`, value]),
  ),
);
const manifest = {
  schema: "haifa.tui4j-terminal-spike/1",
  completedAt: new Date().toISOString(),
  repositoryRoot,
  runRoot,
  nodePtySource: pty.source,
  launcher,
  terminalSizes: ["80x24", "120x40", "180x50"],
  scenarios: results,
  assertions,
  passed: results.every(
    (result) => result.failure == null && Object.values(result.assertions).every(Boolean),
  ),
};
fs.writeFileSync(path.join(runRoot, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n", "utf8");
process.stdout.write(JSON.stringify(manifest, null, 2) + "\n");
process.exit(manifest.passed ? 0 : 1);
