#!/usr/bin/env python3
"""Generate zh-CN narration with edge-tts + word timings → audio_meta.json (PL shape).

Voices are trimmed (head silence kept at 60 ms, tail at 140 ms) so lines land
tight after each frame transition; word timings shift with the head trim.
BGM + SFX entries in an existing audio_meta.json are preserved untouched —
this script only ever replaces the `voices` array.
"""
import asyncio, json, subprocess, sys, re
from pathlib import Path

# project root = the directory containing this script's parent (scripts live in the project root)
PROJECT = Path(__file__).resolve().parent
VOICE = "zh-CN-YunjianNeural"
RATE = "-4%"
HEAD_KEEP = 0.06   # seconds of leading silence kept (breath before the line)
TAIL_KEEP = 0.14   # seconds of trailing silence kept (release after the line)
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

def ffprobe_dur(path):
    p = subprocess.run(["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
                        "-of", "csv=p=0", str(path)], capture_output=True, text=True)
    return float(p.stdout.strip())

def leading_silence(path):
    """Seconds of silence before the first sound (ffmpeg silencedetect, -45 dB)."""
    p = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostats", "-i", str(path),
         "-af", "silencedetect=noise=-45dB:d=0.02", "-f", "null", "-"],
        capture_output=True, text=True)
    m = re.search(r"silence_end:\s*([0-9.]+)", p.stderr)
    return float(m.group(1)) if m else 0.0

def trim(path, head_keep, tail_keep):
    tmp = path.with_suffix(".trim.mp3")
    af = (f"silenceremove=start_periods=1:start_threshold=-45dB:start_silence={head_keep},"
          f"areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence={tail_keep},areverse")
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(path), "-af", af,
                    "-c:a", "libmp3lame", "-q:a", "2", str(tmp)], check=True)
    tmp.replace(path)

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
    # trim head/tail silence; shift word timings by the removed head
    head = leading_silence(out)
    head_cut = max(0.0, head - HEAD_KEEP)
    trim(out, HEAD_KEEP, TAIL_KEEP)
    dur = ffprobe_dur(out)
    return {"frame": item["frame"], "path": f"assets/voice/{out.name}",
            "duration_s": round(dur, 3),
            "words": [{"id": i, "text": w["text"],
                       "start": round(max(0.0, w["start"] - head_cut), 3),
                       "end": round(max(0.0, w["end"] - head_cut), 3)}
                      for i, w in enumerate(words)]}

async def main():
    # optional frame filter: `python3 scripts-edge-tts.py 2 3 6` regenerates ONLY those
    # frames, preserving every other voice entry (audio + word timings) untouched
    only = {int(a) for a in sys.argv[1:] if a.isdigit()} or None
    voices = []
    for item in lines:
        if only and item["frame"] not in only:
            continue
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
    # preserve BGM + SFX from the existing meta; only the voices array is regenerated
    meta_path = PROJECT / "audio_meta.json"
    prev = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else {}
    if only:
        kept = {v["frame"]: v for v in prev.get("voices", []) if v["frame"] not in only}
        merged = {v["frame"]: v for v in voices}
        merged.update(kept)
        voices = [merged[k] for k in sorted(merged)]
    meta = {"bgm": prev.get("bgm"), "bgm_pending": False,
            "voices": voices, "sfx": prev.get("sfx", [])}
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    total = sum(v["duration_s"] for v in voices)
    print(f"OK: {len(voices)} voices, total {total:.1f}s → audio_meta.json")

asyncio.run(main())
