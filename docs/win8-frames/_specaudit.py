# -*- coding: utf-8 -*-
"""spec 结构自检：行数、标签计数、标题层级、markdown 表格列数一致性。

注意：控制台是 GBK，直接 print 中文/符号会 UnicodeEncodeError。
本脚本因此把完整报告写到 _specaudit.out.txt（UTF-8），控制台只印 ASCII 摘要。
"""
import pathlib
import re

p = pathlib.Path("docs/win8-tile-spec.md")
t = p.read_text(encoding="utf-8")
ls = t.splitlines()

TAGS = ("[实测]", "[文档]", "[推算]", "[推测]", "[未取得]")
counts = {tag: t.count("`%s`" % tag) for tag in TAGS}

heads = [(i, l) for i, l in enumerate(ls, 1) if re.match(r"^#{1,3} ", l)]

# 表格列数一致性
bad = []
block = []
for i, l in enumerate(ls, 1):
    if l.startswith("|"):
        block.append((i, l.count("|")))
    else:
        if len(block) >= 2 and len({c for _, c in block}) > 1:
            bad.append((block[0][0], sorted({c for _, c in block})))
        block = []

out = []
out.append("lines: %d   chars: %d" % (len(ls), len(t)))
out.append("tags: " + "  ".join("%s=%d" % (k, v) for k, v in counts.items()))
out.append("")
out.append("headings (%d):" % len(heads))
for i, l in heads:
    out.append("  L%-5d %s" % (i, l))
out.append("")
out.append("inconsistent table blocks: %s" % (bad if bad else "none"))
pathlib.Path("docs/win8-frames/_specaudit.out.txt").write_text(
    "\n".join(out), encoding="utf-8")

print("lines=%d chars=%d" % (len(ls), len(t)))
print("tags: " + " ".join("%s=%d" % (k.encode("ascii", "replace").decode(), v)
                          for k, v in counts.items()))
print("headings=%d" % len(heads))
print("inconsistent table blocks: %s" % ("none" if not bad else bad))
print("full report -> docs/win8-frames/_specaudit.out.txt")
