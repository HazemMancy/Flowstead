# Flowstead — cuts a "Getting started" episode: each recorded chapter timed to its narration, captions from the
# script's own words (timed by the transcript), the narration as the soundtrack; a 16:9 master and a 4:5 cut.
# SPDX-License-Identifier: GPL-3.0-or-later
#
#   python3 -I web/tools/episodes/compose.py <recording dir> <transcript.json> <narration audio> <script.txt> <out dir> <title>
import difflib, json, os, re, subprocess, sys

rec, transcript_path, audio, script_path, out, title = sys.argv[1:7]
os.makedirs(out, exist_ok=True)
T = json.load(open(transcript_path))
words = T["words"]
starts = [c["start"] for c in T["chapters"]]
total = words[-1]["e"] + 0.8
ends = starts[1:] + [total]
FPS = 30


def run(*args):
    subprocess.run(args, check=True)


# ---------- the picture: each chapter as long as its narration ----------
parts = []
for i, (s, e) in enumerate(zip(starts, ends)):
    d = os.path.join(rec, f"ch{i + 1:02d}")
    seg = os.path.join(out, f"seg{i + 1:02d}.mp4")
    length = e - s
    run("ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", os.path.join(d, "frames.ffconcat"),
        "-vf", f"fps={FPS},scale=1920:1080:flags=lanczos,tpad=stop_mode=clone:stop_duration=30,format=yuv420p",
        "-t", f"{length:.3f}", "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-r", str(FPS), seg)
    parts.append(seg)
with open(os.path.join(out, "parts.txt"), "w") as f:
    f.writelines(f"file '{os.path.abspath(x)}'\n" for x in parts)
picture = os.path.join(out, "picture.mp4")
run("ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", os.path.join(out, "parts.txt"), "-c", "copy", picture)

# ---------- captions: the script's words, each timed by the word the transcript heard for it ----------
norm = lambda t: re.sub(r"[^a-z0-9]+", "", t.lower())
script = open(script_path).read().strip()
sw = script.split()
a = [norm(w) for w in sw]
b = [norm(w["w"]) for w in words]
times = [None] * len(sw)
for op, i1, i2, j1, j2 in difflib.SequenceMatcher(a=a, b=b, autojunk=False).get_opcodes():
    if op == "equal" or (op == "replace" and j2 > j1):
        # Spread a replaced run (a number heard as digits) over the words heard for it.
        s0, e0 = words[j1]["s"], words[j2 - 1]["e"]
        n = i2 - i1
        for k in range(n):
            times[i1 + k] = (s0 + (e0 - s0) * k / n, s0 + (e0 - s0) * (k + 1) / n) if op != "equal" else (words[j1 + k]["s"], words[j1 + k]["e"])
for k in range(len(times)):  # any word not heard: between its neighbours
    if times[k] is None:
        prev = next((times[j] for j in range(k - 1, -1, -1) if times[j]), (0, 0))
        times[k] = (prev[1], prev[1] + 0.2)


def chunks(max_chars):
    """Caption lines: whole phrases, broken after punctuation where possible, at most [max_chars]."""
    cues, cur = [], []
    for k, w in enumerate(sw):
        cur.append(k)
        text = " ".join(sw[j] for j in cur)
        nxt = sw[k + 1] if k + 1 < len(sw) else None
        end_sentence = re.search(r"[.?!]$", w)
        soft = re.search(r"[,:;]$", w) and len(text) > max_chars * 0.55
        if end_sentence or soft or nxt is None or len(text + " " + nxt) > max_chars:
            cues.append((times[cur[0]][0], times[cur[-1]][1], text))
            cur = []
    # Each cue stays until the next starts (no flicker), at most 1.2 s past its last word.
    return [(s, min(e + 1.2, cues[i + 1][0] if i + 1 < len(cues) else e + 1.2), t) for i, (s, e, t) in enumerate(cues)]


def ts(t):
    return f"{int(t // 3600)}:{int(t % 3600 // 60):02d}:{t % 60:05.2f}"


def ass(path, w, h, size, margin_v, max_chars, outline_box=True):
    head = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {w}
PlayResY: {h}
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Cap,Inter SemiBold,{size},&H00FFFFFF,&H00FFFFFF,&H1A2A1402,&H1A2A1402,0,0,0,0,100,100,0,0,3,{max(8, size // 3)},0,2,60,60,{margin_v},1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    with open(path, "w") as f:
        f.write(head)
        for s, e, t in chunks(max_chars):
            if s < starts[1] - 0.05 or s >= starts[-1] - 0.05:
                continue  # the title and closing cards carry their own words
            f.write(f"Dialogue: 0,{ts(s)},{ts(e)},Cap,,0,0,0,,{t.replace('–', '-')}\n")


ass(os.path.join(out, "captions-16x9.ass"), 1920, 1080, 44, 96, 58)
ass(os.path.join(out, "captions-4x5.ass"), 1080, 1350, 46, 150, 34)

# ---------- 16:9: picture, captions, narration ----------
master = os.path.join(out, f"{title}-16x9.mp4")
run("ffmpeg", "-v", "error", "-y", "-i", picture, "-i", audio,
    "-vf", f"ass={os.path.join(out, 'captions-16x9.ass')}",
    "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-movflags", "+faststart", "-shortest", master)

# ---------- 4:5 for the LinkedIn feed: the picture in a branded frame, larger captions below ----------
feed = os.path.join(out, f"{title}-4x5.mp4")
top = 290
run("ffmpeg", "-v", "error", "-y", "-i", picture, "-i", audio, "-filter_complex",
    f"color=c=0x02142A:s=1080x1350:r={FPS}[bg];"
    f"[0:v]scale=1080:608:flags=lanczos[v];"
    f"[bg][v]overlay=0:{top}:shortest=1,"
    f"drawtext=font='Inter SemiBold':fontsize=24:fontcolor=0x9FB3CC:text='GETTING STARTED · EPISODE 2':x=60:y=88,"
    f"drawtext=font='Inter ExtraBold':fontsize=52:fontcolor=white:text='From the well to the':x=60:y=132,"
    f"drawtext=font='Inter ExtraBold':fontsize=52:fontcolor=0x14D3CF:text='inlet separator':x=60:y=190,"
    f"ass={os.path.join(out, 'captions-4x5.ass')}[o]",
    "-map", "[o]", "-map", "1:a", "-c:v", "libx264", "-preset", "slow", "-crf", "19", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-movflags", "+faststart", "-shortest", feed)
print(master, feed)
