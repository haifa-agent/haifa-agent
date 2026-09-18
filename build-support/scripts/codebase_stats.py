#!/usr/bin/env python3
"""代码库规模统计：Maven 模块、脚本目录、docs Markdown。

动作（小写位置参数）：
    stats     默认动作，输出全部三部分统计
    modules   统计 Maven 模块总数及每个模块 src/main、src/test 的文件数与行数
    scripts   统计脚本目录中 .py/.sh/.ps1 的文件数与行数
    docs      统计 docs 目录的 Markdown 文件数、总行数与平均行数

长参数使用 --kebab-case；PowerShell/Shell 入口原样透传参数。
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

SCRIPT_EXTENSIONS = (".py", ".sh", ".ps1")
DEFAULT_SCRIPT_DIRS = ("build-support/scripts", "scripts", "test-config/scripts")
SKIPPED_DIRECTORIES = {".git", ".venv", "__pycache__", "node_modules", "target", "venv"}


def repository_root() -> Path:
    return Path(__file__).resolve().parents[2]


def count_lines(path: Path) -> int:
    """按换行符统计文件行数，包含无结尾换行的最后一行。"""
    with path.open("rb") as handle:
        return sum(1 for _ in handle)


def walk_files(directory: Path) -> list[Path]:
    """递归列出目录下所有文件，跳过生成/依赖目录。"""
    files: list[Path] = []
    for path in directory.rglob("*"):
        if not path.is_file():
            continue
        if any(part in SKIPPED_DIRECTORIES for part in path.relative_to(directory).parts):
            continue
        files.append(path)
    return files


def pom_module_names(pom: Path) -> list[str]:
    try:
        tree = ET.parse(pom)
    except (OSError, ET.ParseError) as error:
        raise RuntimeError(f"无法解析 Maven POM {pom}: {error}") from error
    names: list[str] = []
    for element in tree.getroot().iter():
        if element.tag.rsplit("}", 1)[-1] != "module":
            continue
        name = (element.text or "").strip()
        if name:
            names.append(name)
    return names


def discover_maven_modules(root: Path) -> list[Path]:
    """从根 POM 出发，沿 <modules> 递归发现全部模块目录（含聚合模块，不含根项目）。"""
    root_pom = root / "pom.xml"
    if not root_pom.is_file():
        raise RuntimeError(f"未找到根 POM: {root_pom}")
    modules: list[Path] = []
    seen: set[Path] = set()
    queue = [root_pom]
    while queue:
        pom = queue.pop(0)
        if pom in seen:
            continue
        seen.add(pom)
        if pom.parent != root:
            modules.append(pom.parent)
        for name in pom_module_names(pom):
            child_pom = (pom.parent / name / "pom.xml").resolve()
            if child_pom.is_file():
                queue.append(child_pom)
    return sorted(modules, key=lambda path: path.relative_to(root).as_posix())


def count_source_tree(directory: Path) -> tuple[int, int]:
    """返回 (文件数, 总行数)；目录不存在时返回 (0, 0)。"""
    if not directory.is_dir():
        return (0, 0)
    files = walk_files(directory)
    return (len(files), sum(count_lines(path) for path in files))


def maven_module_stats(root: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for module in discover_maven_modules(root):
        main_files, main_lines = count_source_tree(module / "src" / "main")
        test_files, test_lines = count_source_tree(module / "src" / "test")
        rows.append(
            {
                "module": module.relative_to(root).as_posix(),
                "mainFiles": main_files,
                "mainLines": main_lines,
                "testFiles": test_files,
                "testLines": test_lines,
            }
        )
    return rows


def script_dir_stats(root: Path, script_dirs: list[str]) -> list[dict[str, Any]]:
    """统计每个脚本目录中 .py/.sh/.ps1 的文件数与行数（仅顶层文件）。"""
    rows: list[dict[str, Any]] = []
    for relative in script_dirs:
        directory = (root / relative).resolve()
        if not directory.is_dir():
            print(f"警告: 脚本目录不存在，已跳过: {relative}", file=sys.stderr)
            continue
        for ext in SCRIPT_EXTENSIONS:
            files = [path for path in sorted(directory.glob(f"*{ext}")) if path.is_file()]
            rows.append(
                {
                    "directory": relative,
                    "extension": ext,
                    "files": len(files),
                    "lines": sum(count_lines(path) for path in files),
                }
            )
    return rows


def docs_stats(docs: Path) -> dict[str, Any]:
    """统计 docs 的 Markdown，按根目录与一级子目录分组。"""
    if not docs.is_dir():
        raise RuntimeError(f"docs 目录不存在: {docs}")
    all_md = [path for path in walk_files(docs) if path.suffix.lower() == ".md"]
    grouped: dict[str, list[Path]] = {}
    for path in all_md:
        relative = path.relative_to(docs)
        key = relative.parts[0] if len(relative.parts) > 1 else "."
        grouped.setdefault(key, []).append(path)
    total_lines = sum(count_lines(path) for path in all_md)
    groups: list[dict[str, Any]] = []
    for key in sorted(grouped, key=lambda name: (name != ".", name)):
        files = grouped[key]
        lines = sum(count_lines(path) for path in files)
        groups.append(
            {
                "directory": key,
                "files": len(files),
                "lines": lines,
                "average": round(lines / len(files), 1),
            }
        )
    return {
        "groups": groups,
        "totalFiles": len(all_md),
        "totalLines": total_lines,
        "averageLines": round(total_lines / len(all_md), 1) if all_md else 0.0,
    }


def render_module_report(rows: list[dict[str, Any]]) -> str:
    lines = ["===== Maven 模块统计 ====="]
    lines.append(f"模块总数: {len(rows)}")
    if not rows:
        return "\n".join(lines)
    width = max(len(row["module"]) for row in rows)
    header = (
        f"{'模块':<{width}} {'main文件':>8} {'main行数':>10} {'test文件':>8} {'test行数':>10}"
    )
    lines.append(header)
    lines.append("-" * len(header))
    main_files = main_lines = test_files = test_lines = 0
    for row in rows:
        lines.append(
            f"{row['module']:<{width}} {row['mainFiles']:>8} {row['mainLines']:>10} "
            f"{row['testFiles']:>8} {row['testLines']:>10}"
        )
        main_files += row["mainFiles"]
        main_lines += row["mainLines"]
        test_files += row["testFiles"]
        test_lines += row["testLines"]
    lines.append("-" * len(header))
    lines.append(
        f"{'合计':<{width}} {main_files:>8} {main_lines:>10} {test_files:>8} {test_lines:>10}"
    )
    return "\n".join(lines)


def render_script_report(rows: list[dict[str, Any]]) -> str:
    lines = ["===== 脚本目录统计 ====="]
    if not rows:
        return "\n".join(lines)
    width = max(len(row["directory"]) for row in rows)
    header = f"{'目录':<{width}} {'扩展名':>5} {'文件数':>6} {'行数':>8}"
    lines.append(header)
    lines.append("-" * len(header))
    for row in rows:
        lines.append(
            f"{row['directory']:<{width}} {row['extension']:>5} {row['files']:>6} {row['lines']:>8}"
        )
    return "\n".join(lines)


def render_docs_report(stats: dict[str, Any]) -> str:
    lines = [
        "===== docs 目录统计 =====",
        f"md 文件总数: {stats['totalFiles']}",
        f"总行数: {stats['totalLines']}",
        f"平均行数: {stats['averageLines']}",
        "",
        "按目录分组（. 表示 docs 根目录）:",
    ]
    groups = stats["groups"]
    if not groups:
        lines.append("(无 md 文件)")
        return "\n".join(lines)
    width = max(len(group["directory"]) for group in groups)
    header = f"{'目录':<{width}} {'md文件数':>8} {'总行数':>10} {'平均行数':>8}"
    lines.append(header)
    lines.append("-" * len(header))
    for group in groups:
        lines.append(
            f"{group['directory']:<{width}} {group['files']:>8} {group['lines']:>10} "
            f"{group['average']:>8}"
        )
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="代码库规模统计：Maven 模块、脚本目录、docs Markdown。",
    )
    parser.add_argument(
        "action",
        nargs="?",
        choices=("stats", "modules", "scripts", "docs"),
        default="stats",
        help="小写位置动作，默认 stats",
    )
    parser.add_argument("--root", default="", help="仓库根目录，默认脚本所在仓库根")
    parser.add_argument(
        "--script-dirs",
        action="append",
        default=None,
        metavar="DIR",
        help="脚本目录（相对仓库根），可重复；默认 build-support/scripts、scripts、test-config/scripts",
    )
    return parser.parse_args()


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    args = parse_args()
    root = Path(args.root).resolve() if args.root else repository_root()
    try:
        if args.action in ("stats", "modules"):
            print(render_module_report(maven_module_stats(root)))
        if args.action in ("stats", "scripts"):
            script_dirs = args.script_dirs or list(DEFAULT_SCRIPT_DIRS)
            print(render_script_report(script_dir_stats(root, script_dirs)))
        if args.action in ("stats", "docs"):
            print(render_docs_report(docs_stats(root / "docs")))
    except RuntimeError as error:
        print(f"错误: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
