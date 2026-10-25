
#!/usr/bin/env python3

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

INVALID = -1
WEIGHT_SCALE = 4


@dataclass
class Node:
    path: Path
    children: list["Node"] = field(default_factory=list)

    # 该目录直接包含的 Java code 行数。
    direct_loc: int = 0

    # 当前目录及所有后代的 Java code 行数。
    recursive_loc: int = 0

    # direct_loc + 0.25 * descendant_loc，乘 4 保存为整数。
    score: int = 0

    # 当前目录中的所有文件和子目录数量。
    entry_count: int = 0

    eligible: bool = True

    dp: list[int] = field(default_factory=list)
    history: list[list[tuple[int, int] | None]] = field(
        default_factory=list
    )
    take_self: list[bool] = field(default_factory=list)


def build_tree(path: Path) -> Node:
    node = Node(path.resolve())

    with os.scandir(node.path) as iterator:
        entries = sorted(iterator, key=lambda entry: entry.name)

    # 包含普通文件、目录和符号链接。
    node.entry_count = len(entries)

    for entry in entries:
        if entry.is_symlink():
            continue

        if entry.is_dir(follow_symlinks=False):
            node.children.append(build_tree(Path(entry.path)))

    return node


def index_tree(node: Node, result: dict[Path, Node]):
    result[node.path] = node

    for child in node.children:
        index_tree(child, result)


def resolve_cloc_path(name: str, root: Path) -> Path | None:
    raw = Path(name)

    if raw.is_absolute():
        candidates = [raw]
    else:
        candidates = [
            Path.cwd() / raw,
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


def load_cloc(root: Path, by_path: dict[Path, Node]):
    try:
        result = subprocess.run(
            [
                "cloc",
                "--by-file",
                "--json",
                "--include-lang=Java",
                str(root),
            ],
            cwd=Path.cwd(),
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except FileNotFoundError as exc:
        raise RuntimeError(
            "未找到 cloc，请先安装 cloc 并确保它位于 PATH 中。"
        ) from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(
            f"cloc 执行失败：{exc.stderr.strip()}"
        ) from exc

    report = json.loads(result.stdout)

    for name, stats in report.items():
        if name in ("header", "SUM"):
            continue

        if not isinstance(stats, dict) or "code" not in stats:
            continue

        file_path = resolve_cloc_path(name, root)

        if file_path is None:
            continue

        parent = by_path.get(file_path.parent)

        if parent is not None:
            parent.direct_loc += int(stats.get("code", 0))


def calculate_metrics(node: Node):
    for child in node.children:
        calculate_metrics(child)

    descendants_loc = sum(
        child.recursive_loc for child in node.children
    )

    node.recursive_loc = node.direct_loc + descendants_loc

    node.score = (
        WEIGHT_SCALE * node.direct_loc
        + descendants_loc
    )


def mark_eligibility(node: Node):
    # 如果父目录只有一个目录项，且该项就是当前子目录，
    # 则当前子目录不能作为候选项。
    singleton_parent = (
        node.entry_count == 1
        and len(node.children) == 1
    )

    for child in node.children:
        child.eligible = not singleton_parent
        mark_eligibility(child)


def merge(children: list[Node], k: int):
    """合并子树，求选择恰好 n 个目录时的最大总分。"""
    dp = [INVALID] * (k + 1)
    dp[0] = 0

    history = []

    for child in children:
        nxt = [INVALID] * (k + 1)
        layer = [None] * (k + 1)

        for used, current_score in enumerate(dp):
            if current_score == INVALID:
                continue

            for child_count, child_score in enumerate(
                child.dp[:k - used + 1]
            ):
                if child_score == INVALID:
                    continue

                total = used + child_count
                candidate = current_score + child_score

                if candidate > nxt[total]:
                    nxt[total] = candidate
                    layer[total] = (used, child_count)

        dp = nxt
        history.append(layer)

    return dp, history


def compute(node: Node, k: int, is_root: bool = False):
    for child in node.children:
        compute(child, k)

    # 不选择当前目录时，可以从各个子树中选择目录。
    node.dp, node.history = merge(node.children, k)
    node.take_self = [False] * (k + 1)

    # 只有直接包含 Java code 行的目录才有资格入选。
    if (
        not is_root
        and node.eligible
        and node.direct_loc > 0
        and k >= 1
        and node.score > node.dp[1]
    ):
        # 选择当前目录后，不能再选择任何后代。
        node.dp[1] = node.score
        node.take_self[1] = True


def recover(node: Node, k: int, result: list[Node]):
    if k == 0:
        return

    if node.take_self[k]:
        result.append(node)
        return

    allocations = [0] * len(node.children)
    remaining = k

    for i in range(len(node.children) - 1, -1, -1):
        record = node.history[i][remaining]

        if record is None:
            raise RuntimeError("动态规划回溯失败。")

        previous, child_count = record
        allocations[i] = child_count
        remaining = previous

    for child, child_count in zip(node.children, allocations):
        recover(child, child_count, result)


def main():
    parser = argparse.ArgumentParser(
        description=(
            "使用 cloc 统计 Java code 行数，"
            "自动选择互不包含的目录。"
        )
    )

    parser.add_argument("directory", nargs="?", default="src")
    parser.add_argument("count", nargs="?", type=int, default=10)

    args = parser.parse_args()

    if args.count < 0:
        parser.error("目录数量不能小于 0。")

    root = Path(args.directory).resolve()

    if not root.is_dir():
        parser.error(f"输入目录不存在：{args.directory}")

    try:
        tree = build_tree(root)

        by_path: dict[Path, Node] = {}
        index_tree(tree, by_path)

        load_cloc(root, by_path)
        calculate_metrics(tree)
        mark_eligibility(tree)
        compute(tree, args.count, is_root=True)

        if tree.dp[args.count] == INVALID:
            parser.error(
                f"无法选择恰好 {args.count} 个互不包含的目录。"
            )

        selected: list[Node] = []
        recover(tree, args.count, selected)

        selected.sort(key=lambda node: node.path.as_posix())

        display_root = Path(
            os.path.normpath(args.directory)
        ).as_posix().rstrip("/")

        paths = []

        for node in selected:
            relative = node.path.relative_to(root).as_posix()

            if display_root in ("", "."):
                display_path = relative
            else:
                display_path = f"{display_root}/{relative}"

            paths.append(display_path)

        # 标准输出只包含 JSON，方便其他脚本消费。
        print(json.dumps(paths, ensure_ascii=False, indent=2))

        # 诊断信息写入标准错误，不污染 JSON。
        print(
            f"加权总分：{tree.dp[args.count] / WEIGHT_SCALE:,.2f}",
            file=sys.stderr,
        )

        for node, path in zip(selected, paths):
            print(
                f"{path}"
                f" | direct={node.direct_loc:,}"
                f" | recursive={node.recursive_loc:,}"
                f" | score={node.score / WEIGHT_SCALE:,.2f}",
                file=sys.stderr,
            )

    except FileNotFoundError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
