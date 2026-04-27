#!/usr/bin/env python3
"""
Fix Android Studio Image Asset generated XML files.

Problem: Image Asset puts Apache license comments BEFORE <?xml version="1.0"?>,
which violates XML spec and breaks AGP resource merging.

Usage:
    python fix_image_asset_xml.py          # fix receiver/android
    python fix_image_asset_xml.py --all    # fix receiver + detector
"""

import argparse
import re
import sys
from pathlib import Path


def fix_xml_file(path: Path) -> bool:
    """
    Move <?xml...?> declaration to line 1 if it's preceded by a comment.
    Returns True if file was modified.
    """
    text = path.read_text(encoding="utf-8")

    # Fast path: file already starts with <?xml
    stripped = text.lstrip()
    if stripped.startswith("<?xml"):
        return False

    # Find the <?xml ... ?> declaration line
    lines = text.splitlines(keepends=True)
    xml_idx = None
    for i, line in enumerate(lines):
        if line.strip().startswith("<?xml"):
            xml_idx = i
            break

    if xml_idx is None or xml_idx == 0:
        return False  # No XML decl found, or already at top

    # Extract everything before the XML decl (should be a comment block)
    before = lines[:xml_idx]
    xml_line = lines[xml_idx]
    after = lines[xml_idx + 1 :]

    # Only fix if the pre-content is a comment block
    before_text = "".join(before).strip()
    if not before_text.startswith("<!--") or not before_text.endswith("-->"):
        return False

    new_text = xml_line + "".join(before) + "".join(after)

    if new_text != text:
        path.write_text(new_text, encoding="utf-8")
        return True
    return False


def scan_and_fix(root: Path) -> list[Path]:
    """Scan res/ directory for XML files and fix invalid ones."""
    fixed = []
    res_dir = root / "app" / "src" / "main" / "res"
    if not res_dir.exists():
        print(f"[!] res dir not found: {res_dir}")
        return fixed

    for xml_path in res_dir.rglob("*.xml"):
        try:
            if fix_xml_file(xml_path):
                fixed.append(xml_path)
                print(f"  [FIXED] {xml_path.relative_to(root)}")
        except Exception as e:
            print(f"  [ERROR] {xml_path.relative_to(root)}: {e}")

    return fixed


def main():
    parser = argparse.ArgumentParser(description="Fix Image Asset XML license headers")
    parser.add_argument(
        "--all",
        action="store_true",
        help="Fix both receiver/android and detector/android",
    )
    args = parser.parse_args()

    script_dir = Path(__file__).parent.resolve()
    projects = [script_dir]

    if args.all:
        # Try to find detector sibling directory
        detector = script_dir.parent.parent / "detector" / "android"
        if detector.exists():
            projects.append(detector)
        else:
            print(f"[!] detector/android not found at expected path: {detector}")

    total_fixed = 0
    for project in projects:
        print(f"\n>> Scanning: {project.name}")
        fixed = scan_and_fix(project)
        total_fixed += len(fixed)

    print(f"\n=== Done. Fixed {total_fixed} file(s). ===")
    return 0 if total_fixed >= 0 else 1


if __name__ == "__main__":
    sys.exit(main())
