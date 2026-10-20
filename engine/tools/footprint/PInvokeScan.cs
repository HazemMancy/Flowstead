// Lists, for each assembly the worker loaded, the native libraries it P/Invokes and whether it is IL-only.
// Usage: dotnet run --project engine/tools/footprint -- <dwsim-bin> <loaded.json from measure.py>
// SPDX-License-Identifier: GPL-3.0-or-later
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Text.Json;
var dir = args[0];
var names = JsonDocument.Parse(File.ReadAllText(args[1])).RootElement.GetProperty("dll").EnumerateArray().Select(e => e.GetString()).ToList();
foreach (var n in names)
{
    var path = Directory.EnumerateFiles(dir, n, SearchOption.AllDirectories).FirstOrDefault(); if (path == null) continue;
    using var pe = new PEReader(File.OpenRead(path));
    if (!pe.HasMetadata) continue;
    var md = pe.GetMetadataReader();
    var mods = new Dictionary<string, int>();
    foreach (var h in md.MethodDefinitions)
    {
        var m = md.GetMethodDefinition(h);
        var imp = m.GetImport();
        if (imp.Module.IsNil) continue;
        var mod = md.GetString(md.GetModuleReference(imp.Module).Name);
        mods[mod] = mods.GetValueOrDefault(mod) + 1;
    }
    var flags = pe.PEHeaders.CorHeader!.Flags;
    var mixed = (flags & CorFlags.ILOnly) == 0 ? " [NOT IL-only: mixed-mode/C++/CLI]" : "";
    if (mods.Count > 0 || mixed != "") Console.WriteLine($"{n}{mixed}: " + string.Join(", ", mods.OrderByDescending(k => k.Value).Select(k => $"{k.Key}×{k.Value}")));
}
