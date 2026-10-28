# Embedded engine host

Runs the ThermoDock engine, with DWSIM, **inside another process** on the .NET Mono runtime: the
same `DwsimEngine` the server's worker runs, called in-process. It is how the Android app solves
on the phone's CPU (decision D25).

- `thermodock_host.c/.h`: boots Mono in netcore mode (`monovm_initialize` with the base class
  library as trusted assemblies, DWSIM's folder as an app path) and calls
  `ThermoDock.Engine.Worker.EmbeddedHost.Init/Call`: one WorkerRequest JSON in, one
  WorkerResponse JSON out, the same protocol as the server worker.
- `host_linux.c`: a Linux test host that speaks the worker's stdin/stdout protocol, so the
  server's test drivers run against it unchanged.
- The Android JNI bridge lives in `android/engine-local/`.

## Linux check (Mono 8.0.32, linux-x64)

```sh
# runtime: NuGet microsoft.netcore.app.runtime.mono.linux-x64 8.0.32, unzipped to $PACK
N=$PACK/runtimes/linux-x64/native; F=$PACK/runtimes/linux-x64/lib/net8.0; cp $N/System.Private.CoreLib.dll $F/
gcc -O2 -o thermodock-embedded thermodock_host.c host_linux.c -I$N/include/mono-2.0 -L$N -lcoreclr -lpthread -Wl,-rpath,$N
./thermodock-embedded $F <dir with ThermoDock.Engine.*.dll> "$N:<dwsim-bin>" <dwsim-bin>
```

Measured on the 8 benchmarks (dev container, x64): every result **bit-identical** to the CoreCLR
worker (worst relative difference 0), ready in 3.5 s, solves 20–610 ms.
