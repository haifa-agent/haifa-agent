#!/usr/bin/env python3
"""Reject commits whose identity or attribution trailer matches the deny list.

The gate exists so that agent-authored commits cannot enter `dev` or `main` under a
robot identity. It is a hygiene gate, not a security boundary: Git author and
committer fields are supplied by the client and can be rewritten before pushing.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from typing import Sequence

DENY_TOKENS = ("cursoragent", "cursor-agent", "cursor[bot]", "copilot", "claude")

TRAILER_PATTERN = re.compile(
    r"^(?:co-authored-by|co-developed-by|authored-by)\s*:\s*(?P<value>.+)$",
    re.IGNORECASE | re.MULTILINE,
)

FIELD_SEPARATOR = "\x00"
RECORD_SEPARATOR = "\x1e"
# The separators reach Git as the `%x00`/`%x1e` escapes instead of literal bytes:
# Windows cannot pass a NUL character through a process argument list.
GIT_FORMAT = "%H%x00%an%x00%ae%x00%cn%x00%ce%x00%B%x1e"
RECORD_FIELD_COUNT = 6


class CommitLogError(RuntimeError):
    """Raised when the requested revision range cannot be read from Git."""


@dataclass(frozen=True)
class Commit:
    sha: str
    author_name: str
    author_email: str
    committer_name: str
    committer_email: str
    body: str

    def identities(self) -> tuple[tuple[str, str], ...]:
        """Return every (field, value) pair that can attribute this commit to somebody."""
        trailers = tuple(
            ("trailer", match.group("value")) for match in TRAILER_PATTERN.finditer(self.body)
        )
        return (
            ("author name", self.author_name),
            ("author email", self.author_email),
            ("committer name", self.committer_name),
            ("committer email", self.committer_email),
            *trailers,
        )


@dataclass(frozen=True)
class Violation:
    sha: str
    field: str
    value: str
    token: str


def parse_log_output(output: str) -> list[Commit]:
    """Parse `git log --format=GIT_FORMAT` output into commits."""
    commits: list[Commit] = []
    for record in output.split(RECORD_SEPARATOR):
        record = record.strip("\r\n")
        if not record:
            continue
        parts = record.split(FIELD_SEPARATOR)
        if len(parts) != RECORD_FIELD_COUNT:
            raise CommitLogError(f"malformed git log record: {record[:80]!r}")
        commits.append(Commit(*parts))
    return commits


def find_violations(commits: Sequence[Commit], deny_tokens: Sequence[str]) -> list[Violation]:
    tokens = tuple(token.lower() for token in deny_tokens)
    violations: list[Violation] = []
    for commit in commits:
        for field, value in commit.identities():
            lowered = value.lower()
            for token in tokens:
                if token in lowered:
                    violations.append(Violation(commit.sha, field, value.strip(), token))
    return violations


def read_commits(revision_range: str, cwd: str | None = None) -> list[Commit]:
    """Read the non-merge commits in `revision_range`.

    Merge commits are excluded because GitHub synthesizes pull request and merge
    group merge commits whose identity belongs to GitHub, not to the contributor.
    """
    try:
        completed = subprocess.run(
            ["git", "log", "--no-merges", f"--format={GIT_FORMAT}", revision_range],
            cwd=cwd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
    except OSError as error:
        raise CommitLogError(f"cannot run git: {error}") from error
    if completed.returncode != 0:
        detail = completed.stderr.strip() or f"exit code {completed.returncode}"
        raise CommitLogError(f"git log {revision_range!r} failed: {detail}")
    return parse_log_output(completed.stdout)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Reject commits whose author, committer, or attribution trailer matches the deny list."
    )
    parser.add_argument(
        "--range",
        required=True,
        dest="revision_range",
        help="Git revision range to inspect, for example origin/dev..HEAD",
    )
    parser.add_argument(
        "--deny-token",
        action="append",
        default=[],
        help="additional case-insensitive deny token; repeatable",
    )
    args = parser.parse_args(argv)

    try:
        commits = read_commits(args.revision_range)
    except CommitLogError as error:
        print(f"::error::{error}", file=sys.stderr)
        print(f"FAIL: {error}", file=sys.stderr)
        return 2

    violations = find_violations(commits, (*DENY_TOKENS, *args.deny_token))
    print(f"checked {len(commits)} commit(s) in {args.revision_range}")
    if not violations:
        print("commit authorship gate passed")
        return 0

    for violation in violations:
        print(
            f"::error::{violation.sha[:12]} {violation.field}='{violation.value}' "
            f"matches deny token '{violation.token}'"
        )
    print(
        "FAIL: forbidden commit identity. Rewrite the author, committer, or attribution "
        "trailer, then force-push the branch.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
