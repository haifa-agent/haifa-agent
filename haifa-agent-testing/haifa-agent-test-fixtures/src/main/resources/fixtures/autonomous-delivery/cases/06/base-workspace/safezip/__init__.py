from pathlib import Path
from zipfile import ZipFile


def extract_archive(archive: str | Path, destination: str | Path) -> None:
    with ZipFile(archive) as source:
        source.extractall(destination)


__all__ = ["extract_archive"]
