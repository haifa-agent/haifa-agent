#!/usr/bin/env python3

"""Drive one production Coding Terminal session without handling credentials."""

import json
import os
import subprocess
import sys
import time
from pathlib import Path

import pexpect


def fail(message: str, child: pexpect.spawn | None = None) -> None:
    if child is not None:
        child.close(force=True)
    print(message, file=sys.stderr)
    raise SystemExit(20)


def wait_for(child: pexpect.spawn, marker: str, label: str, timeout: int) -> None:
    try:
        child.expect_exact(marker.encode("utf-8"), timeout=timeout)
    except pexpect.TIMEOUT:
        fail(f"TIMEOUT waiting for {label}", child)
    except pexpect.EOF:
        fail(f"UNEXPECTED EOF waiting for {label}", child)


def type_and_send(child: pexpect.spawn, text: str) -> None:
    for character in text:
        child.send(character.encode("utf-8"))
        time.sleep(0.002)
    child.send(b"\r")


def main() -> int:
    if len(sys.argv) != 9:
        fail(
            "usage: run_terminal.py JAR WORKSPACE CONFIG TRACE PROMPT "
            "ACCEPTANCE RESULT_JSON TIMEOUT_SECONDS"
        )

    jar, workspace, config, trace, prompt_file, acceptance_script, result_file, timeout_value = sys.argv[1:]
    timeout = int(timeout_value)
    prompt = Path(prompt_file).read_text("utf-8").strip()
    started_at = time.time()
    child = pexpect.spawn(
        "java",
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
            f"PT{timeout}S",
            "--trace",
            "detail",
            "--trace-file",
            trace,
            "--verbose",
        ],
        env=os.environ.copy(),
        encoding=None,
        dimensions=(42, 132),
        timeout=timeout,
    )
    child.logfile_read = sys.stdout.buffer

    wait_for(child, "IDLE", "terminal startup", timeout)
    time.sleep(0.5)
    type_and_send(child, prompt)
    wait_for(child, "RUNNING", "run start", timeout)
    wait_for(child, "IDLE", "autonomous run completion", timeout)
    completed_at = time.time()

    acceptance = subprocess.run(
        [sys.executable, acceptance_script, workspace],
        capture_output=True,
        text=True,
        timeout=min(300, timeout),
        check=False,
    )

    type_and_send(child, "/quit")
    try:
        child.expect(pexpect.EOF, timeout=60)
    except pexpect.TIMEOUT:
        fail("TIMEOUT waiting for /quit", child)
    child.close()

    payload = {
        "schemaVersion": 1,
        "terminalExitStatus": child.exitstatus,
        "agentWallTimeSeconds": round(completed_at - started_at, 3),
        "acceptanceExitStatus": acceptance.returncode,
        "acceptanceStdout": acceptance.stdout.strip(),
        "acceptanceStderr": acceptance.stderr.strip(),
        "acceptancePassed": acceptance.returncode == 0,
        "interactionCount": 1,
        "humanFollowUps": 0,
    }
    Path(result_file).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print("\nBLACK_BOX_ACCEPTANCE:", "PASS" if acceptance.returncode == 0 else "FAIL")
    return 0 if acceptance.returncode == 0 else 40


if __name__ == "__main__":
    raise SystemExit(main())
