#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "..");

function parseArguments(values) {
  const result = new Map();
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index];
    const value = values[index + 1];
    if (!key?.startsWith("--") || value == null) {
      throw new Error(
        "Usage: node scripts/terminal-ui-phase-c-quality.mjs " +
          "--run-root <new-absolute-path-outside-repository> [--timeout-seconds <seconds>]",
      );
    }
    result.set(key, value);
  }
  return result;
}

function requireRunRoot(value) {
  if (!value || !path.isAbsolute(value)) {
    throw new Error("--run-root must be an absolute path");
  }
  const candidate = path.resolve(value);
  const relative = path.relative(repositoryRoot, candidate);
  if (relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative))) {
    throw new Error("--run-root must be outside the source repository");
  }
  if (fs.existsSync(candidate)) {
    throw new Error("--run-root must not already exist");
  }
  return candidate;
}

function writeJson(file, value) {
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function mavenInvocation(argumentsList) {
  if (process.platform === "win32") {
    return {
      command: "cmd.exe",
      arguments: ["/d", "/s", "/c", path.join(repositoryRoot, "mvnw.cmd"), ...argumentsList],
    };
  }
  return { command: path.join(repositoryRoot, "mvnw"), arguments: argumentsList };
}

const argumentsMap = parseArguments(process.argv.slice(2));
const runRoot = requireRunRoot(argumentsMap.get("--run-root"));
const timeoutSeconds = Number.parseInt(argumentsMap.get("--timeout-seconds") ?? "600", 10);
if (!Number.isInteger(timeoutSeconds) || timeoutSeconds < 30 || timeoutSeconds > 1800) {
  throw new Error("--timeout-seconds must be an integer from 30 through 1800");
}

fs.mkdirSync(runRoot, { recursive: false });
const startedAt = new Date();
const mavenArguments = [
  "--batch-mode",
  "--no-transfer-progress",
  "-pl",
  ":haifa-agent-coding-terminal,:haifa-agent-cli",
  "-am",
  "-Dtest=TerminalTextCursorTest,Tui4jCodingTerminalModelTest,Tui4jTerminalIoTest,Tui4jTerminalThemeTest,Tui4jTerminalViewTest,Tui4jTerminalSpikeTest,LocalCodingProductAssemblyTest",
  "-Dsurefire.failIfNoSpecifiedTests=false",
  "test",
];
const invocation = mavenInvocation(mavenArguments);
const result = spawnSync(invocation.command, invocation.arguments, {
  cwd: repositoryRoot,
  encoding: "utf8",
  timeout: timeoutSeconds * 1000,
  maxBuffer: 16 * 1024 * 1024,
});
const output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
fs.writeFileSync(path.join(runRoot, "maven.log"), output, "utf8");

const timedOut = result.error?.code === "ETIMEDOUT";
const exitCode = timedOut ? 124 : result.status ?? 1;
const platformName =
  process.platform === "win32" ? "Windows" : process.platform === "darwin" ? "macOS" : "Linux";
const automatedStatus = exitCode === 0 ? "PASS" : "FAIL";
const manifest = {
  schemaVersion: 1,
  startedAt: startedAt.toISOString(),
  finishedAt: new Date().toISOString(),
  platform: platformName,
  architecture: process.arch,
  timeoutSeconds,
  exitCode,
  timedOut,
  command: [invocation.command, ...invocation.arguments],
  automated: {
    resizeStatePreservation: automatedStatus,
    colorProfiles: automatedStatus,
    unicodeAndCellWidth: automatedStatus,
    modifiedEnterAndKeyRouting: automatedStatus,
    nonTtyFailClosed: automatedStatus,
    programLifecycle: automatedStatus,
  },
  actualTerminal: {
    macOS: "NOT_RUN",
    Linux: "NOT_RUN",
    Windows: "NOT_RUN",
    WSL: "NOT_RUN",
    reason:
      "This deterministic gate does not claim a real PTY/ConPTY result. Record each real terminal run separately.",
  },
  windowsDynamicResize: {
    status: "SKIPPED_AFTER_3_ATTEMPTS",
    reason:
      "Historical tui4j 0.3.3 ConPTY evidence exhausted the allowed three attempts; no fourth attempt is automated.",
  },
  log: "maven.log",
};
writeJson(path.join(runRoot, "manifest.json"), manifest);

process.stdout.write(`${JSON.stringify(manifest, null, 2)}\n`);
process.exit(exitCode);
