"""Render the native bootstrap for a Coding Agent archive."""

from pathlib import Path
import re

from distribution_contracts import sha256_file


TEMPLATES = Path(__file__).resolve().parents[1] / "templates" / "installers"
RELEASE_URL = "https://github.com/haifa-agent/haifa-agent/releases/download"


def render_installer(archive: Path, output: Path) -> Path:
    match = re.fullmatch(
        r"haifa-coding-agent-(windows-x64|linux-x64)-(v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?)(\.zip|\.tar\.gz)",
        archive.name,
    )
    if not match:
        raise ValueError(f"Invalid Coding Agent archive name: {archive.name}")
    platform, tag, extension = match.groups()
    if (platform == "windows-x64") != (extension == ".zip"):
        raise ValueError(f"Wrong archive format for {platform}: {archive.name}")
    installer = "install-haifa.ps1" if platform == "windows-x64" else "install-haifa.sh"
    digest = sha256_file(archive)
    tokens = {
        "TAG": tag,
        "ARCHIVE": archive.name,
        "DOWNLOAD": f"{RELEASE_URL}/{tag}/{archive.name}",
        "SHA256": digest,
        "PACKAGE_ID": f"{tag}-{digest[:16]}",
    }
    content = (TEMPLATES / installer).read_text(encoding="utf-8")
    for key, value in tokens.items():
        content = content.replace(f"@{key}@", value)
    path = output / installer
    path.write_text(content, encoding="utf-8", newline="\n")
    return path
