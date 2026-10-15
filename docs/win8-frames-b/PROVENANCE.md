# win8-frames-b/ —— 参考帧来源与说明

本目录是 `win8-ref-b` 独立采集的参考帧（B 组证据），对应 `docs/win8-tile-spec-B.md`。

## 1. 源头

| 项目 | 值 |
|---|---|
| 页面 | <https://www.bilibili.com/video/BV1Us411f7bc> |
| 标题 | `windows8.1的动画有多好_哔哩哔哩_bilibili`（由页面 `document.title` 读取确认） |
| 播放器分辨率 | **1280 × 720** |
| 视频总时长 | **345.812833 s** |
| **真实帧率** | **30 fps**（33.33 ms/帧）—— 实测，见下 |
| 视频文件 CDN | `upos-sz-mirrorali.bilivideo.com`（该 CDN 带 CORS 响应头） |

### 帧率的测法（不是猜的）

在页面上下文里把 `video.currentTime = 200`，然后用 `requestVideoFrameCallback` 在**播放中**连续采集 3 秒：

```
88 帧 / 2.9 s，相邻帧 mediaTime 差值：34, 33, 33, 34, 33, 33, … ms
⇒ 帧间隔 33.33 ms ⇒ 30 fps
```

所有跨帧时间都用 **0.0333 s/帧** 换算。

## 2. 抓帧管线（为什么不用 ffmpeg）

本机（`C:\Users\33947\Desktop\Android项目\click`）**没有 `ffmpeg`**，`imageio_ffmpeg` / `av` 也都没装；从 shell 直接联网被完全阻断。所以帧是这样落盘的：

1. 在页面里把 `<video>` 重建为 `crossOrigin = 'anonymous'` 版本（先用
   `fetch(v.src, {headers:{Range:'bytes=0-1024'}, mode:'cors'})` 拿到 **HTTP 206 + `content-type: video/mp4`**，确认该 CDN 允许跨域）。
   —— 不这样做的话 `canvas.getImageData()` 会抛
   `SecurityError: Failed to execute 'getImageData' … The canvas has been tainted by cross-origin data.`
2. `seek` → `drawImage` 到 1280×720 canvas → `canvas.toBlob(res, 'image/png')`。
3. `fetch('http://127.0.0.1:8765/?name=<名字>.png', {method:'POST', body:blob})`，
   由本机一个 Python `http.server`（`recv_frame.py`，带 `Access-Control-Allow-Origin: *` 与
   `Access-Control-Allow-Private-Network: true`）写盘。

一次 seek + draw + POST 实测约 23 ms。

## 3. 本目录中的帧

**窗口：t = 196.300 s → 199.600 s（1/30 s 一帧，共 100 帧的原始序列）**

这里保留了其中 **25 帧**（等间隔 + 关键时刻全留），命名格式：

```
p_<7位毫秒>.png      例如 p_0196300.png = t = 196.300 s
```

| 文件 | t (ms) | 阶段 |
|---|---|---|
| `p_0196300.png` | 196300 | **参考帧**（量测基准）。Win8 开始屏幕，25 个磁贴 |
| `p_0196467.png` | 196467 | 右上角白色方块消失 |
| `p_0196600.png` | 196600 | 鼠标移动中 |
| `p_0196767.png` | 196767 | 鼠标移动中 |
| `p_0196933.png` | 196933 | 鼠标移动中 |
| `p_0197100.png` | 197100 | 鼠标移动中 |
| `p_0197267.png` | 197267 | 鼠标移动中 |
| `p_0197433.png` | 197433 | 鼠标移动中 |
| `p_0197567.png` | 197567 | 鼠标移动中 |
| `p_0197700.png` | 197700 | **整屏切换前的最后一帧**（磁贴仍然完好） |
| `p_0197733.png` | 197733 | **整屏硬切第一帧**（变化像素 5569 → 235862） |
| `p_0197767.png` | 197767 | 切换中 |
| `p_0197800.png` | 197800 | 切换中 |
| `p_0197833.png` | 197833 | 切换中 |
| `p_0197867.png` | 197867 | 切换中（可测磁贴数 = 0） |
| `p_0197900.png` | 197900 | 切换后 |
| `p_0197933.png` | 197933 | 切换后 |
| `p_0197967.png` | 197967 | 切换后 |
| `p_0198000.png` | 198000 | 切换后：满屏小块网格 |
| `p_0198100.png` | 198100 | 收敛中 |
| `p_0198300.png` | 198300 | 收敛中 |
| `p_0198600.png` | 198600 | 收敛中 |
| `p_0199000.png` | 199000 | 稳定 |
| `p_0199300.png` | 199300 | 稳定 |
| `p_0199600.png` | 199600 | 稳定 |

## 4. 量测与结论

脚本：`docs/win8-measure-b.py`
输出：`docs/win8-measure-b.out.txt`（608 行，6 张表，另存了一份在 `C:\Users\33947\Downloads\win8b\measure-out.txt`）

**核心结论：t = 196.300 → 197.700 这 43 帧（1.433 s）里，磁贴宽度比恒为 1.0000（最坏 0.9880 = 1 个像素的抗锯齿抖动），近/远边高比 ≈ 1.01~1.03，磁贴一帧都没有变形。** 随后 t = 197.733 整屏一次性切换。

⇒ **这段素材里没有可测的磁贴按压动画。** 详见 `docs/win8-tile-spec-B.md` §4。

## 5. 重要限制

- **本机没有可用的视觉模型**（`modlens_read_image` 返回
  `Error: No vision provider is set up on this machine … `），所以本目录的 PNG **没有被人或模型目视看过**。
  上面所有关于画面内容的描述都来自 Python/numpy 数值分析与 ASCII 色彩渲染。
  如果需要对帧做目视确认，需要另外配置视觉模型或人工看图。
- bilibili 的播放地址是**带时效的 CDN 直链**，本目录的帧是**抓取当时**的画面，作为证据足够了，但不保证与当前线上的视频逐帧一致（若 UP 主更换了源）。
- 帧是 PNG 无损，尺寸就是 1280×720 原始分辨率，**没有二次缩放**。
