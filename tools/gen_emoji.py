#!/usr/bin/env python3
"""Builds app/src/main/assets/emoji.txt from Unicode's emoji-test.txt.

Keeps fully-qualified emoji without skin-tone variants, grouped by category (Chinese names).
Format: "# 分类名" lines, each followed by one line of space-separated emoji.
Usage: python3 tools/gen_emoji.py path/to/emoji-test.txt
"""
import sys

NAMES = {
    "Smileys & Emotion": "笑脸与情感", "People & Body": "人物与手势", "Animals & Nature": "动物与自然",
    "Food & Drink": "食物与饮料", "Activities": "活动", "Travel & Places": "旅行与地点",
    "Objects": "物品", "Symbols": "符号", "Flags": "旗帜",
}
TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}

groups, cur = {}, None
for line in open(sys.argv[1], encoding="utf-8"):
    if line.startswith("# group:"):
        cur = NAMES.get(line.split(":", 1)[1].strip())
        if cur:
            groups.setdefault(cur, [])
        continue
    if cur is None or line.startswith("#") or ";" not in line:
        continue
    status = line.split(";", 1)[1].split("#", 1)[0].strip()
    if status != "fully-qualified":
        continue
    emoji = line.split("#", 1)[1].strip().split(" ", 1)[0]
    if any(ch in TONES for ch in emoji):
        continue
    groups[cur].append(emoji)

with open("app/src/main/assets/emoji.txt", "w", encoding="utf-8") as f:
    for name in NAMES.values():
        if groups.get(name):
            f.write(f"# {name}\n{' '.join(groups[name])}\n")
print({k: len(v) for k, v in groups.items()})
