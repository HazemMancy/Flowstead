// Flowstead — selects the DWSIM files the on-device engine ships (decision D26) and copies them,
// with Flowstead's own engine assemblies, into a folder the Android build packs as an asset.
//   dotnet run --project engine/tools/bundle -- <dwsim-bin> <engine-bin> <framework-dir> <out-dir>
// Selection: the assembly-reference closure of DWSIM's solver assemblies, minus desktop UI, Office,
// PDF, cloud and OpenGL libraries a headless solve never runs. Files every solve needs at run time
// (user compounds, data tables) are copied too. The result is listed in <out-dir>/MANIFEST.txt.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Text.RegularExpressions;

if (args.Length != 4)
{
    Console.Error.WriteLine("usage: bundle <dwsim-bin> <engine-bin> <framework-dir> <out-dir>");
    return 2;
}
var (dwsim, engineBin, frameworkDir, outDir) = (args[0], args[1], args[2], args[3]);

string[] roots =
[
    "DWSIM.Automation", "DWSIM.Thermodynamics", "DWSIM.UnitOperations", "DWSIM.FlowsheetBase",
    "DWSIM.FlowsheetSolver", "DWSIM.Interfaces", "DWSIM.GlobalSettings", "DWSIM.SharedClasses", "CapeOpen",
    // Loaded by reflection, not by reference: property packages and solvers.
    "DWSIM.Thermodynamics.AdvancedEOS.GERG2008", "DWSIM.Thermodynamics.AdvancedEOS.PCSAFT2",
    "DWSIM.Thermodynamics.AdvancedEOS.PRSRKTDep", "DWSIM.Thermodynamics.CoolPropInterface",
    "DWSIM.MathOps.SwarmOps", "DWSIM.DynamicsManager", "DWSIM.FileStorage", "DWSIM.Logging",
    "DWSIM.Thermodynamics.ThermoC", "ThermoCS",
    // Type-forwarded from the BCL's System.Drawing facade; DWSIM ships the implementation.
    "System.Drawing.Common",
];
// Never needed by a headless solve; several pull in hundreds of MB of desktop frameworks.
var exclude = new Regex(
    @"^(Microsoft\.Graph.*|Microsoft\.Identity.*|Microsoft\.IdentityModel.*|System\.IdentityModel\..*|Azure\..*|" +
    @"Microsoft\.Web\.WebView2.*|OpenTK.*|Presentation.*|ReachFramework|WindowsBase|GemBox\..*|ExcelApi|OfficeApi|" +
    @"VBIDEApi|NetOffice|ExcelDna\..*|stdole|Scintilla.*|ScintillaNET|DWSIM\.UI\..*|DWSIM\.Simulate365|Cudafy\.NET|" +
    @"itextsharp|Xamarin\..*|GtkSharp|GioSharp|GdkSharp|GLibSharp|AtkSharp|PangoSharp|CairoSharp|HtmlRenderer.*|" +
    @"Eto\.OxyPlot|Eto\.Gtk.*|Eto\.Wpf|Eto\.WinForms|Eto\.Mac.*|SpinningProgress|TabStrip|RichTextBoxExtended|" +
    @"Jolt.*|xmlunit-core|AutomaticGraphLayout|Microsoft\.Msagl.*|DWSIM\.SkiaSharp\.Views\.Desktop|" +
    @"SkiaSharp\.Views\..*|Microsoft\.Bcl\.AsyncInterfaces|System\.Memory\.Data|DWSIM\.ProFeatures)$",
    RegexOptions.IgnoreCase);

var framework = Directory.GetFiles(frameworkDir, "*.dll").Select(Path.GetFileNameWithoutExtension).ToHashSet(StringComparer.OrdinalIgnoreCase);
var selected = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
var skipped = new SortedSet<string>(StringComparer.OrdinalIgnoreCase);
var queue = new Queue<string>(roots);
while (queue.Count > 0)
{
    var name = queue.Dequeue();
    if (selected.ContainsKey(name) || skipped.Contains(name) || framework.Contains(name)) continue;
    if (exclude.IsMatch(name)) { skipped.Add(name); continue; }
    var path = Path.Combine(dwsim, name + ".dll");
    if (!File.Exists(path)) { skipped.Add(name + " (not shipped by DWSIM)"); continue; }
    selected[name] = path;
    using var pe = new PEReader(File.OpenRead(path));
    if (!pe.HasMetadata) continue;
    var md = pe.GetMetadataReader();
    foreach (var h in md.AssemblyReferences) queue.Enqueue(md.GetString(md.GetAssemblyReference(h).Name));
}

if (Directory.Exists(outDir)) Directory.Delete(outDir, true);
var dwsimOut = Directory.CreateDirectory(Path.Combine(outDir, "dwsim")).FullName;
var appOut = Directory.CreateDirectory(Path.Combine(outDir, "app")).FullName;
foreach (var (_, path) in selected) File.Copy(path, Path.Combine(dwsimOut, Path.GetFileName(path)));
foreach (var dir in new[] { "addcomps", "data" })
{
    if (!Directory.Exists(Path.Combine(dwsim, dir))) continue; // DWSIM 10 keeps its data tables as embedded resources
    foreach (var f in Directory.EnumerateFiles(Path.Combine(dwsim, dir), "*", SearchOption.AllDirectories))
    {
        var dest = Path.Combine(dwsimOut, Path.GetRelativePath(dwsim, f));
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        File.Copy(f, dest);
    }
}
foreach (var d in new[] { "extenders", "ppacks", "unitops" }) Directory.CreateDirectory(Path.Combine(dwsimOut, d)); // DWSIM scans these
foreach (var f in Directory.GetFiles(engineBin, "Flowstead.Engine.*.dll")) File.Copy(f, Path.Combine(appOut, Path.GetFileName(f)));

long Size(string dir) => Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories).Sum(f => new FileInfo(f).Length);
using (var m = new StreamWriter(Path.Combine(outDir, "MANIFEST.txt")))
{
    m.WriteLine($"Flowstead on-device engine bundle: {selected.Count} DWSIM assemblies, {Size(outDir) / 1e6:0.0} MB");
    m.WriteLine("Shipped:");
    foreach (var (n, p) in selected) m.WriteLine($"  {new FileInfo(p).Length / 1e6,6:0.0} MB  {n}");
    m.WriteLine("Left out (desktop UI, Office, PDF, cloud, OpenGL, or not in DWSIM's package):");
    foreach (var s in skipped) m.WriteLine($"  {s}");
}
Console.WriteLine($"{selected.Count} DWSIM assemblies, {Size(outDir) / 1e6:0.0} MB -> {outDir}");
return 0;
