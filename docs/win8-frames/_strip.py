"""Strip HTML from a saved page and dump plain text for grepping."""
import re, html, sys, pathlib

src = pathlib.Path(sys.argv[1])
dst = src.with_suffix(".txt")
s = src.read_text(encoding="utf-8", errors="replace")

# try to grab the article body
m = re.search(r'<div[^>]*class="[^"]*(?:entry-content|post-content|article-content)[^"]*"[^>]*>(.*)', s, re.S)
body = m.group(1) if m else s

body = re.sub(r"<script.*?</script>", " ", body, flags=re.S)
body = re.sub(r"<style.*?</style>", " ", body, flags=re.S)
body = re.sub(r"<[^>]+>", " ", body)
body = html.unescape(body)
body = re.sub(r"[ \t\r]+", " ", body)
body = re.sub(r"\n\s*\n+", "\n", body)
dst.write_text(body, encoding="utf-8")
print(f"{src.name} -> {dst.name}  {len(body)} chars")
