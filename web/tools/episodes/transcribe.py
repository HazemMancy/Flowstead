# Flowstead — the narration's words and their times (faster-whisper), to check the read against the script and to cut
# the video to it. SPDX-License-Identifier: GPL-3.0-or-later
#   python3 -I web/tools/episodes/transcribe.py <audio> <script.txt> <out.json>
import json, re, sys, difflib
from faster_whisper import WhisperModel

audio, script_path, out = sys.argv[1:4]
model = WhisperModel("medium.en", device="cpu", compute_type="int8")
segs, _ = model.transcribe(audio, word_timestamps=True, language="en", beam_size=5, vad_filter=False)
words = [{"w": w.word.strip(), "s": round(w.start, 3), "e": round(w.end, 3)} for s in segs for w in s.words]

norm = lambda t: re.sub(r"[^a-z0-9]+", "", t.lower())
script = open(script_path).read()
paras = [p.split() for p in script.strip().split("\n\n")]
flat = [(i, w) for i, p in enumerate(paras) for w in p]
a = [norm(w) for _, w in flat]
b = [norm(w["w"]) for w in words]
sm = difflib.SequenceMatcher(a=a, b=b, autojunk=False)
diffs = [{"op": op, "script": " ".join(w for _, w in flat[i1:i2]), "heard": " ".join(w["w"] for w in words[j1:j2]),
          "at": words[j1]["s"] if j1 < len(words) else None} for op, i1, i2, j1, j2 in sm.get_opcodes() if op != "equal"]
# Each paragraph's first and last heard word.
starts, ends = {}, {}
for blk in sm.get_matching_blocks():
    for k in range(blk.size):
        p = flat[blk.a + k][0]
        t = words[blk.b + k]
        starts.setdefault(p, t["s"]); ends[p] = t["e"]
chapters = [{"paragraph": p + 1, "start": starts.get(p), "end": ends.get(p), "first": " ".join(paras[p][:4])} for p in range(len(paras))]
json.dump({"words": words, "diffs": diffs, "chapters": chapters}, open(out, "w"), indent=1)
print(f"{len(words)} words heard, {len(a)} in the script, {len(diffs)} differences")
for d in diffs: print(f"  {d['op']:8s} at {d['at']}: script «{d['script']}» heard «{d['heard']}»")
for c in chapters: print(f"  ¶{c['paragraph']:2d} {c['start']}–{c['end']}  {c['first']}")
