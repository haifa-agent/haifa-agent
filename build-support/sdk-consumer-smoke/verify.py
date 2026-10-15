#!/usr/bin/env python3
"""Verify standalone consumers against a local publication or an unpacked SDK repository."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from xml.sax.saxutils import escape


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path)
    parser.add_argument("--version", default="0.1.3-SNAPSHOT")
    args = parser.parse_args()
    directory = Path(__file__).resolve().parent
    root = directory.parent.parent
    maven = str(root / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
    gradle = shutil.which("gradle")
    if gradle is None:
        raise SystemExit("A current Gradle installation is required for the SDK consumer smoke.")
    target = directory / "target"
    target.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bundle-smoke-", dir=target) as temporary:
        extra = []
        gradle_extra = [f"-PhaifaVersion={args.version}"]
        if args.repository:
            repository = args.repository.resolve()
            if not (repository / "io" / "haifa" / "haifa-agent-bom" / args.version).is_dir():
                raise SystemExit("SDK bundle repository does not contain the requested BOM version.")
            uri = repository.as_uri()
            settings = Path(temporary) / "settings.xml"
            settings.write_text(
                '<settings><profiles><profile><id>bundle</id><repositories><repository>'
                '<id>haifa-bundle</id><url>' + escape(uri) + '</url></repository></repositories>'
                '</profile></profiles><activeProfiles><activeProfile>bundle</activeProfile>'
                '</activeProfiles></settings>', encoding="utf-8"
            )
            # An empty local repository proves no previously installed Haifa artifact fills a bundle gap.
            extra = ["-s", str(settings), f"-Dmaven.repo.local={Path(temporary) / 'maven'}"]
            gradle_extra.append(f"-PhaifaRepository={uri}")
        for project in ("maven", "spring-boot"):
            subprocess.run(
                [maven, "--batch-mode", "--no-transfer-progress", *extra,
                 f"-Dhaifa.version={args.version}", "-f", str(directory / project / "pom.xml"), "clean", "verify"],
                cwd=root, check=True,
            )
        subprocess.run(
            [gradle, "--no-daemon", "--console=plain", "-p", str(directory / "gradle"),
             *gradle_extra, "clean", "build"], cwd=root, check=True,
        )
        subprocess.run(
            [maven, "--batch-mode", "--no-transfer-progress", *extra,
             f"-Dhaifa-agent.version={args.version}", "-f",
             str(root / "examples" / "haifa-agent-example" / "pom.xml"), "clean", "verify"],
            cwd=root, check=True,
        )


if __name__ == "__main__":
    main()
