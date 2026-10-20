// Flowstead engine spike — solve one material stream with DWSIM, headless, on .NET 8 / Linux.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// This program is free software: you can redistribute it and/or modify it under the terms of
// the GNU General Public License as published by the Free Software Foundation, either version 3
// of the License, or (at your option) any later version. See LICENSE.

using System.Diagnostics;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

var dwsimHome = Environment.GetEnvironmentVariable("DWSIM_HOME")
    ?? typeof(Spike).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>().First(a => a.Key == "DwsimBin").Value!;
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
// DWSIM resolves data files and native libraries relative to the working directory.
Directory.SetCurrentDirectory(dwsimHome);

return Spike.Run(dwsimHome);

static class Spike
{
    [MethodImpl(MethodImplOptions.NoInlining)]
    public static int Run(string dwsimHome)
    {
        var sw = Stopwatch.StartNew();
        Console.WriteLine($"Runtime: {RuntimeInformation.FrameworkDescription} on {RuntimeInformation.OSDescription} ({RuntimeInformation.ProcessArchitecture})");
        Console.WriteLine($"DWSIM:   {dwsimHome}");

        var automation = new DWSIM.Automation.Automation3();
        Console.WriteLine($"[{sw.ElapsedMilliseconds,6} ms] Automation3 ready: {automation.AvailablePropertyPackages.Count} property packages, {automation.AvailableCompounds.Count} compounds");

        var fs = automation.CreateFlowsheet();
        foreach (var c in new[] { "Methane", "Ethane", "Propane" }) fs.AddCompound(c);
        fs.CreateAndAddPropertyPackage("Peng-Robinson (PR)");

        var ms = (DWSIM.Thermodynamics.Streams.MaterialStream)fs.AddObject(
            DWSIM.Interfaces.Enums.GraphicObjects.ObjectType.MaterialStream, 50, 50, "FEED");
        ms.SetOverallComposition(new[] { 0.80, 0.15, 0.05 });   // mole fractions
        ms.SetTemperature(273.15 - 40.0);                        // K  (−40 °C)
        ms.SetPressure(30e5);                                    // Pa (30 bara)
        ms.SetMolarFlow(100.0 / 3.6);                            // mol/s (100 kgmol/h)

        var errors = automation.CalculateFlowsheet4(fs);
        Console.WriteLine($"[{sw.ElapsedMilliseconds,6} ms] Solved with {errors.Count} error(s)");
        foreach (var e in errors) Console.WriteLine("  ! " + e.Message);

        var p = ms.Phases[0].Properties;
        var vf = ms.Phases[2].Properties.molarfraction;
        Console.WriteLine($"FEED  T = {p.temperature - 273.15:F3} °C   P = {p.pressure / 1e5:F4} bara");
        Console.WriteLine($"      vapour fraction (molar) = {vf:F6}");
        Console.WriteLine($"      H (mass) = {p.enthalpy:F4} kJ/kg   mass flow = {p.massflow * 3600:F3} kg/h");
        return errors.Count == 0 ? 0 : 1;
    }
}
