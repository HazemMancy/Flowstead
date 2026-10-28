// ThermoDock engine worker — one long-lived process hosting DWSIM, driven over stdin/stdout.
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// This program is free software: you can redistribute it and/or modify it under the terms of
// the GNU General Public License as published by the Free Software Foundation, either version 3
// of the License, or (at your option) any later version. See LICENSE.
//
// Protocol: one JSON WorkerRequest per stdin line, one WorkerResponse per stdout line.
// On start-up the worker writes {"id":0,"result":{"ready":true,...}} once DWSIM is loaded.
// Everything DWSIM itself prints goes to stderr so it can never corrupt the protocol.

using System.Reflection;
using System.Runtime.CompilerServices;
using System.Text;
using ThermoDock.Engine.Worker;

var dwsimHome = Environment.GetEnvironmentVariable("DWSIM_HOME")
    ?? typeof(WorkerHost).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>().First(a => a.Key == "DwsimBin").Value!;
if (!File.Exists(Path.Combine(dwsimHome, "DWSIM.Thermodynamics.dll")))
{
    Console.Error.WriteLine($"DWSIM not found in '{dwsimHome}'. Run engine/scripts/fetch-dwsim.sh or set DWSIM_HOME.");
    return 2;
}

AppDomain.CurrentDomain.AssemblyResolve += (_, e) =>
{
    var path = Path.Combine(dwsimHome, new AssemblyName(e.Name).Name + ".dll");
    return File.Exists(path) ? Assembly.LoadFrom(path) : null;
};
Directory.SetCurrentDirectory(dwsimHome);

var protocolOut = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true };
Console.SetOut(Console.Error);

return Run(protocolOut);

[MethodImpl(MethodImplOptions.NoInlining)]
static int Run(TextWriter output) => new WorkerHost(output).Run(Console.In);
