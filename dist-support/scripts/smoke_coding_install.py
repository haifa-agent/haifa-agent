#!/usr/bin/env python3
"""Run the release installer over HTTP with an empty user directory and no Java on PATH."""

import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from threading import Thread

from coding_installers import RELEASE_URL
from smoke_distributions import checksummed_archive


class ReleaseHandler(SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        pass


def verify_linux(url, bad_url, home, environment):
    environment.update(HOME=str(home), PATH="/usr/bin:/bin", HAIFA_CODING_AUTH_STORE="file")
    # Ubuntu normally has a system Java on CI runners. Expose just the OS tools
    # used by the installer and launcher, then confirm all checks use bundled Java.
    tools = home / "tools"
    tools.mkdir()
    for name in ("uname", "getconf", "mkdir", "mktemp", "rm", "curl", "sha256sum", "tar",
                 "gzip", "mv", "grep", "cat", "dirname", "readlink", "groups"):
        (tools / name).symlink_to(shutil.which(name, path=environment["PATH"]))
    environment["PATH"] = str(tools)

    def shell(command, *arguments, check=True):
        return subprocess.run(
            ["/bin/sh", "-ec", command, "sh", *arguments], cwd=home, env=environment, check=check, timeout=180
        )

    install = """
installer=$(mktemp)
trap 'rm -f "$installer"' EXIT
curl -fLSs "$1" -o "$installer"
/bin/sh "$installer"
export PATH="$HOME/.local/bin:$PATH"
haifa --help >/dev/null
haifa auth status --store file >/dev/null
"""
    shell(install, url)
    state = home / "data" / "preserve-me"
    state.write_text("existing data", encoding="utf-8")
    shell(install, url)
    assert shell(install, bad_url, check=False).returncode != 0, "Bad archive installed"
    subprocess.run(
        ["/bin/bash", "--noprofile", "-ic", "haifa --help >/dev/null"],
        cwd=home, env=environment, check=True, timeout=60,
    )
    assert state.read_text(encoding="utf-8") == "existing data"
    assert (home / ".bashrc").read_text(encoding="utf-8").count("# Haifa Agent PATH") == 1


def verify_windows(url, bad_url, home, environment):
    import ctypes
    import winreg

    system = Path(environment["SYSTEMROOT"]) / "System32"
    powershell = system / "WindowsPowerShell" / "v1.0" / "powershell.exe"
    environment.update(USERPROFILE=str(home), LOCALAPPDATA=str(home / "Local"), Path=str(system),
                       COMSPEC=str(system / "cmd.exe"), PATHEXT=".COM;.EXE;.BAT;.CMD")
    environment.pop("PATH", None)
    environment.pop("PSMODULEPATH", None)

    def shell(command, check=True, **variables):
        return subprocess.run(
            [str(powershell), "-NoProfile", "-NonInteractive", "-Command", "$ErrorActionPreference='Stop'; " + command],
            cwd=home, env={**environment, **variables}, check=check, timeout=180,
        )

    install = """
& ([scriptblock]::Create([Text.Encoding]::UTF8.GetString((Invoke-WebRequest -UseBasicParsing $env:HAIFA_TEST_URL).RawContentStream.ToArray())))
haifa --help | Out-Null
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
haifa auth status | Out-Null
exit $LASTEXITCODE
"""
    with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, "Environment", 0, winreg.KEY_READ | winreg.KEY_WRITE) as key:
        try:
            previous = winreg.QueryValueEx(key, "Path")
        except FileNotFoundError:
            previous = None
        try:
            shell(install, HAIFA_TEST_URL=url)
            state = home / "data" / "preserve-me"
            state.write_text("existing data", encoding="utf-8")
            shell(install, HAIFA_TEST_URL=url)
            assert shell(install, check=False, HAIFA_TEST_URL=bad_url).returncode != 0, "Bad archive installed"
            shell("""
$bin = Join-Path $env:LOCALAPPDATA 'HaifaAgent/bin'
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User') -split ';'
if (@($userPath | Where-Object { $_ -eq $bin }).Count -ne 1) { throw 'Expected one persistent PATH entry.' }
$env:Path = ($userPath -join ';') + ';' + $env:Path
haifa --help | Out-Null
exit $LASTEXITCODE
""")
            assert state.read_text(encoding="utf-8") == "existing data"
        finally:
            if previous is not None:
                winreg.SetValueEx(key, "Path", 0, previous[1], previous[0])
            else:
                try:
                    winreg.DeleteValue(key, "Path")
                except FileNotFoundError:
                    pass
            ctypes.windll.user32.SendMessageTimeoutW(
                ctypes.c_void_p(0xFFFF), 0x1A, ctypes.c_size_t(0), ctypes.c_wchar_p("Environment"), 2, 200, None
            )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path("dist-support/output"))
    args = parser.parse_args()
    output = args.output_dir.resolve()
    checksums = {
        name: digest
        for digest, name in (line.split() for line in (output / "sha256sums.txt").read_text(encoding="utf-8").splitlines())
    }
    windows = os.name == "nt"
    platform = "windows-x64" if windows else "linux-x64"
    extension = "zip" if windows else "tar.gz"
    installer = "install-haifa.ps1" if windows else "install-haifa.sh"
    archive = checksummed_archive(output, checksums, f"haifa-coding-agent-{platform}-*.{extension}")
    tag = archive.name.removeprefix(f"haifa-coding-agent-{platform}-").removesuffix(f".{extension}")
    script = (output / installer).read_text(encoding="utf-8")
    if script.count(f"{RELEASE_URL}/{tag}/{archive.name}") != 1:
        raise RuntimeError("Installer must download the package from its own release tag")

    with tempfile.TemporaryDirectory(prefix="haifa install ") as temporary:
        root = Path(temporary)
        home = root / "empty home"
        home.mkdir()
        release = root / "release"
        payload = release / "ok" / tag
        payload.mkdir(parents=True)
        shutil.copyfile(archive, payload / archive.name)
        bad = release / "bad" / tag
        bad.mkdir(parents=True)
        (bad / archive.name).write_bytes(b"truncated archive")
        with ThreadingHTTPServer(("127.0.0.1", 0), partial(ReleaseHandler, directory=str(release))) as server:
            thread = Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                (payload / installer).write_text(script.replace(RELEASE_URL, base + "/ok"), encoding="utf-8")
                (bad / installer).write_text(script.replace(RELEASE_URL, base + "/bad"), encoding="utf-8")
                environment = {key: value for key, value in os.environ.items() if not key.upper().startswith(
                    ("HAIFA_", "JAVA_", "JDK_", "_JAVA_", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")
                )}
                environment["HAIFA_DATA_ROOT"] = str(home / "data")
                verify = verify_windows if windows else verify_linux
                verify(f"{base}/ok/{tag}/{installer}", f"{base}/bad/{tag}/{installer}", home, environment)
            finally:
                server.shutdown()
                thread.join()
    print(f"PASS: {platform} clean install, launch, auth status, reinstall and checksum failure")


if __name__ == "__main__":
    main()
