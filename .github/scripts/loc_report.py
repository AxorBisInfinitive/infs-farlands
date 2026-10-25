
#!/usr/bin/env python3

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
SRC_ROOT = (ROOT / "src").resolve()

METRICS = ("blank", "comment", "code")


def run_command(command: list[str]) -> str:
    result = subprocess.run(
        command,
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.stdout.strip()


def run_git(*args: str) -> str:
    return run_command(["git", *args])


def parse_time(value: str) -> datetime:
    result = datetime.fromisoformat(
        value.replace("Z", "+00:00")
    )

    if result.tzinfo is None:
        result = result.replace(tzinfo=timezone.utc)

    return result


def format_time(value: datetime | None) -> str:
    if value is None:
        return "未知"

    return value.astimezone(timezone.utc).strftime(
        "%Y-%m-%d %H:%M:%S UTC"
    )


def resolve_cloc_path(name: str, root: Path) -> Path | None:
    raw = Path(name)

    if raw.is_absolute():
        candidates = [raw]
    else:
        candidates = [
            ROOT / raw,
            root / raw,
        ]

    for candidate in candidates:
        try:
            candidate = candidate.resolve()

            if (
                candidate.is_relative_to(root)
                and candidate.suffix.lower() == ".java"
                and candidate.is_file()
            ):
                return candidate
        except OSError:
            continue

    return None


def load_cloc_records() -> dict[Path, dict[str, int]]:
    try:
        output = run_command([
            "cloc",
            "--by-file",
            "--json",
            "--include-lang=Java",
            str(SRC_ROOT),
        ])
    except FileNotFoundError as exc:
        raise RuntimeError(
            "未找到 cloc，请先安装 cloc 并确保它位于 PATH 中。"
        ) from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(
            f"cloc 执行失败：{exc.stderr.strip()}"
        ) from exc

    report = json.loads(output)
    records: dict[Path, dict[str, int]] = {}

    for name, stats in report.items():
        if name in ("header", "SUM"):
            continue

        if not isinstance(stats, dict) or "code" not in stats:
            continue

        path = resolve_cloc_path(name, SRC_ROOT)

        if path is None:
            continue

        records[path] = {
            "blank": int(stats.get("blank", 0)),
            "comment": int(stats.get("comment", 0)),
            "code": int(stats.get("code", 0)),
        }

    return records


def aggregate(
    records: dict[Path, dict[str, int]],
    directory: Path,
) -> dict[str, int]:
    result = {
        "files": 0,
        "blank": 0,
        "comment": 0,
        "code": 0,
    }

    for path, stats in records.items():
        if not path.is_relative_to(directory):
            continue

        result["files"] += 1

        for metric in METRICS:
            result[metric] += stats[metric]

    return result


def directory_history(relative_path: str):
    # Git 不单独存储目录创建时间。
    # 这里取目录树中最早新增文件的提交时间。
    output = run_git(
        "log",
        "--root",
        "--diff-filter=A",
        "--no-renames",
        "--format=COMMIT:%cI",
        "--name-only",
        "--",
        relative_path,
    )

    creation_dates = []

    for line in output.splitlines():
        if not line.startswith("COMMIT:"):
            continue

        creation_dates.append(
            parse_time(line.removeprefix("COMMIT:"))
        )

    created = min(creation_dates) if creation_dates else None

    # 最近一次影响当前目录的提交。
    output = run_git(
        "log",
        "-1",
        "--format=%H%x09%cI",
        "--",
        relative_path,
    )

    if not output:
        return created, None, None

    parts = output.split("\t", maxsplit=1)

    if len(parts) != 2:
        raise RuntimeError(
            f"无法解析目录的最后一次 Git 提交：{relative_path}"
        )

    commit_sha, commit_time = parts

    return created, parse_time(commit_time), commit_sha


def escape_cell(value: str) -> str:
    return (
        str(value)
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("\n", " ")
    )


def main():
    selected_file = Path(
        os.environ["SELECTED_DIRECTORIES_FILE"]
    )

    selected = json.loads(
        selected_file.read_text(encoding="utf-8")
    )

    if not isinstance(selected, list) or len(selected) != 10:
        raise ValueError(
            "摘要目录必须恰好包含 10 个路径。"
        )

    repository = os.environ["GITHUB_REPOSITORY"]
    branch = os.environ["GITHUB_REF_NAME"]
    commit = os.environ["GITHUB_SHA"]
    server = os.environ.get(
        "GITHUB_SERVER_URL",
        "https://github.com",
    ).rstrip("/")

    records = load_cloc_records()

    # 1. 两个 source set 的真实 LoC 统计。
    source_sets = [
        ("src/main/java", ROOT / "src/main/java"),
        ("src/client/java", ROOT / "src/client/java"),
    ]

    source_rows = []

    for name, path in source_sets:
        path = path.resolve()

        if not path.is_dir():
            raise ValueError(f"Source set 不存在：{name}")

        stats = aggregate(records, path)
        source_rows.append({
            "name": name,
            **stats,
        })

    source_total = {
        key: sum(row[key] for row in source_rows)
        for key in ("files", *METRICS)
    }

    # 2. 十个摘要目录的统计与 Git 历史。
    package_rows = []
    seen_paths = set()

    for item in selected:
        if not isinstance(item, str):
            raise ValueError(f"无效目录路径：{item!r}")

        relative_path = Path(item)

        if relative_path.is_absolute():
            raise ValueError(f"不允许绝对路径：{item}")

        directory = (ROOT / relative_path).resolve()

        if (
            not directory.is_relative_to(SRC_ROOT)
            or directory == SRC_ROOT
            or not directory.is_dir()
        ):
            raise ValueError(f"无效摘要目录：{item}")

        relative = directory.relative_to(ROOT).as_posix()

        if relative in seen_paths:
            raise ValueError(f"摘要目录重复：{relative}")

        seen_paths.add(relative)

        stats = aggregate(records, directory)

        created, modified, last_sha = directory_history(relative)

        directory_url = (
            f"{server}/{repository}/tree/{commit}/"
            f"{quote(relative, safe='/')}"
        )

        last_commit_url = (
            f"{server}/{repository}/commit/{last_sha}"
            if last_sha
            else None
        )

        package_rows.append({
            "path": relative,
            "directory_url": directory_url,
            "files": stats["files"],
            "loc": stats["code"],
            "created": format_time(created),
            "modified": format_time(modified),
            "last_sha": last_sha,
            "last_commit_url": last_commit_url,
        })

    package_total_loc = sum(
        row["loc"] for row in package_rows
    )
    package_total_files = sum(
        row["files"] for row in package_rows
    )

    # 3. 生成中文 Job Summary。
    lines = [
        "# Java LoC Report",
        "",
        "## 基本信息",
        "",
        f"- **仓库：** `{repository}`",
        f"- **分支：** `{branch}`",
        f"- **报告提交：** [`{commit[:7]}`]"
        f"({server}/{repository}/commit/{commit})",
        "- **统计工具：** `cloc`",
        "",
        "## 统计",
        "",
        "| 源集 | 文件总数 | 空行 | 注释行 | LoC |",
        "|:--|--:|--:|--:|--:|",
    ]

    for row in source_rows:
        lines.append(
            f"| `{row['name']}` "
            f"| {row['files']:,} "
            f"| {row['blank']:,} "
            f"| {row['comment']:,} "
            f"| {row['code']:,} |"
        )

    lines.append(
        f"| **合计** "
        f"| **{source_total['files']:,}** "
        f"| **{source_total['blank']:,}** "
        f"| **{source_total['comment']:,}** "
        f"| **{source_total['code']:,}** |"
    )

    lines.extend([
        "",
        "## 摘要",
        "",
        "| 目录 | LoC 总数 | 文件总数 | 创建时间 | 最后修改时间 | 最后 Commit |",
        "|:--|--:|--:|:--|:--|:--|",
    ])

    for row in package_rows:
        directory_link = (
            f"[{escape_cell(row['path'])}]"
            f"({row['directory_url']})"
        )

        if row["last_sha"] and row["last_commit_url"]:
            last_commit = (
                f"[`{row['last_sha'][:7]}`]"
                f"({row['last_commit_url']})"
            )
        else:
            last_commit = "未知"

        lines.append(
            f"| {directory_link} "
            f"| {row['loc']:,} "
            f"| {row['files']:,} "
            f"| {row['created']} "
            f"| {row['modified']} "
            f"| {last_commit} |"
        )

    lines.append(
        f"| **合计** "
        f"| **{package_total_loc:,}** "
        f"| **{package_total_files:,}** "
        f"| — | — | — |"
    )

    lines.append("")

    summary_path = Path(os.environ["GITHUB_STEP_SUMMARY"])

    with summary_path.open("a", encoding="utf-8") as summary:
        summary.write("\n".join(lines))

    print(
        "LoC report generated successfully: "
        f"{source_total['code']:,} source-set LoC; "
        f"{len(package_rows)} summary directories."
    )


if __name__ == "__main__":
    try:
        main()
    except (
        OSError,
        ValueError,
        RuntimeError,
        subprocess.CalledProcessError,
    ) as exc:
        print(f"LoC report failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
