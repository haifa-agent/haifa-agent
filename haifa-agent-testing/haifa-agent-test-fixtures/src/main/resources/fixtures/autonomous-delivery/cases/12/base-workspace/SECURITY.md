# Export path security

`writeExport(root, key, content)` accepts slash-delimited logical keys.

- Permit ordinary relative segments and nested keys.
- Reject empty segments, `.`, `..`, absolute paths, backslashes, drive paths and NUL.
- Validate the complete key before creating directories or files.
- Rejection must leave the destination tree unchanged.
- Write the final file atomically so interruption cannot expose partial content.
