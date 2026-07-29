#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
scratch="$root/build/regression"
db="$scratch/tasks.tsv"
rm -rf "$scratch"
mkdir -p "$scratch"

test "$("$root/task-board" "$db" add "Write docs" high 2026-08-01)" = "1"
test "$("$root/task-board" "$db" add "Release build" medium -)" = "2"
test "$("$root/task-board" "$db" add "Fix blocker" HIGH 2026-07-30)" = "3"

expected_open=$(printf '1\tOPEN\tHIGH\t2026-08-01\tWrite docs\n2\tOPEN\tMEDIUM\t-\tRelease build\n3\tOPEN\tHIGH\t2026-07-30\tFix blocker')
test "$("$root/task-board" "$db" list)" = "$expected_open"

test "$("$root/task-board" "$db" done 2)" = "done 2"
expected_high=$(printf '1\tOPEN\tHIGH\t2026-08-01\tWrite docs\n3\tOPEN\tHIGH\t2026-07-30\tFix blocker')
test "$("$root/task-board" "$db" list --priority high --status open)" = "$expected_high"

json=$("$root/task-board" "$db" export --format json)
python3 -c 'import json,sys; data=json.loads(sys.argv[1]); assert [row["id"] for row in data] == [1,2,3]; assert data[1]["status"] == "DONE"' "$json"

if "$root/task-board" "$db" list --status missing >/dev/null 2>&1; then
  exit 1
fi

rm -rf "$scratch"
printf 'Task Board regression: PASS\n'
