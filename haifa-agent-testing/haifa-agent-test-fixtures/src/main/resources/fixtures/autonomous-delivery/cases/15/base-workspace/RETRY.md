# Transient probe

Run `python3 probe.py` with the same process-provided `TMPDIR`.
The first attempt returns code 75 after recording a transient marker.
The second attempt consumes the marker, succeeds, and removes it.
This is the expected behavior and requires no source change.
