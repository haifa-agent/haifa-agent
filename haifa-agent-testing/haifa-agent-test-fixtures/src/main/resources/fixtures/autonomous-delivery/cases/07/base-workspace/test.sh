#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
classes="$root/build/classes"
rm -rf "$classes"
mkdir -p "$classes"
find "$root/src/main/java" -name '*.java' -print0 |
  xargs -0 javac --release 21 -d "$classes"
cat >"$root/build/VisibleTest.java" <<'JAVA'
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
JAVA
javac --release 21 -cp "$classes" -d "$classes" "$root/build/VisibleTest.java"
java -cp "$classes" VisibleTest
printf 'visible regression: PASS\n'
