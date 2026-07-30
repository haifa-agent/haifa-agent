#!/usr/bin/env python3

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


CASE_ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, cwd, timeout=120):
    environment = os.environ.copy()
    environment["GOCACHE"] = str(cwd / ".acceptance-cache")
    environment["GOPATH"] = str(cwd / ".acceptance-go")
    return subprocess.run(
        command,
        cwd=cwd,
        env=environment,
        capture_output=True,
        text=True,
        timeout=timeout,
    )


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    failures = []
    checks["testsUnchanged"] = digest(
        CASE_ROOT / "base-workspace/ledger/ledger_test.go"
    ) == digest(workspace / "ledger/ledger_test.go")
    checks["visibleTests"] = run(["go", "test", "./..."], workspace).returncode == 0
    checks["diffCheck"] = run(["git", "diff", "--check"], workspace).returncode == 0

    with tempfile.TemporaryDirectory() as temporary:
        copy = Path(temporary) / "repo"
        shutil.copytree(
            workspace,
            copy,
            ignore=shutil.ignore_patterns(".git", ".cache", ".acceptance-*"),
        )
        hidden = copy / "ledger/hidden_race_test.go"
        hidden.write_text(
            r'''package ledger

import (
	"sync"
	"testing"
)

func TestHiddenConcurrentCreditsAndReads(t *testing.T) {
	initial := map[string]int64{"cash": 0}
	book := New(initial)
	initial["cash"] = 999
	const workers = 80
	const rounds = 50
	var wait sync.WaitGroup
	for worker := 0; worker < workers; worker++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			for round := 0; round < rounds; round++ {
				if err := book.ApplyBatch([]Entry{{Account: "cash", Delta: 1}}); err != nil {
					t.Errorf("credit failed: %v", err)
					return
				}
				_ = book.Balance("cash")
			}
		}()
	}
	wait.Wait()
	if got, want := book.Balance("cash"), int64(workers*rounds); got != want {
		t.Fatalf("balance=%d want=%d", got, want)
	}
}

func TestHiddenAtomicRejection(t *testing.T) {
	book := New(map[string]int64{"a": 100, "b": 5})
	entries := []Entry{{Account: "a", Delta: -80}, {Account: "a", Delta: -30}, {Account: "b", Delta: 10}}
	if err := book.ApplyBatch(entries); err == nil {
		t.Fatal("expected rejection")
	}
	if book.Balance("a") != 100 || book.Balance("b") != 5 {
		t.Fatalf("partial mutation a=%d b=%d", book.Balance("a"), book.Balance("b"))
	}
	if entries[0].Delta != -80 {
		t.Fatal("input mutated")
	}
}

func TestHiddenEmptyAccountRejectsWholeBatch(t *testing.T) {
	book := New(map[string]int64{"a": 10})
	if err := book.ApplyBatch([]Entry{{Account: "a", Delta: 5}, {Account: "", Delta: 1}}); err == nil {
		t.Fatal("expected rejection")
	}
	if book.Balance("a") != 10 {
		t.Fatal("partial update")
	}
}
''',
            encoding="utf-8",
        )
        race = run(["go", "test", "-race", "./..."], copy, timeout=180)
        checks["hiddenRaceAndAtomicity"] = race.returncode == 0

    for name, passed in checks.items():
        if not passed:
            failures.append(name)
    print(json.dumps({"case": "10-go-concurrency", "passed": not failures, "checks": checks, "failures": failures}, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
