"""跨版本核对 WinJS 动画常量。

目的：`docs/win8-tile-spec.md` 的常量取自 winjs@4.4.3（约 Windows 10 时代）。
本任务的目标是 Windows 8，因此必须确认这些常量在更早的版本里是否一致。

npm 上最早的 winjs 是 3.0.0（2014-09，Windows 8.1 Update / Windows Phone 8.1 时代）。
WinJS 1.0 / 2.0（Windows 8 RTM / 8.1）没有发布到 npm。

用法：python docs\\win8-frames\\_vercheck.py
输出：docs\\win8-frames\\winjs-version-check.md
"""

import os
import re
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "_vercheck_cache")
OUT = os.path.join(HERE, "winjs-version-check.md")

# 要核对的版本（按时间顺序）
VERSIONS = ["3.0.0", "3.0.1", "4.4.3"]

# 要核对的动画名 -> 源码里的 profiler 标记名（不带引号）
ANIMS = [
    "pointerDown", "pointerUp",
    "enterPage", "exitPage",
    "enterContent", "exitContent",
    "expand", "collapse",
    "addToList", "deleteFromList",
    "reposition",
    "fadeIn", "fadeOut",
    "showEdgeUI", "hideEdgeUI",
    "showPanel", "hidePanel",
    "showPopup", "hidePopup",
    "dragSourceStart", "dragSourceEnd",
    # Win8.1 的**页面导航转场**族 —— 与「进出」语义最贴近的一组，全部要核对
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

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122 Safari/537.36")


def fetch(version, name):
    """下载并在本地缓存一份 js 文件，返回文本。"""
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, f"winjs-{version}-{name}")
    if os.path.exists(path) and os.path.getsize(path) > 10000:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return f.read()

    url = f"https://unpkg.com/winjs@{version}/js/{name}"
    print(f"  下载 {url} ...", flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=180) as r:
        text = r.read().decode("utf-8", errors="replace")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"    -> {len(text)} 字符，已缓存 {path}", flush=True)
    return text


def find_function_body(src, fname):
    """抓出函数体（花括号配平）。

    源码里有两种写法，都必须支持，否则会漏掉一半动画：

      A)  `pointerDown: function (element) { ... }`          —— 直接是函数
      B)  `expand: { ... execute: function (element, offset) { ... } ... }`
          —— 是个对象，真正的实现在它的 `execute` 成员上

    早期版本的本脚本只支持 A，导致 `expand` / `collapse` / `addToList` /
    `deleteFromList` / `reposition` 被误报成「各版本都未找到」。
    那 5 个「版本差异」是脚本 bug，不是真实差异。

    返回 (起始行号, 函数体文本) 或 None。
    """
    # 最可靠的锚点是每个动画实现体内的唯一 profiler 标记：
    #     writeAnimationProfilerMark("expandAnimation,StartTM");
    # 它一定在这个动画的实现体内部，而动画名本身（如 "expand"）会出现在
    # 文档注释、类名 ExpandAnimation、别的模块里，单独搜名字会搜错。
    mark = None
    for mk in (f'"{fname}Animation,StartTM"', f'"{fname},StartTM"'):
        mi = src.find(mk)
        if mi >= 0:
            mark = mk
            break
    if mark is None:
        return None

    # 从标记往前找最近的候选函数头，取两者中更靠近标记的那个：
    #   写法 A：`pointerDown: function (element) {`
    #   写法 B：`execute: function () {`（WinJS.Class.define 的 Public Members）
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
        return None
    m = max(cands, key=lambda mm: mm.start())

    start = m.end() - 1  # 指向 '{'
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
                return line, src[start:i + 1]
        i += 1
    return None


def extract_params(body):
    """从函数体里抽出所有 duration / timing / delay / from / to / scale 字面量。"""
    out = []
    for m in re.finditer(r"duration\s*:\s*([0-9]+)", body):
        out.append(("duration", m.group(1)))
    for m in re.finditer(r"timing\s*:\s*[\"']([^\"']+)[\"']", body):
        out.append(("timing", m.group(1)))
    for m in re.finditer(
        r"delay\s*:\s*(?:this\.\w+\.length\s*>\s*0\s*\?\s*([0-9]+)\s*:\s*([0-9]+)|"
        r"([0-9]+)|(staggerDelay\([^)]*\)))",
        body,
    ):
        if m.group(1) is not None:
            out.append(("delay", f"{m.group(1)}(有)/{m.group(2)}(无)"))
        elif m.group(3) is not None:
            out.append(("delay", m.group(3)))
        elif m.group(4) is not None:
            out.append(("delay", m.group(4)))
    for m in re.finditer(r"to\s*:\s*[\"']([^\"']+)[\"']", body):
        out.append(("to", m.group(1)))
    for m in re.finditer(r"from\s*:\s*[\"']([^\"']+)[\"']", body):
        out.append(("from", m.group(1)))
    for m in re.finditer(r"(scale\([0-9.,\s]+\))", body):
        out.append(("scale", m.group(1)))
    return out


def main():
    print("跨版本核对 WinJS 动画常量", flush=True)
    lines = []
    lines.append("# WinJS 跨版本常量核对\n")
    lines.append("本文件由 `docs/win8-frames/_vercheck.py` 生成。\n")
    lines.append(
        "**目的**：`docs/win8-tile-spec.md` 的常量取自 `winjs@4.4.3`（约 Windows 10 时代，"
        "2017-03 发布）。本任务的目标是 **Windows 8**，所以必须确认这些常量在更早的版本里是否一致。\n"
    )
    lines.append(
        "npm 上最早的 `winjs` 是 **3.0.0**（2014-09-16，Windows 8.1 Update / Windows Phone 8.1 时代）。"
        "**WinJS 1.0 / 2.0（Windows 8 RTM / 8.1）从未发布到 npm**，因此本次无法用 npm 通道核对 —— "
        "这是本核对的一个明确的覆盖盲区，如实记录。\n"
    )

    # 缓存全部版本
    print("\n[1/2] 下载各版本 base.js / ui.js", flush=True)
    texts = {}
    for v in VERSIONS:
        for name in ("base.js", "ui.js"):
            try:
                texts[(v, name)] = fetch(v, name)
            except Exception as e:  # noqa: BLE001
                print(f"    !! {v}/{name} 失败: {e}", flush=True)
                texts[(v, name)] = ""

    # 逐动画抽取
    print("\n[2/2] 抽取并比对", flush=True)
    table = {}  # (version, anim) -> params / None
    whereline = {}
    for v in VERSIONS:
        for anim in ANIMS:
            found = None
            for name in ("base.js", "ui.js"):
                src = texts.get((v, name), "")
                if not src:
                    continue
                r = find_function_body(src, anim)
                if r:
                    ln, body = r
                    found = extract_params(body)
                    whereline[(v, anim)] = (name, ln)
                    break
            table[(v, anim)] = found

    # 汇总为按动画分组的对比表
    header = "| 动画 | " + " | ".join(f"winjs@{v}" for v in VERSIONS) + " | 一致? |"
    sep = "|---|---|" + "---|" * (len(VERSIONS) - 1) + "---|"
    lines.append("## 逐动画对比\n")
    lines.append(header)
    lines.append(sep)

    all_same = True
    diffs = []
    for anim in ANIMS:
        cells = []
        sigs = []
        for v in VERSIONS:
            p = table.get((v, anim))
            if p is None:
                cells.append("**未找到**")
                sigs.append(None)
            else:
                # 压缩成紧凑签名
                groups = {}
                for k, val in p:
                    groups.setdefault(k, [])
                    if val not in groups[k]:
                        groups[k].append(val)
                order = ["duration", "timing", "delay", "from", "to", "scale"]
                parts = []
                for k in order:
                    if k in groups:
                        parts.append(f"{k}={'/'.join(groups[k])}")
                cells.append("<br>".join(parts))
                sigs.append(tuple((k, tuple(vals)) for k, vals in sorted(groups.items())))
        same = len(set(s for s in sigs if s is not None)) <= 1 and None not in sigs
        if not same:
            all_same = False
            diffs.append(anim)
        lines.append(f"| `{anim}` | " + " | ".join(cells) + f" | {'✅ 一致' if same else '❌ **不同**'} |")

    lines.append("")
    lines.append("## 结论\n")
    if all_same:
        lines.append(
            "**`winjs@3.0.0` / `3.0.1` / `4.4.3` 三个版本的动画常量完全一致。**\n\n"
            "这说明这套常量至少从 Windows 8.1 Update 时代（2014-09）起就冻结了，"
            "一直沿用到 Windows 10 时代的最后一个 WinJS 版本。"
            "**因此从 4.4.3 抄出来的值，对 Windows 8.1 是可靠的。**\n"
        )
    else:
        lines.append(
            "**发现版本间差异**，涉及动画：" + "、".join(f"`{a}`" for a in diffs) + "\n\n"
            "详见上表。取参数时必须注明版本。\n"
        )
    lines.append(
        "### 仍然存在的盲区\n\n"
        "**Windows 8 RTM（2012）随 Visual Studio 模板分发的 WinJS 1.0 / 2.0 仍未核对。**"
        "这两个版本没有发布到 npm，unpkg 上取不到；GitHub 在本机不可达（详见 `README.md` 的可达性表）。\n\n"
        "如果将来能取到 WinJS 1.0/2.0，把版本号加进本脚本顶部的 `VERSIONS` 列表重跑即可自动比对。\n"
    )

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"\n-> {OUT}", flush=True)

    # 控制台摘要
    print("\n摘要：", "三版本常量完全一致" if all_same else f"存在差异: {diffs}")


if __name__ == "__main__":
    main()
