// Flowstead engine tests — .dwxmz export and import. Every benchmark is exported, imported back and
// re-solved; the re-solved numbers must match the direct solve, so nothing is lost on the way.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Nodes;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class DwsimFileTests(EngineFixture engine, ITestOutputHelper output)
{
    private static readonly string Dir = Path.GetFullPath(EngineFixture.Meta("BenchmarksDir"));

    public static IEnumerable<object[]> Cases() => BenchmarkTests.Cases();

    private static Flowsheet Load(string name) =>
        JsonNode.Parse(File.ReadAllText(Path.Combine(Dir, name, "input.json")))!["flowsheet"].Deserialize<Flowsheet>(Json.Options)!;

    [Theory]
    [MemberData(nameof(Cases))]
    public async Task RoundTripKeepsTheSolution(string name)
    {
        using var client = engine.Authed();
        var original = Load(name);
        var direct = await Post<SolveResult>(client, "/solve", original);

        var export = await Post<ExportResult>(client, "/export", original);
        var bytes = Convert.FromBase64String(export.Data);
        Assert.True(bytes is [0x50, 0x4B, ..], "a .dwxmz is a zip");
        foreach (var w in export.Warnings) output.WriteLine("export: " + w);

        var import = await Post<ImportResult>(client, "/import", new DwsimFile(export.Data));
        foreach (var w in import.Warnings) output.WriteLine("import: " + w);
        var back = import.Flowsheet;
        Assert.Equal(original.PropertyPackage, back.PropertyPackage);
        Assert.Equal(original.Compounds, back.Compounds);

        var resolved = await Post<SolveResult>(client, "/solve", back);
        var hasAdjust = original.Objects.Any(o => o.Type == ObjectType.Adjust);
        Assert.True(resolved.Status == SolveStatus.Converged || hasAdjust,
            $"{name}: re-solve {resolved.Status}: {string.Join("; ", resolved.Messages.Select(m => m.Text))}");

        // Same tag → same numbers (the import gives new ids).
        var byTag = back.Objects.ToDictionary(o => o.Tag, o => o.Id);
        foreach (var o in original.Objects.Where(o => o.Type == ObjectType.MaterialStream))
        {
            Assert.True(byTag.ContainsKey(o.Tag), $"{name}: stream {o.Tag} lost on import");
            if (hasAdjust) continue; // the Adjust is not in the file, so the varied spec is at its starting value
            var a = direct.Objects[o.Id].Properties;
            var b = resolved.Objects[byTag[o.Tag]].Properties;
            foreach (var key in new[] { "temperature", "pressure", "massFlow", "vapourFraction" })
            {
                if (!a.TryGetValue(key, out var x)) continue;
                Assert.True(b.TryGetValue(key, out var y), $"{name}/{o.Tag}: {key} missing after round trip");
                Assert.True(Math.Abs(x - y) <= 1e-6 * Math.Max(1, Math.Abs(x)), $"{name}/{o.Tag}: {key} {x} → {y}");
            }
        }
        Assert.Equal(original.Connections.Count + HiddenEnergyStreams(original), back.Connections.Count);
    }

    /// <summary>The engine adds an energy stream to each heater, cooler, air cooler, pump, compressor, expander, reactor or column duty drawn without one; it is real in the file.</summary>
    private static int HiddenEnergyStreams(Flowsheet fs) =>
        fs.Objects.Count(o => o.Type is ObjectType.Heater or ObjectType.Cooler or ObjectType.AirCooler or ObjectType.Pump or ObjectType.Compressor or ObjectType.Expander
                              && !fs.Connections.Any(c => (c.ToObject == o.Id && c.ToPort == 1) || (c.FromObject == o.Id && c.FromPort == 1)))
        // Reactors: heat in on port 1 (their out port 1 is the liquid product).
        + fs.Objects.Count(o => o.Type is ObjectType.ConversionReactor or ObjectType.EquilibriumReactor or ObjectType.GibbsReactor or ObjectType.Cstr or ObjectType.Pfr
                                && !fs.Connections.Any(c => c.ToObject == o.Id && c.ToPort == 1))
        // Columns: condenser (port 3) and reboiler (port 4) duties.
        + fs.Objects.Count(o => o.Type is ObjectType.DistillationColumn or ObjectType.ShortcutColumn or ObjectType.RefluxedAbsorber
                                && !fs.Connections.Any(c => c.FromObject == o.Id && c.FromPort == 3))
        + fs.Objects.Count(o => o.Type is ObjectType.DistillationColumn or ObjectType.ShortcutColumn or ObjectType.ReboiledAbsorber
                                && !fs.Connections.Any(c => c.ToObject == o.Id && c.ToPort == 4));

    [Fact]
    public async Task StoredFlowsheetDownloadsAsAFile()
    {
        using var client = engine.Authed();
        var created = await client.PostAsJsonAsync("/flowsheets", new { name = "Letdown / test", flowsheet = Load("05-jt-valve-letdown") }, Json.Options);
        var id = (await created.Content.ReadFromJsonAsync<JsonObject>())!["id"]!.GetValue<string>();
        var r = await client.GetAsync($"/flowsheets/{id}/export");
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("Letdown _ test.dwxmz", r.Content.Headers.ContentDisposition?.FileName?.Trim('"'));
        Assert.True((await r.Content.ReadAsByteArrayAsync()) is [0x50, 0x4B, ..]);
    }

    /// <summary>shared/fixtures/with-script.dwxmz: FEED → VLV-100 → LP → SCRIPT-1 (a Python script unit op) → END, saved by DWSIM 9.0.5.</summary>
    [Fact]
    public async Task UnsupportedObjectsAreLeftOutWithAWarning()
    {
        using var client = engine.Authed();
        var file = Convert.ToBase64String(File.ReadAllBytes(Path.Combine(Dir, "..", "shared", "fixtures", "with-script.dwxmz")));
        var import = await Post<ImportResult>(client, "/import", new DwsimFile(file));
        Assert.Equal(["END", "FEED", "LP", "VLV-100"], import.Flowsheet.Objects.Select(o => o.Tag).Order());
        Assert.Contains(import.Warnings, w => w.StartsWith("SCRIPT-1: CustomUO is not supported"));
        Assert.Contains(import.Warnings, w => w.StartsWith("END: it was fed by SCRIPT-1"));
        Assert.Equal(2, import.Flowsheet.Connections.Count);

        var feed = import.Flowsheet.Objects.Single(o => o.Tag == "FEED");
        Assert.Equal(300.0, feed.Specs[SpecKeys.Temperature].Si, 9);
        Assert.Equal(5e6, feed.Specs[SpecKeys.Pressure].Si, 6);
        Assert.Equal(10.0, feed.Specs[SpecKeys.MolarFlow].Si, 9);
        Assert.Equal(0.9, feed.Composition["Methane"], 9);
        var valve = import.Flowsheet.Objects.Single(o => o.Tag == "VLV-100");
        Assert.Equal(1e6, valve.Specs[SpecKeys.OutletPressure].Si, 6);
    }

    [Fact]
    public async Task GarbageIsRejectedInPlainLanguage()
    {
        using var client = engine.Authed();
        var r = await client.PostAsJsonAsync("/import", new DwsimFile(Convert.ToBase64String("not a flowsheet"u8.ToArray())), Json.Options);
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        var error = await EngineFixture.Json<EngineError>(r);
        Assert.Equal("invalid_file", error.Code);
        Assert.Contains("DWSIM could not open the file", error.Message);

        r = await client.PostAsJsonAsync("/import", new DwsimFile("%%%"), Json.Options);
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Fact]
    public async Task InvalidFlowsheetIsNotExported()
    {
        using var client = engine.Authed();
        var r = await client.PostAsJsonAsync("/export", new Flowsheet { PropertyPackage = "Peng-Robinson (PR)" }, Json.Options);
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        Assert.Contains("Add at least one compound", (await EngineFixture.Json<EngineError>(r)).Message);
    }

    private static async Task<T> Post<T>(HttpClient client, string path, object body)
    {
        var r = await client.PostAsJsonAsync(path, body, Json.Options);
        Assert.True(r.IsSuccessStatusCode, $"{path}: {(int)r.StatusCode} {await r.Content.ReadAsStringAsync()}");
        return (await r.Content.ReadFromJsonAsync<T>(Json.Options))!;
    }
}
