from pathlib import Path
import xml.etree.ElementTree as ET


ANDROID_NS = "http://schemas.android.com/apk/res/android"
ATTR = f"{{{ANDROID_NS}}}"
EXPECTED_SHORTCUTS = {
    "toggle": "io.nekohasekai.sagernet.QuickToggleShortcut",
    "enable": "io.nekohasekai.sagernet.ui.QuickEnableShortcut",
    "disable": "io.nekohasekai.sagernet.ui.QuickDisableShortcut",
    "scan": "io.nekohasekai.sagernet.ui.ScannerActivity",
}
EXPECTED_PACKAGE = "com.nb4a.throne"
MANIFEST_NAMESPACE = "io.nekohasekai.sagernet"


def normalize_activity_name(name: str) -> str:
    if name.startswith("."):
        return MANIFEST_NAMESPACE + name
    if "." not in name:
        return f"{MANIFEST_NAMESPACE}.{name}"
    return name


def main() -> None:
    shortcuts_path = Path("app/src/main/res/xml/shortcuts.xml")
    manifest_path = Path("app/src/main/AndroidManifest.xml")

    shortcuts_root = ET.parse(shortcuts_path).getroot()
    shortcuts = shortcuts_root.findall("shortcut")
    actual_ids = [shortcut.get(ATTR + "shortcutId") for shortcut in shortcuts]
    assert actual_ids == list(EXPECTED_SHORTCUTS), actual_ids

    target_classes: set[str] = set()
    for shortcut in shortcuts:
        shortcut_id = shortcut.get(ATTR + "shortcutId")
        intent = shortcut.find("intent")
        assert intent is not None, shortcut_id
        assert intent.get(ATTR + "targetPackage") == EXPECTED_PACKAGE, shortcut_id
        target_class = intent.get(ATTR + "targetClass")
        assert target_class == EXPECTED_SHORTCUTS[shortcut_id], shortcut_id
        target_classes.add(target_class)

    manifest_root = ET.parse(manifest_path).getroot()
    application = manifest_root.find("application")
    assert application is not None
    activities = application.findall("activity")
    declared_activities = {
        normalize_activity_name(activity.get(ATTR + "name")): activity
        for activity in activities
    }

    main_activity = declared_activities["io.nekohasekai.sagernet.ui.MainActivity"]
    shortcut_metadata = [
        metadata
        for metadata in main_activity.findall("meta-data")
        if metadata.get(ATTR + "name") == "android.app.shortcuts"
    ]
    assert len(shortcut_metadata) == 1
    assert shortcut_metadata[0].get(ATTR + "resource") == "@xml/shortcuts"

    application_shortcut_metadata = [
        metadata
        for metadata in application.findall("meta-data")
        if metadata.get(ATTR + "name") == "android.app.shortcuts"
    ]
    assert not application_shortcut_metadata

    for target_class in target_classes:
        activity = declared_activities[target_class]
        assert activity.get(ATTR + "exported") == "true", target_class

    assert not Path("app/src/debug/res/xml/shortcuts.xml").exists()
    print("shortcut static checks: PASS")


if __name__ == "__main__":
    main()
