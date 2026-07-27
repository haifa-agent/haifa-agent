#!/usr/bin/env python3
"""Render terminal screen snapshots to PNG frames and an animated GIF."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


BACKGROUND = "#101419"
FOREGROUND = "#e6edf3"
MUTED = "#8b949e"
ACCENT = "#58a6ff"


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--screens", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--font", type=Path, default=Path(r"C:\Windows\Fonts\msyh.ttc"))
    parser.add_argument("--font-size", type=int, default=17)
    parser.add_argument("--duration-ms", type=int, default=900)
    return parser.parse_args()


def load_snapshots(source: Path) -> list[dict]:
    snapshots = []
    with source.open("r", encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                snapshots.append(json.loads(line))
    if not snapshots:
        raise ValueError(f"No screen snapshots found in {source}")
    return snapshots


def main() -> None:
    options = arguments()
    snapshots = load_snapshots(options.screens)
    options.output.mkdir(parents=True, exist_ok=True)

    font = ImageFont.truetype(str(options.font), options.font_size)
    label_font = ImageFont.truetype(str(options.font), max(13, options.font_size - 2))
    cell_width = max(9, int(font.getlength("M")))
    line_height = options.font_size + 5
    margin = 18
    label_height = 34
    prepared = []
    for snapshot in snapshots:
        lines = snapshot.get("lines")
        if lines is None:
            lines = snapshot["screen"].split("\n")
        prepared.append((snapshot, lines))
    columns = max(120, max((len(line) for _, lines in prepared for line in lines), default=120))
    rows = max(40, max((len(lines) for _, lines in prepared), default=40))
    width = margin * 2 + columns * cell_width
    height = margin * 2 + label_height + rows * line_height

    frames: list[Image.Image] = []
    for index, (snapshot, lines) in enumerate(prepared):
        image = Image.new("RGB", (width, height), BACKGROUND)
        draw = ImageDraw.Draw(image)
        label = (
            f"{index + 1:02d}/{len(snapshots):02d}  "
            f"{snapshot.get('elapsedSeconds', 0):7.2f}s  "
            f"{snapshot.get('label', 'snapshot')}"
        )
        draw.text((margin, margin), label, font=label_font, fill=ACCENT)
        draw.line(
            (margin, margin + label_height - 7, width - margin, margin + label_height - 7),
            fill=MUTED,
            width=1,
        )
        y = margin + label_height
        for line in lines:
            draw.text((margin, y), line, font=font, fill=FOREGROUND)
            y += line_height
        frame_path = options.output / f"frame-{index:03d}.png"
        image.save(frame_path)
        frames.append(image)

    gif_path = options.output / "terminal-evidence.gif"
    frames[0].save(
        gif_path,
        save_all=True,
        append_images=frames[1:],
        duration=options.duration_ms,
        loop=0,
        optimize=False,
    )
    summary = {
        "schema": "haifa.terminal-screen-render/1",
        "source": str(options.screens.resolve()),
        "frames": len(frames),
        "gif": str(gif_path.resolve()),
        "frameDirectory": str(options.output.resolve()),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
