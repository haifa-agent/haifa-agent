# Cache directory contract

`cache_job.py VALUE` writes and then reads a small cache entry.

- Use the directory selected by `TMPDIR`, falling back to the platform temporary-directory API.
- Create only the required private cache directory.
- Reject an empty value with a non-zero exit.
- Do not depend on a writable `/tmp`.
- Preserve deterministic stdout and leave source files unchanged during execution.
