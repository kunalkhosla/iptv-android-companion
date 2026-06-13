#!/usr/bin/env python3
"""Look up the screen-center coords for a uiautomator-dumped element by
the first (or, with --last, the last) node whose `text` or
`content-desc` *contains* the needle.

Outputs "<cx> <cy>" — the rectangle center, suitable to feed straight
into `adb shell input tap`. No output means no match.

Usage: find-tap-target.py [--last] <ui-dump.xml> <needle>
"""
import html
import re
import sys

args = sys.argv[1:]
last = False
if args and args[0] == "--last":
    last = True
    args.pop(0)
if len(args) != 2:
    sys.exit(f"usage: {sys.argv[0]} [--last] <ui-dump.xml> <needle>")

path, needle = args
with open(path, "r", encoding="utf-8", errors="replace") as fh:
    xml = fh.read()

# Each uiautomator <node ...> opens until "/>" (leaf) or ">" (container);
# we only care about attributes on the opening tag, not child structure.
NODE_RE = re.compile(r'<node\b[^>]*?(?:/>|>)')
TEXT_RE = re.compile(r' text="([^"]*)"')
DESC_RE = re.compile(r' content-desc="([^"]*)"')
BOUNDS_RE = re.compile(r' bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')

matches = []
for m in NODE_RE.finditer(xml):
    tag = m.group(0)
    text = html.unescape(TEXT_RE.search(tag).group(1)) if TEXT_RE.search(tag) else ""
    desc = html.unescape(DESC_RE.search(tag).group(1)) if DESC_RE.search(tag) else ""
    if needle in text or needle in desc:
        b = BOUNDS_RE.search(tag)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            matches.append(((x1 + x2) // 2, (y1 + y2) // 2))

if matches:
    cx, cy = matches[-1] if last else matches[0]
    print(f"{cx} {cy}")
