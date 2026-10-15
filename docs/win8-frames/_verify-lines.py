"""校验本目录文档里引用的 WinJS 源码行号是否真的指向那段代码。

**为什么必须有这个脚本**：缓存里的 WinJS 源码是 `\\r\\r\\n` 结尾
（下载时被文本模式二次转换）。PowerShell 的 `Select-String` 和 Python 的
universal newlines **都会把 `\\r\\r\\n` 当成两个换行**，于是数出来的行号
整整翻一倍。`winjs-transitions.txt` 第一版就把 `turnstileForwardIn`
（真实 L17934）标成了 L35867 —— 文档里给错行号比不给更糟，因为读的人
grep 不到就会认为整份文档在编。

所以：**任何引用源码行号的地方，都应该能被这个脚本验一遍。**

用法：
    python docs\\win8-frames\\_verify-lines.py

退出码 0 = 全部命中；1 = 有行号对不上（会把期望/实际都打出来）。
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "_vercheck_cache")
VERSION = "4.4.3"


def normalize(txt):
    """把源码统一成 LF 换行。**必须先处理 `\\r\\r\\n`**，理由见模块 docstring。"""
    txt = txt.replace("\r\r\n", "\n")
    txt = txt.replace("\r\n", "\n")
    return txt.replace("\r", "\n")


def load(name):
    with open(os.path.join(CACHE, name), "rb") as f:
        raw = f.read().decode("utf-8", errors="replace")
    lines = normalize(raw).split("\n")
    while lines and lines[-1] == "":
        lines.pop()
    return lines


# (期望行号, 该行必须包含的子串, 出处文档)
# 期望值都不是手写的：是先用 normalize() 数出来、再逐条回读确认过的。
CLAIMS = [
    (16103, "function staggerDelay(initialDelay, extraDelay, delayFactor, delayCap) {", "spec §1.5"),
    (16191, "function animRotationTransform(elemArray, origins, transition) {", "spec §10.2"),
    (16204, "function clearAnimRotationTransform(elemArray) {", "spec §10.2"),
    (16251, "function collectTurnstileTransformOrigins(elements) {", "spec §10.2"),
    (16255, "var offsetLeftLTR = -(40 + itemBoundingBox.left);", "spec §10.2"),
    (16257, "var totalOffsetY = ((_Global.innerHeight / 2) - itemBoundingBox.top);", "spec §10.2"),
    (16288, 'timing: "cubic-bezier(0.1, 0.9, 0.2, 1)",', "spec §1.2 首个命中"),
    (16345, "var RepositionAnimation = _Base.Class.define(", "spec §1.3 reposition"),
    (16368, "var AddToListAnimation = _Base.Class.define(", "spec §1.3 addToList"),
    (16415, "var DeleteFromListAnimation = _Base.Class.define(", "spec §1.3 deleteFromList"),
    (16430, 'timing: "cubic-bezier(0.11, 0.5, 0.24, .96)",', "spec §1.2 退场曲线 #1"),
    (16481, 'timing: "cubic-bezier(0.11, 0.5, 0.24, .96)",', "spec §1.2 退场曲线 #2"),
    (16740, "createExpandAnimation: function (revealed, affected) {", "spec §1.4 expand"),
    (16763, "createCollapseAnimation: function (hidden, affected) {", "spec §1.4 collapse"),
    (17211, "pointerDown: function (element) {", "spec §1.2"),
    (17235, 'timing: "cubic-bezier(0.1, 0.9, 0.2, 1)",', "spec §3.5 pointerDown 曲线"),
    (17241, "pointerUp: function (element) {", "spec §1.2"),
    (17271, "dragSourceStart: function (dragSource, affected) {", "spec §1.2"),
    (17427, 'var offsetArray = new OffsetArray(offset, "WinJS-enterContent", [{ top: "28px", left: "0px", rtlflip: false }]);', "spec §8.4 更正"),
    (17474, "exitContent: function (outgoing, offset) {", "spec §1.2"),
    (17726, "enterPage: function (element, offset) {", "spec §1.2"),
    (17776, "exitPage: function (outgoing, offset) {", "spec §1.2"),
    (17934, "turnstileForwardIn: function (incomingElements) {", "spec §10.1"),
    (17958, 'from: "perspective(600px) rotateY(80deg)",', "spec §10.1"),
    (17972, "turnstileForwardOut: function (outgoingElements) {", "spec §10.1"),
    (17997, 'to: "perspective(600px) rotateY(-50deg)",', "spec §10.1"),
    (18010, "turnstileBackwardIn: function (incomingElements) {", "spec §10.1"),
    (18034, 'from: "perspective(600px) rotateY(-50deg)",', "spec §10.1"),
    (18048, "turnstileBackwardOut: function (outgoingElements) {", "spec §10.1"),
    (18072, 'from: "perspective(800px) rotateY(0deg)",', "spec §10.1"),
    (15419, 'PageNavigationAnimation.turnstile', "spec §10.5 注册名文档注释"),
    (15422, 'turnstile: "turnstile",', "spec §10.5 注册名"),
]

# 出现次数也是结论，一并校验。
# **必须用宽松正则**：WinJS 里同一条曲线有两种写法（带空格 / 不带空格），
# 直接 grep 带空格的字符串只会数到 49（base.js 44 + ui.js 5），
# 而文档里的口径是 60（base.js 52 + ui.js 8）。见 spec §1.2 的「计数口径」注。
CURVE_MAIN = r"cubic-bezier\(\s*0\.1\s*,\s*0\.9\s*,\s*0\.2\s*,\s*1\s*\)"
CURVE_OUT = r"cubic-bezier\(\s*0\.11\s*,\s*0\.5\s*,\s*0\.24\s*,\s*\.?96\s*\)"

COUNTS = [
    (CURVE_MAIN, 52, "base.js 招牌曲线出现次数（spec §6.1）"),
    (CURVE_OUT, 2, "base.js 退场专用曲线（spec §6.2）"),
]


def main():
    print(f"WinJS {VERSION} —— 行号与出现次数校验")
    print(f"缓存目录：{CACHE}\n")

    base = load(f"winjs-{VERSION}-base.js")
    ui = load(f"winjs-{VERSION}-ui.js")

    # 先自证归一化是对的：如果有 \r\r\n 没被处理，行数会翻倍
    with open(os.path.join(CACHE, f"winjs-{VERSION}-base.js"), "rb") as f:
        raw = f.read()
    print(f"base.js：{len(raw):,} 字节，CRLF={raw.count(b'\\r\\n'):,}，"
          f"归一化后 {len(base):,} 行")
    print(f"ui.js  ：归一化后 {len(ui):,} 行\n")

    fails = 0
    for ln, needle, src in CLAIMS:
        if not (1 <= ln <= len(base)):
            print(f"[越界] L{ln}  {src}")
            fails += 1
            continue
        actual = base[ln - 1]
        if needle in actual:
            print(f"[ OK ] L{ln:<6} {src}")
        else:
            fails += 1
            print(f"[FAIL] L{ln:<6} {src}")
            print(f"        期望包含：{needle}")
            print(f"        实际内容：{actual.strip()[:150]}")

    print()
    for pattern, want, label in COUNTS:
        rx = re.compile(pattern)
        got = sum(1 for l in base if rx.search(l))
        mark = "OK " if got == want else "FAIL"
        if got != want:
            fails += 1
        print(f"[{mark}] base.js 出现 {got} 次（期望 {want}）—— {label}")

    rx_main = re.compile(CURVE_MAIN)
    rx_out = re.compile(CURVE_OUT)
    u = sum(1 for l in ui if rx_main.search(l))
    print(f"[{'OK ' if u == 8 else 'FAIL'}] ui.js   出现 {u} 次（期望 8）—— spec §6.1")
    if u != 8:
        fails += 1

    o = sum(1 for l in ui if rx_out.search(l))
    print(f"[{'OK ' if o == 0 else 'FAIL'}] ui.js   出现 {o} 次（期望 0）—— 退场曲线只在 base.js")
    if o != 0:
        fails += 1

    # 顺带把「直接 grep 带空格串会数错」这件事量化出来，免得下一个人再踩
    strict = sum(1 for l in base if "cubic-bezier(0.1, 0.9, 0.2, 1)" in l)
    print(f"\n[提示] 同一条曲线，直接 grep 带空格串只能数到 base.js {strict} 处"
          f"（宽松正则 52 处）—— 差的都是不带空格写法，不是文档写错。")

    print()
    if fails:
        print(f"共 {fails} 项对不上 —— 文档里的行号需要更正。")
        return 1
    print(f"全部 {len(CLAIMS)} 条行号 + {len(COUNTS) + 2} 项计数校验通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
