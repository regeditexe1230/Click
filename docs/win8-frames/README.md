# `docs/win8-frames/` —— 素材来源与抓帧记录

**一句话：本目录里没有一帧真正的 Windows 8 实拍录像。** 下面说清楚为什么，以及目录里这些文件到底是什么。

---

## 1. 抓帧结论：`[实测]` 未取得

任务书要求「参考帧图片（越接近真实播放的采样越好），以及抓帧来源」。这一项**未能完成**。

原因不是没找，是这台机器**出网被封得很死**。可达性实测（curl，2026-02 于本机）：

| 目标 | 结果 |
|---|---|
| `learn.microsoft.com` | ✅ 可达 |
| `unpkg.com` / `jsdelivr` / `cdnjs` | ✅ 可达（拿到 WinJS 4.4.3 真源码） |
| `cnblogs.com`、`winaero.com` | ✅ 可达 |
| `bing.com` | ✅ 可达 |
| `github.com` | ❌ curl 返回 `000`（连不上） |
| `web.archive.org` | ❌ 返回 `000`，且 `web_fetch` 报 `TypeError: fetch failed` |
| `giphy.com` / `imgur.com` / `tenor.com` | ❌ 不可达 |
| `duckduckgo.com` / `en.wikipedia.org` | ❌ 不可达 |
| `stackoverflow.com`（直连） | ❌ HTTP 403 Cloudflare `Just a moment...`（[Stack Exchange API](https://api.stackexchange.com/2.3) 是唯一可用通道） |
| `youtube.com`（经 yt-dlp） | ❌ 进程直接挂死，无输出 |
| `tympanus.net/codrops` | ❌ Cloudflare 拦 `web_fetch`（带浏览器 UA 的 curl 可过） |

**还试过的路：**

1. **Wikimedia Commons** —— 按 `filetype:video` 搜 Windows 8 开始屏幕点击录像，203 条命中**全部无关**（多为无关的 Win8 界面截图或别的主题视频）。Commons 上没有 Win8 磁贴点击录像。
2. **本机 ffmpeg** —— **未安装**（`ffmpeg` 不是可识别命令）。即便拿到视频，也只能用 `cv2.VideoCapture` 解码，且 yt-dlp 因无法合流必须挑 progressive 格式（如 format 18）。
3. **OST 硕士论文** `http://eprints.ost.ch/278/7/HCID12Master-Win8UX-KusterGebauer.pdf` —— 下载一度 stall，后来拿到（见下），但**内容不含动画参数**。

**没有任何一项拿到真实播放采样。因此 `[实测]` 条目数为 0，本次没有编造任何实测数字。**

---

## 2. 目录内容清单（每项都说明了它是什么）

### 2.1 `sim/` —— **重建帧，不是实拍**
共 16 张 PNG，由 `docs/win8-measure.py` 用**微软官方源码常量**重建：
- `tilt-*.png`（4 张）—— `TiltEffect` 在「正中 / 右边缘中点 / 右下角 / 左上角」四种点击位置下的姿态（双轴倾斜 + Z 压深 25px）。
- `enterPage-*.png`（6 张）—— `enterPage` 1000ms 位移 + 170ms 淡入的时间序列。
- `exitPage-*.png`（6 张）—— `exitPage` 117ms 线性淡出的时间序列。

**这些帧的用途是给实现者一个可对照的几何参照，不能当作 Win8 实拍证据。**
重建参数、投影约定、以及「重建帧测量值 vs 解析值」的自校验结果见 `measure-report.md`。

### 2.2 `measure-report.md` —— 逐帧几何测量报告
由 `docs/win8-measure.py` 生成。包含缓动采样表、TiltEffect 角度/压深表、
**透视强度表（近边/远边高度比 vs 相机距离）**、宽度收缩表，以及重建帧的逐帧测量与自校验。

### 2.3 研究素材
| 文件 | 内容 |
|---|---|
| `_stackexchange.txt` | 通过 Stack Exchange API 取的 5 个相关问答全文（Q27821464 / Q10437327 / Q18611046 / Q17669629 / Q34181536）。**有效来源**，其中 Q27821464 的提问原文描述了「磁贴被按点击位置 push back」。 |
| `_src_codrops.txt` | Codrops "Designing with Motion"（Henry Modisett）全文。**对本任务价值低**：只是一般动效理论，唯一量化说法是「按钮按压反馈 magic number 在 80ms 到 200ms 之间」「不知道从哪开始就试 200ms」，**没有任何 Windows 8 磁贴的贝塞尔值或几何**。领导此前怀疑该文是 `cubic-bezier(0.1,0.9,0.2,1)` 的出处，**这个怀疑不成立**——真出处是 WinJS 发行源码。 |
| `_src_ost_thesis.pdf` / `_src_ost_thesis.txt` | OST 硕士论文 "Win8 UX"（100 页，239,151 字符，已抽文本）。**不是有效来源**：全文 `cubic`=0 次、`bezier`=0 次、`easing`=0 次、`duration`=0 次、`transition`=0 次、`tilt`=0 次、`flip`=0 次、`millisecond`=0 次；`animation/Animation` 仅 18 次且均为 Windows Runtime API 命名空间提及。**不要引用它作为动画参数出处。** |
| `_src_codrops.html` | 上面 Codrops 文的原始 HTML |
| `_strip.py` | 把 HTML 剥成纯文本的小工具：`python docs/win8-frames/_strip.py <file.html>`（写同目录 `.txt`） |
| `_so.py` | 走 Stack Exchange API 抓问答的脚本：`python docs/win8-frames/_so.py`（写 `_stackexchange.txt`） |

### 2.4 已删除的临时目录
研究期间在 `.tmpflip/ref/` 下放了两份 **WinJS 4.4.3 发行源码**（`base.js`、`ui.js`，来自
`https://unpkg.com/winjs@4.4.3/js/base.js` 与 `.../ui.js`）用于逐行核对常量。
`.tmpflip/` **不是**允许写入的范围，任务结束后已删除。
所有引用到的常量都已抄进 `docs/win8-tile-spec.md` 的常量表，**每个数字都附了 URL 与行号**，可独立复核。

---

## 3. 如果将来拿到 Win8 录像，怎么用

```powershell
# 1) 逐帧导出（无 ffmpeg，用 cv2）
#    见下方 python 片段
# 2) 用同一个测量脚本跑真帧
python docs\win8-measure.py probe <帧目录>
```

`probe` 模式与 `sim` 模式**共用同一份轮廓测量代码**（`measure_silhouette()`），
所以真帧进来的那天，量出来的数可以直接和本报告里的解析值对照。

逐帧导出片段（本机无 ffmpeg 时的替代路径）：

```python
import cv2, os
cap = cv2.VideoCapture(r"win8.mp4")
os.makedirs("frames", exist_ok=True)
i = 0
while True:
    ok, frame = cap.read()
    if not ok: break
    cv2.imwrite("frames/f%05d.png" % i, frame); i += 1
print(i, "frames")
```
