"""扫码界面静态校验：XML 可解析，悬浮按钮和 Kotlin 接线保持一致。

用法（在仓库根目录执行）：uv run tools/diagnostics/roo_check_scanner_ui.py
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LAYOUT = REPO / "app/src/main/res/layout/layout_scanner.xml"
BACKGROUND = REPO / "app/src/main/res/drawable/scanner_action_background.xml"
ACTIVITY = REPO / "app/src/main/java/io/nekohasekai/sagernet/ui/ScannerActivity.kt"
MENU = REPO / "app/src/main/res/menu/scanner_menu.xml"
RES = REPO / "app/src/main/res"

ANDROID = "{http://schemas.android.com/apk/res/android}"
errors: list[str] = []

for xml_file in (LAYOUT, BACKGROUND):
    try:
        ET.parse(xml_file)
    except (ET.ParseError, OSError) as error:
        errors.append(f"{xml_file.relative_to(REPO)}: XML 无法解析: {error}")

if not errors:
    root = ET.parse(LAYOUT).getroot()
    children = list(root)
    if any(child.tag == "include" for child in children):
        errors.append("layout_scanner.xml: 不应保留会被全屏预览遮挡的 appbar include")

    expected = {
        "@+id/ivFlashlight": "bottom|start",
        "@+id/ivPhotoLibrary": "bottom|end",
    }
    for view_id, gravity in expected.items():
        view = next((child for child in children if child.get(ANDROID + "id") == view_id), None)
        if view is None:
            errors.append(f"layout_scanner.xml: 缺少 {view_id}")
        elif view.get(ANDROID + "layout_gravity") != gravity:
            errors.append(f"layout_scanner.xml: {view_id} 应位于 {gravity}")

activity = ACTIVITY.read_text(encoding="utf-8")
for reference in (
    "binding.ivFlashlight.setOnClickListener",
    "binding.ivPhotoLibrary.setOnClickListener",
    'startFilesForResult(importCodeFile, "image/*")',
):
    if reference not in activity:
        errors.append(f"ScannerActivity.kt: 缺少 `{reference}`")

if "setSupportActionBar" in activity or "onCreateOptionsMenu" in activity:
    errors.append("ScannerActivity.kt: 不应继续依赖不可见的工具栏菜单")
if MENU.exists():
    errors.append("scanner_menu.xml: 悬浮按钮接管入口后该菜单应删除")

required_strings = {"scanner_toggle_flashlight", "scanner_select_image"}
for values_dir in sorted(RES.glob("values-*")):
    strings_file = values_dir / "strings.xml"
    if not strings_file.exists() or values_dir.name.startswith(("values-night", "values-v")):
        continue
    try:
        translated = {
            element.get("name")
            for element in ET.parse(strings_file).getroot().findall("string")
        }
    except ET.ParseError as error:
        errors.append(f"{strings_file.relative_to(REPO)}: XML 无法解析: {error}")
        continue
    missing = sorted(required_strings - translated)
    if missing:
        errors.append(f"{strings_file.relative_to(REPO)}: 缺少扫码文案 {', '.join(missing)}")

print("checked scanner layout, action wiring, obsolete toolbar menu and locale strings")
if errors:
    print(f"\n{len(errors)} error(s):")
    for error in errors:
        print(" -", error)
    sys.exit(1)
print("OK: scanner actions are visible and wired")
