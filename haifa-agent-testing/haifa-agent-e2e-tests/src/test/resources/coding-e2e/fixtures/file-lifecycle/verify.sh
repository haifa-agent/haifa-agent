#!/bin/sh
set -eu
python_command="${HAIFA_PYTHON_EXECUTABLE:-python3}"
exec "$python_command" verify.py
