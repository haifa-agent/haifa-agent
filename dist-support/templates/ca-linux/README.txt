Haifa Coding Agent - Linux x86_64
================================

Includes a Linux Java 21 runtime. A separate Java installation is not needed.
Requires a glibc-based x86_64 Linux system compatible with the JDK used to build
the bundled runtime. Ubuntu 24.04 under WSL2 has been verified; older Linux
distributions and musl-based distributions are not verified.

Quick start
-----------
Extract the .tar.gz archive with tar -xzf, then run from the package directory:

  ./bin/haifa --help
  ./bin/haifa --terminal --workspace /absolute/path/to/project

Use a UTF-8 terminal. First run opens the model connection picker; use /connect
to configure model authentication later. The package includes the public
haifa-coding.yaml template without user credentials.

Configuration and durable state
-------------------------------
The launcher always loads the bundled haifa-coding.yaml. Default data and logs
live under $HOME/.haifa-agent/coding/data and $HOME/.haifa-agent/coding/logs,
outside the installation directory. Override HAIFA_DATA_ROOT to change that root,
or set HAIFA_SQLITE_DATABASE_PATH, HAIFA_TRANSCRIPT_ROOT and HAIFA_LOG_DIR
individually. New files are created with a private umask (077).

To upgrade, extract the new package into another directory and launch it using
the same data root. Keep a backup of durable state before upgrading.

Project: https://github.com/haifa-agent/haifa-agent
