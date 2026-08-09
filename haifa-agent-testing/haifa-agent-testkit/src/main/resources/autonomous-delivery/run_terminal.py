#!/usr/bin/env python3

"""Drive one production Coding Terminal session without handling credentials."""

import json
import hashlib
import os
import re
import subprocess
import sys
import time
from pathlib import Path

import pexpect

DRIVER_PROTOCOL_VERSION = "1.2.0"
RUN_TERMINAL_STATES = ("IDLE", "COMPLETED", "FAILED", "CANCELLED", "TIMEOUT")
RECORDING_COLUMNS = 132
RECORDING_ROWS = 42
MAX_RECORDED_OUTPUT_BYTES = 1024 * 1024
STATUS_ROW_SEQUENCE = f"\x1b[{RECORDING_ROWS - 6};1H".encode("ascii")
ANSI_CSI_PATTERN = rb"\x1b\[[0-?]*[ -/]*[@-~]"


def fail(message: str, child: pexpect.spawn | None = None) -> None:
    if child is not None:
        child.close(force=True)
    print(message, file=sys.stderr)
    raise SystemExit(20)


def status_pattern(marker: str) -> re.Pattern[bytes]:
    return re.compile(
        re.escape(STATUS_ROW_SEQUENCE)
        + rb"(?:[ \t\r]|"
        + ANSI_CSI_PATTERN
        + rb")*"
        + re.escape(marker.encode("utf-8"))
        + rb"(?=[ \t\r\n]|\x1b)"
    )


def wait_for(child: pexpect.spawn, marker: str, label: str, timeout: int) -> None:
    try:
        child.expect(status_pattern(marker), timeout=timeout)
    except pexpect.TIMEOUT:
        fail(f"TIMEOUT waiting for {label}", child)
    except pexpect.EOF:
        fail(f"UNEXPECTED EOF waiting for {label}", child)


def wait_for_any(child: pexpect.spawn, markers: tuple[str, ...], label: str, timeout: int) -> str:
    try:
        index = child.expect([status_pattern(marker) for marker in markers], timeout=timeout)
        return markers[index]
    except pexpect.TIMEOUT:
        fail(f"TIMEOUT waiting for {label}", child)
    except pexpect.EOF:
        fail(f"UNEXPECTED EOF waiting for {label}", child)


def type_and_send(child: pexpect.spawn, text: str) -> None:
    for character in text:
        child.send(character.encode("utf-8"))
        time.sleep(0.002)
    child.send(b"\r")


class AsciicastRecorder:
    def __init__(self) -> None:
        self.started_wall = int(time.time())
        self.started_monotonic = time.monotonic()
        self.events: list[list[object]] = []
        self.output_bytes = 0
        self.truncated = False

    def write(self, data: bytes) -> None:
        sys.stdout.buffer.write(data)
        remaining = MAX_RECORDED_OUTPUT_BYTES - self.output_bytes
        if remaining <= 0:
            self.truncated = True
            return
        text = data.decode("utf-8", errors="replace")
        encoded = text.encode("utf-8")
        if len(encoded) > remaining:
            text = encoded[:remaining].decode("utf-8", errors="ignore")
            encoded = text.encode("utf-8")
            self.truncated = True
        if text:
            elapsed = round(time.monotonic() - self.started_monotonic, 6)
            self.events.append([elapsed, "o", text])
            self.output_bytes += len(encoded)

    def flush(self) -> None:
        sys.stdout.buffer.flush()

    def elapsed_seconds(self) -> float:
        return round(time.monotonic() - self.started_monotonic, 6)

    def finalize(self, recording_file: str) -> dict[str, object]:
        path = Path(recording_file)
        header = {
            "version": 2,
            "width": RECORDING_COLUMNS,
            "height": RECORDING_ROWS,
            "timestamp": self.started_wall,
            "env": {"TERM": "xterm-256color"},
        }
        with path.open("w", encoding="utf-8", newline="\n") as output:
            output.write(json.dumps(header, ensure_ascii=False, separators=(",", ":")) + "\n")
            for event in self.events:
                output.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
        content = path.read_bytes()
        return {
            "format": "asciicast-v2",
            "path": path.name,
            "ansiMode": "preserved",
            "sha256": hashlib.sha256(content).hexdigest(),
            "bytes": len(content),
            "events": len(self.events),
            "truncated": self.truncated,
            "columns": RECORDING_COLUMNS,
            "rows": RECORDING_ROWS,
            "encoding": "UTF-8",
        }


def main() -> int:
    if len(sys.argv) != 10:
        fail(
            "usage: run_terminal.py JAR WORKSPACE CONFIG TRACE PROMPT "
            "ACCEPTANCE RECORDING RESULT_JSON TIMEOUT_SECONDS"
        )

    (
        jar,
        workspace,
        config,
        trace,
        prompt_file,
        acceptance_script,
        recording_file,
        result_file,
        timeout_value,
    ) = sys.argv[1:]
    timeout = int(timeout_value)
    prompt = Path(prompt_file).read_text("utf-8").strip()
    started_at = time.time()
    recorder = AsciicastRecorder()
    terminal_states: list[dict[str, object]] = []
    input_timeline: list[dict[str, object]] = []
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
        dimensions=(RECORDING_ROWS, RECORDING_COLUMNS),
        timeout=timeout,
    )
    child.logfile_read = recorder

    wait_for(child, "IDLE", "terminal startup", timeout)
    terminal_states.append({"state": "IDLE", "atSeconds": recorder.elapsed_seconds()})
    time.sleep(0.5)
    input_timeline.append(
        {
            "action": "objective",
            "atSeconds": recorder.elapsed_seconds(),
            "characters": len(prompt),
        }
    )
    type_and_send(child, prompt)
    wait_for(child, "RUNNING", "run start", timeout)
    terminal_states.append({"state": "RUNNING", "atSeconds": recorder.elapsed_seconds()})
    terminal_state = wait_for_any(child, RUN_TERMINAL_STATES, "autonomous run completion", timeout)
    terminal_states.append({"state": terminal_state, "atSeconds": recorder.elapsed_seconds()})
    completed_at = time.time()

    acceptance = subprocess.run(
        [sys.executable, acceptance_script, workspace],
        capture_output=True,
        text=True,
        timeout=min(300, timeout),
        check=False,
    )

    input_timeline.append(
        {
            "action": "quit",
            "atSeconds": recorder.elapsed_seconds(),
            "characters": len("/quit"),
        }
    )
    type_and_send(child, "/quit")
    try:
        child.expect(pexpect.EOF, timeout=60)
    except pexpect.TIMEOUT:
        fail("TIMEOUT waiting for /quit", child)
    child.close()
    recording = recorder.finalize(recording_file)

    payload = {
        "schemaVersion": 2,
        "driverProtocolVersion": DRIVER_PROTOCOL_VERSION,
        "terminalBackend": "unix-pty",
        "terminalExitStatus": child.exitstatus,
        "agentWallTimeSeconds": round(completed_at - started_at, 3),
        "acceptanceExitStatus": acceptance.returncode,
        "acceptanceStdout": acceptance.stdout.strip(),
        "acceptanceStderr": acceptance.stderr.strip(),
        "acceptancePassed": acceptance.returncode == 0,
        "interactionCount": 1,
        "humanFollowUps": 0,
        "terminalStates": terminal_states,
        "inputTimeline": input_timeline,
        "recording": recording,
    }
    Path(result_file).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print("\nBLACK_BOX_ACCEPTANCE:", "PASS" if acceptance.returncode == 0 else "FAIL")
    return 0 if acceptance.returncode == 0 else 40


if __name__ == "__main__":
    raise SystemExit(main())
