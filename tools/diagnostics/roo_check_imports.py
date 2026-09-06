"""静态校验 libcore 的 Go import（无本地 Go 环境的替代检查）：

1. fork 残留检查：禁止 import libneko / sing-box/nekoutils / sing-box/boxapi / sing-box/common/conntrack
2. sing-box 子包路径检查：github.com/sagernet/sing-box/xxx 必须在官方源码树中存在对应目录
3. libcore 内部包路径检查

用法（在仓库根目录执行）：uv run tools/diagnostics/roo_check_imports.py [sing-box源码目录]
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LIBCORE = REPO / "libcore"
SING_BOX = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO.parent / "sing-box"

FORBIDDEN = (
    "github.com/matsuridayo/libneko",
    "github.com/sagernet/sing-box/nekoutils",
    "github.com/sagernet/sing-box/boxapi",
    "github.com/sagernet/sing-box/common/conntrack",
    "starifly",
)

IMPORT_RE = re.compile(r'^\s*(?:[\w.]+\s+)?"([^"]+)"\s*$')

errors: list[str] = []
checked = 0


def collect_imports(go_file: Path) -> list[str]:
    """提取 import 块与单行 import 中的包路径。"""
    imports: list[str] = []
    in_block = False
    for line in go_file.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not in_block and stripped.startswith("import ("):
            in_block = True
            continue
        if in_block:
            if stripped == ")":
                in_block = False
                continue
            m = IMPORT_RE.match(line)
            if m:
                imports.append(m.group(1))
        else:
            m = re.match(r'^import\s+(?:[\w.]+\s+)?"([^"]+)"', stripped)
            if m:
                imports.append(m.group(1))
    return imports


for go_file in sorted(LIBCORE.rglob("*.go")):
    for pkg in collect_imports(go_file):
        checked += 1
        rel = go_file.relative_to(REPO)
        for bad in FORBIDDEN:
            if bad in pkg:
                errors.append(f"{rel}: 残留 fork 依赖 {pkg}")
        if pkg.startswith("github.com/sagernet/sing-box/"):
            sub = pkg.removeprefix("github.com/sagernet/sing-box/")
            if not (SING_BOX / sub).is_dir():
                errors.append(f"{rel}: 官方 sing-box 无此包 {pkg}")
        elif pkg == "github.com/sagernet/sing-box":
            pass  # 根包 box
        elif pkg.startswith("libcore/"):
            sub = pkg.removeprefix("libcore/")
            if not (LIBCORE / sub).is_dir():
                errors.append(f"{rel}: libcore 内部包不存在 {pkg}")

print(f"checked {checked} imports, sing-box tree: {SING_BOX} (exists={SING_BOX.is_dir()})")
if errors:
    print(f"\n{len(errors)} error(s):")
    for e in errors:
        print(" -", e)
    sys.exit(1)
print("OK: no fork residue, all sing-box subpackages exist in official tree")
