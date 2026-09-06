"""校验规范与 Python 维护工具没有回流到旧位置。

用法（在仓库根目录执行）：uv run tools/diagnostics/roo_check_repo_governance.py
"""

from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
TOOLS = REPO / "tools" / "diagnostics"
FORBIDDEN_DOCS = ("REPO_SCHEMA.md", "ROO_KERNEL_TODO.md", "THEME_SCHEMA.md")
STALE_REFERENCES = (
    "uv run .roo/",
    ".roo/roo_",
    "Path(__file__).parent.parent",
)

errors: list[str] = []

for name in FORBIDDEN_DOCS:
    if (REPO / name).exists():
        errors.append(f"旧规范仍存在于仓库根目录: {name}")

for forbidden_dir in (REPO / ".roo", REPO / "openspec"):
    for script in forbidden_dir.rglob("*.py"):
        errors.append(f"Python 维护工具位于保留目录: {script.relative_to(REPO)}")

for script in REPO.glob("*.py"):
    errors.append(f"Python 维护工具散落在仓库根目录: {script.name}")

for script in sorted(TOOLS.glob("*.py")):
    try:
        source = script.read_text(encoding="utf-8")
        compile(source, str(script), "exec")
    except (OSError, SyntaxError, UnicodeError) as error:
        errors.append(f"{script.relative_to(REPO)}: 无法编译: {error}")

text_files = [
    *REPO.glob("*.md"),
    *TOOLS.glob("*.py"),
    *REPO.glob("openspec/**/*.md"),
    *REPO.glob("openspec/**/*.yaml"),
]
for path in text_files:
    if path.resolve() == Path(__file__).resolve():
        continue
    text = path.read_text(encoding="utf-8", errors="replace")
    for stale in STALE_REFERENCES:
        if stale in text:
            errors.append(f"{path.relative_to(REPO)}: 残留旧路径或旧根目录推导 `{stale}`")

print(f"checked {len(list(TOOLS.glob('*.py')))} Python tools and OpenSpec layout")
if errors:
    print(f"\n{len(errors)} error(s):")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("OK: specifications and Python tools follow repository governance")
