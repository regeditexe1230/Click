"""把 WinJS 导航转场族的完整函数体逐字导出，供人工核对。

背景：`_vercheck.py` 的对比表已经显示 `turnstileForwardIn` 里有
`perspective(600px) rotateY(80deg)` —— 即 Win8 **确实有**绕 Y 轴的 3D 旋转转场，
且**显式指定了透视距离 600px**。这推翻了「Win8 动画库里没有旋转」的早期说法，
必须把原文抄出来核实，不能再靠参数摘要。

用法：python docs\\win8-frames\\_dumpnav.py
输出：docs\\win8-frames\\winjs-transitions.txt
"""

import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "_vercheck_cache")
OUT = os.path.join(HERE, "winjs-transitions.txt")

VERSION = "4.4.3"
SRC = os.path.join(CACHE, f"winjs-{VERSION}-base.js")

# 要导出的转场族
NAV = [
    "continuumForwardIn", "continuumForwardOut",
    "continuumBackwardIn", "continuumBackwardOut",
    "turnstileForwardIn", "turnstileForwardOut",
    "turnstileBackwardIn", "turnstileBackwardOut",
    "drillInIncoming", "drillInOutgoing",
    "drillOutIncoming", "drillOutOutgoing",
    "slideUp", "slideDown",
    "slideLeftIn", "slideLeftOut",
    "slideRightIn", "slideRightOut",
    "crossFade",
]


def normalize(txt):
    """把源码统一成 LF 换行，**必须先处理 `\\r\\r\\n`**。

    本仓库缓存里的 WinJS 是 `\\r\\r\\n` 结尾（下载时被文本模式二次转换）。
    Python 的 universal newlines 会把 `\\r\\r\\n` 变成两个 `\\n`，
    于是 `src.count("\\n")` 数出来的行号**整整翻一倍** ——
    本脚本第一版就是这样把 `turnstileForwardIn`（真实 L17934）标成了 L35867。
    先替换 `\\r\\r\\n`，再按常规走，行号才与 unpkg 原文一致。
    """
    txt = txt.replace("\r\r\n", "\n")
    txt = txt.replace("\r\n", "\n")
    return txt.replace("\r", "\n")


def body_of(src, fname):
    """定位某动画的实现体（花括号配平）。

    锚点用唯一的 profiler 标记 `"<name>Animation,StartTM"` 或 `"<name>,StartTM"`，
    因为动画名本身也会出现在文档注释和别的函数里。
    """
    mi = -1
    for mk in (f'"{fname}Animation,StartTM"', f'"{fname},StartTM"'):
        mi = src.find(mk)
        if mi >= 0:
            break
    if mi < 0:
        return None, None

    cands = []
    for pat in (
        re.compile(r"\b" + re.escape(fname) + r"\s*:\s*function\s*\([^)]*\)\s*\{"),
        re.compile(r"\bexecute\s*:\s*function\s*\([^)]*\)\s*\{"),
    ):
        last = None
        for mm in pat.finditer(src, 0, mi):
            last = mm
        if last is not None:
            cands.append(last)
    if not cands:
        return None, None
    m = max(cands, key=lambda mm: mm.start())

    start = m.end() - 1
    depth = 0
    i = start
    in_str = None
    while i < len(src):
        c = src[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == in_str:
                in_str = None
        elif c in "\"'":
            in_str = c
        elif c == "/" and i + 1 < len(src) and src[i + 1] == "/":
            j = src.find("\n", i)
            i = len(src) if j < 0 else j
            continue
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                line = src.count("\n", 0, m.start()) + 1
                return line, src[m.start():i + 1]
        i += 1
    return None, None


def main():
    with open(SRC, "rb") as f:
        src = normalize(f.read().decode("utf-8", errors="replace"))

    out = []
    out.append(f"# WinJS {VERSION} 导航转场族 —— 实现体逐字导出\n")
    out.append(f"来源：https://unpkg.com/winjs@{VERSION}/js/base.js")
    out.append("本文件由 `docs/win8-frames/_dumpnav.py` 生成，内容为源码原文，未做改写。\n")
    out.append(
        "**为什么单独导出这一族**：`turnstileForwardIn` 的实现体里出现了\n"
        "`perspective(600px) rotateY(80deg)` —— 这是 Win8 **官方发行源码中真实的\n"
        "绕 Y 轴 3D 旋转转场，并且显式写明了透视距离**。它与「磁贴按压」完全不同，\n"
        "正是「进出」语义最贴合的一组。\n"
    )
    out.append("---\n")

    for name in NAV:
        ln, body = body_of(src, name)
        out.append(f"\n## `{name}`\n")
        if body is None:
            out.append(f"**在该版本中未找到**（`winjs@{VERSION}`）\n")
            continue
        out.append(f"起始行：**L{ln}**\n")
        out.append("```js")
        out.append(body)
        out.append("```\n")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print(f"-> {OUT}")

    # 控制台只打印关键的透视/旋转证据行
    print("\n关键证据（含 perspective / rotate 的行）：")
    for i, line in enumerate("\n".join(out).splitlines(), 1):
        if "perspective(" in line or "rotateX(" in line or "rotateY(" in line:
            print(f"  {line.strip()[:150]}")


if __name__ == "__main__":
    main()
