#!/usr/bin/env python3
"""Drive the packaged Coding Agent through a real Unix PTY without model calls."""

import argparse
import fcntl
import hashlib
import json
import os
import pty
import re
import select
import struct
import subprocess
import termios
import time
from pathlib import Path


ANSI = re.compile(r"\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x1b]*(?:\x1b\\|\x07)|.)")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the production Coding Terminal in a Unix PTY.")
    parser.add_argument("--run-root", required=True)
    parser.add_argument("--launcher", required=True)
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--timeout-seconds", type=int, default=30)
    return parser.parse_args()


def resize(fd: int, columns: int, rows: int) -> None:
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, columns, 0, 0))


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    path.chmod(0o600)


def main() -> int:
    options = arguments()
    repository = Path(__file__).resolve().parent.parent
    run_root = Path(options.run_root).absolute()
    launcher = Path(options.launcher).resolve()
    workspace = Path(options.workspace).resolve()
    if not run_root.is_absolute() or run_root.exists():
        raise ValueError("run root must be a new absolute directory")
    if run_root == repository or repository in run_root.parents:
        raise ValueError("run root must be outside the repository")
    if not launcher.is_file() or not os.access(launcher, os.X_OK):
        raise ValueError("launcher must be an executable file")
    if not workspace.is_dir():
        raise ValueError("workspace must be an existing directory")

    run_root.mkdir(mode=0o700, parents=False)
    master, slave = pty.openpty()
    resize(slave, 80, 24)
    environment = dict(os.environ)
    environment.update(
        {
            "DEEPSEEK_API_KEY": "linux-special-unused-placeholder",
            "TERM": "xterm-256color",
            "NO_COLOR": "",
        }
    )
    started = time.monotonic()

    def establish_controlling_terminal() -> None:
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

    process = subprocess.Popen(
        [str(launcher), "--terminal", "--workspace", str(workspace)],
        cwd=workspace,
        env=environment,
        stdin=slave,
        stdout=slave,
        stderr=slave,
        close_fds=True,
        preexec_fn=establish_controlling_terminal,
    )
    os.close(slave)
    output = bytearray()
    cast_events: list[list[object]] = []
    actions: list[dict[str, object]] = []

    def drain(wait: float) -> None:
        ready, _, _ = select.select([master], [], [], wait)
        if not ready:
            return
        try:
            chunk = os.read(master, 65536)
        except OSError:
            return
        if chunk:
            output.extend(chunk)
            cast_events.append([round(time.monotonic() - started, 6), "o", chunk.decode("utf-8", "replace")])

    def send(data: bytes, action: str) -> None:
        os.write(master, data)
        actions.append({"atMillis": round((time.monotonic() - started) * 1000), "action": action})

    deadline = started + options.timeout_seconds
    while b"Haifa Coding Agent" not in output and time.monotonic() < deadline:
        drain(0.1)
    if b"Haifa Coding Agent" not in output:
        process.terminate()
        raise RuntimeError("terminal did not render its production header")

    unicode_draft = "Linux PTY 中文 🙂".encode("utf-8")
    send(unicode_draft, "type-unicode-draft-line-1")
    send(b"\x0a", "insert-newline-with-ctrl-j")
    send("第二行".encode("utf-8"), "type-unicode-draft-line-2")
    time.sleep(0.3)
    drain(0.1)
    resize(master, 120, 40)
    cast_events.append([round(time.monotonic() - started, 6), "r", "120x40"])
    actions.append({"atMillis": round((time.monotonic() - started) * 1000), "action": "resize-120x40"})
    time.sleep(0.3)
    drain(0.1)
    send(b"\x03", "clear-draft")
    time.sleep(0.2)
    send(b"/quit\r", "quit")

    while process.poll() is None and time.monotonic() < deadline:
        drain(0.1)
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill()
        raise RuntimeError("terminal did not exit within the bounded timeout")
    for _ in range(10):
        drain(0.02)
    os.close(master)

    raw = bytes(output)
    decoded = raw.decode("utf-8", "replace")
    checks = {
        "exitCodeZero": process.returncode == 0,
        "productionHeader": "Haifa Coding Agent" in decoded,
        "unicodeDraftRendered": "Linux PTY 中文 🙂" in decoded,
        "multilineDraftRendered": "第二行" in decoded,
        "alternateScreenEntered": "\x1b[?1049h" in decoded,
        "alternateScreenExited": "\x1b[?1049l" in decoded,
        "resizeRecorded": any(event[1] == "r" and event[2] == "120x40" for event in cast_events),
        "quitSelected": "/quit" in decoded,
    }
    passed = all(checks.values())
    (run_root / "terminal.ansi").write_bytes(raw)
    (run_root / "terminal.ansi").chmod(0o600)
    (run_root / "terminal.txt").write_text(ANSI.sub("", decoded), encoding="utf-8")
    (run_root / "terminal.txt").chmod(0o600)
    with (run_root / "session.cast").open("w", encoding="utf-8") as cast:
        cast.write(json.dumps({"version": 2, "width": 80, "height": 24, "env": {"TERM": "xterm-256color"}}))
        cast.write("\n")
        for event in cast_events:
            cast.write(json.dumps(event, ensure_ascii=False))
            cast.write("\n")
    (run_root / "session.cast").chmod(0o600)
    write_json(run_root / "interaction.json", actions)
    write_json(
        run_root / "result.json",
        {
            "schemaVersion": 1,
            "driver": "unix-pty",
            "providerCalls": 0,
            "exitCode": process.returncode,
            "checks": checks,
            "passed": passed,
        },
    )
    manifest_lines = []
    for path in sorted(run_root.iterdir(), key=lambda item: item.name):
        if path.name == "manifest.sha256" or not path.is_file():
            continue
        manifest_lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}")
    (run_root / "manifest.sha256").write_text("\n".join(manifest_lines) + "\n", encoding="ascii")
    (run_root / "manifest.sha256").chmod(0o600)
    if not passed:
        raise RuntimeError("one or more Unix PTY checks failed")
    print(json.dumps({"passed": True, "checks": checks}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
