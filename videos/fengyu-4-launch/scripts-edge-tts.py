#!/usr/bin/env python3
"""Generate zh-CN narration with edge-tts + word timings → audio_meta.json (PL shape)."""
import asyncio, json, subprocess, sys, re
from pathlib import Path

# project root = the directory containing this script's parent (scripts live in the project root)
PROJECT = Path(__file__).resolve().parent
VOICE = "zh-CN-XiaoxiaoNeural"
RATE = "-4%"
SCRIPT = (PROJECT / "SCRIPT.md").read_text(encoding="utf-8")

# parse SCRIPT.md: "## Line N — label (Frame N)" + indented spoken text
lines = []
cur = None
for raw in SCRIPT.splitlines():
    h = re.match(r"^#{2,3}\s+.*?\(Frame\s+(\d+)\)", raw, re.I)
    if h:
        if cur: lines.append(cur)
        cur = {"frame": int(h.group(1)), "text": ""}
        continue
    if cur is None: continue
    if raw.strip().startswith("**"): continue
    m = re.match(r"^(?: {4,}|\t)(.+)$", raw)
    if m: cur["text"] += m.group(1).strip()
if cur: lines.append(cur)

import edge_tts

async def gen(item):
    out = PROJECT / "assets" / "voice" / f"{item['frame']:02d}.mp3"
    out.parent.mkdir(parents=True, exist_ok=True)
    words = []
    comm = edge_tts.Communicate(item["text"], VOICE, rate=RATE, boundary="WordBoundary")
    with open(out, "wb") as f:
        async for chunk in comm.stream():
            if chunk["type"] == "audio":
                f.write(chunk["data"])
            elif chunk["type"] == "WordBoundary":
                words.append({
                    "text": chunk["text"],
                    "start": chunk["offset"] / 1e7,
                    "end": (chunk["offset"] + chunk["duration"]) / 1e7,
                })
    # duration from the file itself
    p = subprocess.run(["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
                        "-of", "csv=p=0", str(out)], capture_output=True, text=True)
    dur = float(p.stdout.strip())
    return {"frame": item["frame"], "path": f"assets/voice/{out.name}",
            "duration_s": round(dur, 3),
            "words": [{"id": i, "text": w["text"], "start": round(w["start"], 3),
                       "end": round(w["end"], 3)} for i, w in enumerate(words)]}

async def main():
    voices = []
    for item in lines:
        for attempt in range(3):
            try:
                v = await gen(item)
                voices.append(v)
                print(f"frame {v['frame']}: {v['duration_s']}s, {len(v['words'])} words")
                break
            except Exception as e:
                print(f"frame {item['frame']} attempt {attempt+1} failed: {e}", file=sys.stderr)
                await asyncio.sleep(2)
        else:
            print(f"FAILED frame {item['frame']}", file=sys.stderr)
    voices.sort(key=lambda v: v["frame"])
    meta = {"bgm": None, "bgm_pending": False, "voices": voices, "sfx": []}
    (PROJECT / "audio_meta.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    total = sum(v["duration_s"] for v in voices)
    print(f"OK: {len(voices)} voices, total {total:.1f}s → audio_meta.json")

asyncio.run(main())
