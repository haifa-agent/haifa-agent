# Security report: archive extraction escapes destination

The public API `safezip.extract_archive(archive, destination)` and CLI
`python3 -m safezip ARCHIVE DESTINATION` currently trust ZIP member names.
An uploaded archive can write outside the requested destination.

The repair must:

- accept ordinary files, explicit directories, UTF-8 names and nested paths;
- reject absolute POSIX paths, drive/UNC paths, `..` traversal after portable
  slash normalization, NUL names, symbolic links and other special files;
- treat both `/` and `\` as separators when validating untrusted names;
- validate the complete archive before creating or replacing extracted files;
- on rejection, return a concise CLI error with non-zero status and leave no
  new partial output;
- never overwrite a pre-existing file through an unsafe archive;
- use the Python standard library and preserve the public API.

Equivalent encodings and mixed separators are in scope. The destination itself
may already exist.
