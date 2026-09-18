import shutil
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parent
CLASSES = ROOT / "build" / "classes"


def run(arguments):
    return subprocess.run(
        arguments,
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
    )


def main():
    shutil.rmtree(CLASSES, ignore_errors=True)
    CLASSES.mkdir(parents=True)
    sources = sorted(str(path) for path in (ROOT / "src/main/java").rglob("*.java"))
    compiled = run(["javac", "--release", "21", "-d", str(CLASSES), *sources])
    if compiled.returncode != 0:
        raise AssertionError(compiled.stderr)
    harness = ROOT / "build" / "VisibleTest.java"
    harness.write_text(
        """
import io.haifa.window.Event;
import io.haifa.window.WindowService;
import java.time.*;
import java.util.*;

public class VisibleTest {
  public static void main(String[] args) {
    var events = List.of(
      new Event("a", Instant.parse("2026-07-01T00:00:00Z")),
      new Event("b", Instant.parse("2026-07-01T23:59:59Z")),
      new Event("c", Instant.parse("2026-07-02T00:00:00Z")));
    var result = WindowService.eventsForLocalDate(
      events, LocalDate.parse("2026-07-01"), ZoneId.of("UTC"));
    if (!result.stream().map(Event::id).toList().equals(List.of("a", "b"))) {
      throw new AssertionError(result);
    }
  }
}
""".lstrip(),
        encoding="utf-8",
    )
    visible_compile = run(
        ["javac", "--release", "21", "-cp", str(CLASSES), "-d", str(CLASSES), str(harness)]
    )
    if visible_compile.returncode != 0:
        raise AssertionError(visible_compile.stderr)
    visible = run(["java", "-cp", str(CLASSES), "VisibleTest"])
    if visible.returncode != 0:
        raise AssertionError(visible.stderr)
    print("visible regression: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
