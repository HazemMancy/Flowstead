# Drives the engine worker over its stdin protocol, solves every benchmark twice and reports start-up
# time, solve times, peak memory and the DWSIM files the process maps (from /proc; Linux only).
#   python3 engine/tools/footprint/measure.py loaded.json   (after building the worker in Release)
# Then: dotnet run --project engine/tools/footprint -- engine/dwsim-bin loaded.json   (P/Invoke scan)
# See docs/on-device-spike.md.
import json, os, subprocess, sys, time, glob
root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
worker = f"{root}/engine/src/Flowstead.Engine.Worker/bin/Release/net10.0/linux-x64/Flowstead.Engine.Worker.dll"
t0 = time.time()
p = subprocess.Popen(["dotnet", worker], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
ready = json.loads(p.stdout.readline()); t_ready = time.time() - t0
def status():
    d = {}
    for line in open(f"/proc/{p.pid}/status"):
        k, v = line.split(":", 1)
        if k in ("VmHWM", "VmRSS"): d[k] = int(v.split()[0]) // 1024
    return d
print(f"ready in {t_ready:.2f} s, {status()}")
rid = 0
def call(method, params):
    global rid; rid += 1
    p.stdin.write(json.dumps({"id": rid, "method": method, "params": params}) + "\n")
    t = time.time(); r = json.loads(p.stdout.readline()); return r, time.time() - t
cases = sorted(glob.glob(f"{root}/benchmarks/0*/input.json"))
for rnd in ("first", "second"):
    for c in cases:
        fs = json.load(open(c))["flowsheet"]
        r, wall = call("solve", fs)
        print(f"{rnd:6} {os.path.basename(os.path.dirname(c)):28} {r['result']['status']:9} engine {r['result']['elapsedMs']:5d} ms  wall {wall*1000:6.0f} ms")
print("after solves", status())
maps = set()
for line in open(f"/proc/{p.pid}/maps"):
    parts = line.split()
    if len(parts) >= 6 and parts[5].startswith("/"): maps.add(parts[5])
dw = sorted(m for m in maps if "/dwsim-bin/" in m)
so = [m for m in dw if ".so" in m]; dll = [m for m in dw if m.endswith(".dll")]
size = lambda xs: sum(os.path.getsize(x) for x in xs) / 1e6
print(f"DWSIM-dir files mapped: {len(dll)} .dll ({size(dll):.1f} MB), {len(so)} native ({size(so):.1f} MB)")
for x in so: print("  native:", os.path.basename(x), f"{os.path.getsize(x)/1e6:.1f} MB")
for x in sorted(dll, key=os.path.getsize, reverse=True)[:25]: print("  dll:", os.path.basename(x), f"{os.path.getsize(x)/1e6:.1f} MB")
other_native = sorted(m for m in maps if ".so" in m and "/dwsim-bin/" not in m and "dotnet" not in m)
print("other native (system):", [os.path.basename(m) for m in other_native])
json.dump({"dll": [os.path.basename(x) for x in dll], "so": [os.path.basename(x) for x in so]}, open(sys.argv[1], "w"))
p.stdin.close(); p.wait()
