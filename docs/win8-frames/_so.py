"""Fetch Stack Overflow Q+A via the Stack Exchange API (no Cloudflare) and dump HTML-stripped text."""
import json, re, html, sys, urllib.request, pathlib

IDS = [27821464, 10437327, 18611046, 17669629, 34181536]
out = pathlib.Path(__file__).with_name("_stackexchange.txt")
chunks = []


def api(path):
    url = f"https://api.stackexchange.com/2.3/{path}"
    req = urllib.request.Request(url, headers={"User-Agent": "research/1.0"})
    with urllib.request.urlopen(req, timeout=45) as r:
        return json.load(r)


def clean(h):
    h = re.sub(r"<[^>]+>", "\n", h or "")
    return re.sub(r"\n{2,}", "\n", html.unescape(h)).strip()


for qid in IDS:
    try:
        q = api(f"questions/{qid}?site=stackoverflow&filter=withbody")["items"]
    except Exception as e:
        chunks.append(f"=== Q{qid}: QUESTION FETCH FAILED: {e}\n")
        continue
    if not q:
        chunks.append(f"=== Q{qid}: not found\n")
        continue
    q = q[0]
    chunks.append(f"=== Q{qid}  [score {q.get('score')}] {q.get('title')}\nURL: {q.get('link')}\n"
                  f"--- QUESTION BODY ---\n{clean(q.get('body'))}\n")
    try:
        ans = api(f"questions/{qid}/answers?site=stackoverflow&filter=withbody&sort=votes&order=desc&pagesize=10")["items"]
    except Exception as e:
        chunks.append(f"--- ANSWERS FAILED: {e}\n")
        continue
    for a in ans:
        chunks.append(f"--- ANSWER [score {a.get('score')}, accepted={a.get('is_accepted')}] ---\n{clean(a.get('body'))}\n")

out.write_text("\n".join(chunks), encoding="utf-8")
print(f"wrote {out} ({out.stat().st_size} bytes)")
