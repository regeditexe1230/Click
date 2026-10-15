#!/usr/bin/env python3
"""Extract frames + a real-time index from a screenrecord mp4 (or a png burst).

Why this exists: `ffmpeg` is not installed on the capture host, but OpenCV is,
and cv2 exposes the *per-frame presentation timestamp* (`CAP_PROP_POS_MSEC`),
which is what matters because `screenrecord` writes variable-frame-rate video
(a nominal `fps` from the container would silently mis-time every frame).

Frame timing is therefore always taken from the frame's own pts, never from
`index / fps`.

usage:
  python tools/video_to_frames.py --video flip.mp4 --outdir <dir> [--start-ms 0] [--max-frames N]
  python tools/video_to_frames.py --png-dir <dir> --index <prefix>_index.txt --outdir <dir>

writes <outdir>/frames/f_000001.png ... and <outdir>/frames.csv with columns:
  frame, t_ms, file, source
where t_ms is relative to the first extracted frame (the tap offset is added by
the caller if it needs an absolute timeline).
"""
from __future__ import annotations

import argparse
import csv
import os
import shutil
import subprocess
import sys


def die(msg: str, code: int = 2):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(code)


def extract_with_cv2(video: str, outdir: str, start_ms: float, max_frames: int):
    import cv2  # noqa: WPS433  (optional import on purpose)

    cap = cv2.VideoCapture(video)
    if not cap.isOpened():
        die("cv2 could not open %s" % video)
    frames = []
    idx = 0
    written = 0
    while True:
        ok = cap.grab()
        if not ok:
            break
        pts = cap.get(cv2.CAP_PROP_POS_MSEC)
        idx += 1
        if pts < start_ms:
            continue
        ok, frame = cap.retrieve()
        if not ok:
            break
        written += 1
        name = "f_%06d.png" % written
        path = os.path.join(outdir, "frames", name)
        # NOTE: cv2.imwrite() silently fails on non-ASCII paths (this repo lives under
        # "...\Android项目\click"), so encode in memory and write the bytes ourselves.
        ok, buf = cv2.imencode(".png", frame)
        if not ok:
            raise SystemExit("cv2.imencode failed for %s" % path)
        buf.tofile(path)
        frames.append((written, pts - start_ms, os.path.join("frames", name), "video"))
        if max_frames and written >= max_frames:
            break
    cap.release()
    nominal = 0.0
    try:
        cap2 = cv2.VideoCapture(video)
        nominal = cap2.get(cv2.CAP_PROP_FPS) or 0.0
        cap2.release()
    except Exception:
        pass
    return frames, {"nominal_fps": nominal, "scanned": idx}


def extract_with_ffmpeg(video: str, outdir: str, start_ms: float, max_frames: int):
    ff = shutil.which("ffmpeg")
    if not ff:
        die("no ffmpeg on PATH and cv2 unavailable -> cannot decode %s" % video)
    pattern = os.path.join(outdir, "frames", "f_%06d.png")
    cmd = [ff, "-hide_banner", "-loglevel", "error", "-i", video]
    if start_ms:
        cmd += ["-ss", "%.3f" % (start_ms / 1000.0)]
    if max_frames:
        cmd += ["-frames:v", str(max_frames)]
    cmd += ["-vsync", "0", pattern]
    subprocess.run(cmd, check=True)
    names = sorted(os.listdir(os.path.join(outdir, "frames")))
    frames = [(i + 1, 0.0, os.path.join("frames", n), "video") for i, n in enumerate(names)]
    return frames, {"nominal_fps": 0.0, "scanned": len(names), "note": "ffmpeg path has no pts index"}


def extract_from_burst(png_dir: str, index_file: str, outdir: str, max_frames: int):
    """burst mode: the device already wrote real timestamps, just normalise."""
    rows = []
    with open(index_file, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            parts = line.split()
            if not parts or parts[0] != "FRAME" or len(parts) < 4:
                continue
            rows.append((int(parts[1]), int(parts[2]), int(parts[3])))
    if not rows:
        die("no FRAME rows in %s" % index_file)
    rows.sort()
    t0 = rows[0][1]
    frames = []
    for _n, t_start, _dur in rows[: max_frames or None]:
        src = os.path.join(png_dir, "f_%03d.png" % _n)
        if not os.path.exists(src):
            continue
        dst_name = "f_%06d.png" % (len(frames) + 1)
        shutil.copyfile(src, os.path.join(outdir, "frames", dst_name))
        frames.append((len(frames) + 1, t_start - t0, os.path.join("frames", dst_name), "burst"))
    deltas = [round((rows[i][1] - rows[i - 1][1]) / 1000.0, 3) for i in range(1, len(rows))]
    mean_gap = sum(deltas) / len(deltas) if deltas else 0.0
    return frames, {
        "nominal_fps": round(1.0 / mean_gap, 2) if mean_gap else 0.0,
        "scanned": len(rows),
        "frame_gap_s": deltas,
    }


def main():
    ap = argparse.ArgumentParser(description="frames + real timestamps from a device capture")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--video", help="mp4 written by screenrecord")
    src.add_argument("--png-dir", help="directory of png frames (burst mode)")
    ap.add_argument("--index", help="burst index file (FRAME n t0 t1 dur bytes)")
    ap.add_argument("--outdir", required=True)
    ap.add_argument("--start-ms", type=float, default=0.0, help="skip frames before this pts")
    ap.add_argument("--max-frames", type=int, default=0)
    ap.add_argument("--prefer", choices=["cv2", "ffmpeg"], default="cv2")
    args = ap.parse_args()

    os.makedirs(os.path.join(args.outdir, "frames"), exist_ok=True)
    info = {}
    if args.png_dir:
        if not args.index:
            die("--png-dir requires --index")
        frames, info = extract_from_burst(args.png_dir, args.index, args.outdir, args.max_frames)
    else:
        use_cv2 = args.prefer == "cv2"
        if use_cv2:
            try:
                import cv2  # noqa: F401
            except Exception:
                use_cv2 = False
        if use_cv2:
            frames, info = extract_with_cv2(args.video, args.outdir, args.start_ms, args.max_frames)
        else:
            frames, info = extract_with_ffmpeg(args.video, args.outdir, args.start_ms, args.max_frames)

    if not frames:
        die("no frames extracted")

    csv_path = os.path.join(args.outdir, "frames.csv")
    with open(csv_path, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["frame", "t_ms", "file", "source"])
        for i, t_ms, path, source in frames:
            w.writerow([i, "%.1f" % t_ms, path, source])

    gaps = [round(frames[i][1] - frames[i - 1][1], 1) for i in range(1, len(frames))]
    m = len(gaps) and sum(gaps) / len(gaps) or 0
    print("frames=%d  t_range=%.0f..%.0f ms  mean_gap=%.1f ms (%.1f fps)"
          % (len(frames), frames[0][1], frames[-1][1], m, (1000.0 / m) if m else 0))
    print("nominal_fps=%s  scanned=%s" % (info.get("nominal_fps"), info.get("scanned")))
    if "frame_gap_s" in info:
        print("burst gaps (s): %s" % info["frame_gap_s"][:40])
    print("wrote %s" % csv_path)


if __name__ == "__main__":
    main()
