# Flowstead — paces a narration for the video: slower (tempo, pitch kept) with a pause after each sentence and a longer
# one between chapters, and the transcript's times moved to match (with the old → new time map, for the recorder's cues).
# SPDX-License-Identifier: GPL-3.0-or-later
#
#   python3 -I web/tools/episodes/pace.py <audio> <transcript.json> <out audio.wav> <out transcript.json> [tempo] [sentence gap s] [chapter gap s]
import json, re, subprocess, sys

audio, tpath, out_audio, out_t = sys.argv[1:5]
tempo = float(sys.argv[5]) if len(sys.argv) > 5 else 0.9
gap = float(sys.argv[6]) if len(sys.argv) > 6 else 0.6
chapter_gap = float(sys.argv[7]) if len(sys.argv) > 7 else 1.5
T = json.load(open(tpath))
words = T["words"]
starts = [c["start"] for c in T["chapters"]]
dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", audio], capture_output=True, text=True, check=True).stdout)

# Cut points: halfway through the silence after each sentence's last word.
cuts = []
for i, w in enumerate(words[:-1]):
    if re.search(r"[.?!]$", w["w"]):
        cuts.append(((w["e"] + words[i + 1]["s"]) / 2, words[i + 1]["s"] in starts or any(abs(words[i + 1]["s"] - s) < 0.05 for s in starts)))
bounds = [0.0] + [c for c, _ in cuts] + [dur]
pauses = [chapter_gap if ch else gap for _, ch in cuts] + [0.0]

# The time map: each piece [old start, old end] → new start, played at [tempo].
pieces, t_new = [], 0.0
for k in range(len(bounds) - 1):
    a, b = bounds[k], bounds[k + 1]
    pieces.append({"old": [a, b], "new": t_new})
    t_new += (b - a) / tempo + pauses[k]


def remap(t):
    for p in pieces:
        if p["old"][0] - 1e-9 <= t <= p["old"][1] + 1e-9:
            return p["new"] + (t - p["old"][0]) / tempo
    return pieces[-1]["new"] + (t - pieces[-1]["old"][0]) / tempo


# Audio: each piece slowed (atempo keeps the pitch), then its pause.
fl, labels = [], []
for k, p in enumerate(pieces):
    a, b = p["old"]
    fl.append(f"[0:a]atrim={a:.4f}:{b:.4f},asetpts=PTS-STARTPTS,atempo={tempo},apad=pad_dur={pauses[k]:.3f}[p{k}]")
    labels.append(f"[p{k}]")
fl.append("".join(labels) + f"concat=n={len(pieces)}:v=0:a=1[out]")
subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", audio, "-filter_complex", ";".join(fl), "-map", "[out]", "-ar", "48000", "-ac", "1", out_audio], check=True)

T2 = dict(T)
T2["words"] = [dict(w, s=round(remap(w["s"]), 3), e=round(remap(w["e"]), 3)) for w in words]
T2["chapters"] = [dict(c, start=round(remap(c["start"]), 3), end=round(remap(c["end"]), 3) if c.get("end") is not None else None) for c in T["chapters"]]
T2["timeMap"] = {"tempo": tempo, "pieces": pieces}
json.dump(T2, open(out_t, "w"), indent=1)
print(f"{dur:.1f} s → {t_new:.1f} s ({len(cuts)} sentence pauses)")
