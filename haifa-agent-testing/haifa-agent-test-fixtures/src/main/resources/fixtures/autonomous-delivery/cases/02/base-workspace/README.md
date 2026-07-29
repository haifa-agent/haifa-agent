# Task Board CLI

The application stores tasks in a local file and is invoked as:

```bash
./task-board DB_FILE COMMAND [OPTIONS]
```

`task-board` compiles the Java sources when needed and runs
`io.haifa.board.Main`.

## Existing commands

```text
add DB_FILE TITLE PRIORITY DUE
done DB_FILE ID
list DB_FILE
```

- `PRIORITY` accepts `low`, `medium`, or `high` case-insensitively.
- `DUE` is an ISO date or `-` for no due date.
- `add` prints the allocated positive integer ID.
- `done` prints `done ID`; an unknown ID is an operational failure.
- `list` prints one task per line as
  `ID<TAB>STATUS<TAB>PRIORITY<TAB>DUE<TAB>TITLE`, ordered by ID.
- Data must survive separate process invocations.

## Next release contract

`list` additionally accepts optional `--status OPEN|DONE` and
`--priority LOW|MEDIUM|HIGH` filters in either order. Matching remains
case-insensitive and output ordering does not change.

The new command:

```text
export DB_FILE --format json
```

prints a JSON array ordered by ID. Each object has exactly these keys in this
order: `id`, `title`, `priority`, `due`, `status`. `id` is a JSON number and
all other values are strings. JSON escaping must handle quotes, backslashes,
tabs, newlines, and non-ASCII titles.

Malformed command syntax, IDs, priorities, dates, status values, and formats
print a concise diagnostic to stderr and exit with status 2. Operational
failures such as an unknown task ID exit with status 1. No Java stack trace is
part of normal CLI errors.

Run the repository regression suite with:

```bash
./test.sh
```
