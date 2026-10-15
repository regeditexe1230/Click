#!/system/bin/sh
# On-device frame burst for the program-tile flip harness.
#
# Pushed to /data/local/tmp by tools/capture_flip.ps1 and run there, so that:
#   * every frame is timestamped ON THE DEVICE (no per-frame adb round trip),
#   * the loop costs one shell start instead of one adb call per frame.
#
# usage: sh flip_burst.sh <outdir> <count> <prefix> <pre_seconds>
#
# Timestamps come from /proc/uptime (centisecond resolution, monotonic, always
# available); `date +%s%3N` is used as a fallback on the rare build where
# /proc/uptime is unreadable.
#
# Output on stdout, one line per frame:
#   FRAME <n> <uptime_ms_start> <uptime_ms_end> <dur_ms> <bytes>
# and an index file <outdir>/<prefix>_index.txt with the same rows.

OUT="$1"
COUNT="$2"
PREFIX="${3:-f}"
PRE="${4:-0}"

if [ -z "$OUT" ] || [ -z "$COUNT" ]; then
  echo "usage: sh flip_burst.sh <outdir> <count> <prefix> <pre_seconds>" >&2
  exit 2
fi

now_ms() {
  # /proc/uptime -> "123456.78 987.65" (seconds since boot, 2 decimals)
  u=$(cut -d' ' -f1 /proc/uptime 2>/dev/null)
  if [ -n "$u" ]; then
    s=${u%.*}
    f=${u#*.}
    [ "$f" = "$u" ] && f=00
    f=$(printf '%-2s' "$f" | tr ' ' 0 | cut -c1-2)
    echo "$((s * 1000 + f * 10))"
    return
  fi
  d=$(date +%s%3N 2>/dev/null)
  case "$d" in
    *N) echo "$(( $(date +%s) * 1000 ))" ;;
    *)  echo "$d" ;;
  esac
}

mkdir -p "$OUT" || exit 3
rm -f "$OUT"/${PREFIX}_[0-9]*.png "$OUT"/${PREFIX}_index.txt
IDX="$OUT/${PREFIX}_index.txt"

# Optional pre-roll: sit still for a moment before the first frame so the host
# can start the burst, then tap, without adb latency eating the first frames.
if [ "$PRE" != "0" ]; then
  sleep "$PRE"
fi

i=0
while [ "$i" -lt "$COUNT" ]; do
  i=$((i + 1))
  n=$(printf '%03d' "$i")
  p="$OUT/${PREFIX}_${n}.png"
  t0=$(now_ms)
  screencap -p "$p"
  t1=$(now_ms)
  b=$(wc -c < "$p" 2>/dev/null || echo 0)
  row="FRAME $i $t0 $t1 $((t1 - t0)) $b"
  echo "$row" >> "$IDX"
  echo "$row"
done

echo "DONE $i $(now_ms)"
