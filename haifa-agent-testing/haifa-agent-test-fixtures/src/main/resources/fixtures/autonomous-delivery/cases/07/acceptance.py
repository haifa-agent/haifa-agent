#!/usr/bin/env python3

import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path


CASE_ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, cwd, timeout=90):
    return subprocess.run(
        command, cwd=cwd, capture_output=True, text=True, timeout=timeout
    )


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    failures = []
    checks["testsUnchanged"] = digest(
        CASE_ROOT / "base-workspace/test.sh"
    ) == digest(workspace / "test.sh")
    visible = run(["./test.sh"], workspace)
    checks["visibleRegression"] = visible.returncode == 0
    checks["diffCheck"] = run(["git", "diff", "--check"], workspace).returncode == 0

    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        classes = root / "classes"
        classes.mkdir()
        sources = [
            str(path)
            for path in (workspace / "src/main/java").rglob("*.java")
        ]
        compiled = run(
            ["javac", "--release", "21", "-d", str(classes), *sources],
            workspace,
        )
        harness = root / "HiddenTest.java"
        harness.write_text(
            r'''
import io.haifa.window.Event;
import io.haifa.window.WindowService;
import java.time.*;
import java.util.*;

public class HiddenTest {
  public static void main(String[] args) {
    checkZone("Asia/Shanghai", LocalDate.parse("2026-07-02"));
    checkZone("America/New_York", LocalDate.parse("2026-03-08"));
  }
  static void checkZone(String zoneName, LocalDate day) {
    ZoneId zone = ZoneId.of(zoneName);
    Instant start = day.atStartOfDay(zone).toInstant();
    Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
    var before = new Event("before", start.minusNanos(1));
    var sameB = new Event("b", start);
    var sameA = new Event("a", start);
    var last = new Event("last", end.minusNanos(1));
    var next = new Event("next", end);
    var input = new ArrayList<>(List.of(last, next, sameB, before, sameA));
    var snapshot = List.copyOf(input);
    var result = WindowService.eventsForLocalDate(input, day, zone);
    if (!result.stream().map(Event::id).toList().equals(List.of("a", "b", "last"))) {
      throw new AssertionError(zoneName + " " + result);
    }
    if (!input.equals(snapshot)) throw new AssertionError("input mutated");
    try {
      result.add(next);
      throw new AssertionError("result mutable");
    } catch (UnsupportedOperationException expected) {}
  }
}
''',
            encoding="utf-8",
        )
        hidden_compile = run(
            [
                "javac",
                "--release",
                "21",
                "-cp",
                str(classes),
                "-d",
                str(classes),
                str(harness),
            ],
            workspace,
        )
        hidden = run(
            ["java", "-Duser.timezone=Pacific/Honolulu", "-cp", str(classes), "HiddenTest"],
            workspace,
        ) if compiled.returncode == 0 and hidden_compile.returncode == 0 else None
        checks["hiddenTimezoneContract"] = hidden is not None and hidden.returncode == 0

    for name, passed in checks.items():
        if not passed:
            failures.append(name)
    print(json.dumps({"case": "07-java-timezone-issue", "passed": not failures, "checks": checks, "failures": failures}, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
