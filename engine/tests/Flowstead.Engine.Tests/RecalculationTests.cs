// Flowstead engine tests — reusing the last solve's flowsheet (D82). A solve after a spec change, which reuses the
// worker's DWSIM flowsheet and recalculates downstream of the change, must give what a fresh build gives.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Globalization;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Nodes;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class RecalculationTests(EngineFixture engine, ITestOutputHelper output)
{
    private static readonly string Dir = Path.GetFullPath(EngineFixture.Meta("BenchmarksDir"));

    public static IEnumerable<object[]> Cases() => BenchmarkTests.Cases();

    [Theory]
    [MemberData(nameof(Cases))]
    public async Task ReusedFlowsheetGivesTheFreshResult(string name)
    {
        var input = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(Dir, name, "input.json")))!;
        var model = input["flowsheet"].Deserialize<Flowsheet>(Json.Options)!;
        using var client = engine.Authed();

        // The fixture's workers take requests in turn; each request goes twice so every worker holds the same flowsheet.
        async Task<JsonNode> Twice(Flowsheet f)
        {
            await Solve(client, f);
            return await Solve(client, f);
        }

        var first = await Twice(model);
        if (first["status"]!.GetValue<string>() != "converged") return; // nothing is reused after a failed solve
        var again = await Twice(model);
        Assert.Equal("unchanged", Kind(again));
        Same(name + " unchanged", first, again);

        foreach (var (label, changed) in Perturbations(model))
        {
            await Twice(model);
            var reused = await Twice(changed);
            await Twice(Unrelated); // evicts the cached flowsheet from both workers
            var fresh = await Solve(client, changed);
            Assert.Equal("built", Kind(fresh));
            if (fresh["status"]!.GetValue<string>() != "converged") continue;
            Assert.Equal("converged", reused["status"]!.GetValue<string>());
            Assert.Contains(Kind(reused), new[] { "downstream", "reused", "built" }); // built: a column or reactor spec changed
            Same($"{name} {label}", fresh, reused);
            var (r, f) = (reused["recalculation"]!, fresh["recalculation"]!);
            output.WriteLine($"{name} {label}: fresh build {f["buildMs"]} ms + calculate {f["calculateMs"]} ms ({f["total"]} objects); " +
                             $"reused {Kind(reused)} {r["buildMs"]} ms + {r["calculateMs"]} ms ({r["recalculated"]} recalculated)");
        }
    }

    private static string Kind(JsonNode result) => result["recalculation"]!["kind"]!.GetValue<string>();

    private static async Task<JsonNode> Solve(HttpClient client, Flowsheet f)
    {
        var response = await client.PostAsJsonAsync("/solve", f, Json.Options);
        response.EnsureSuccessStatusCode();
        return JsonNode.Parse(await response.Content.ReadAsStringAsync())!;
    }

    /// <summary>A feed's temperature up 2 K; the first unit spec in the flow up 2 %.</summary>
    private static IEnumerable<(string, Flowsheet)> Perturbations(Flowsheet model)
    {
        var fed = model.Connections.Select(c => c.ToObject).ToHashSet();
        if (model.Objects.FirstOrDefault(o => o.Type == ObjectType.MaterialStream && !fed.Contains(o.Id) && o.Specs.ContainsKey(SpecKeys.Temperature)) is { } feed)
        {
            yield return ($"{feed.Tag} T+2 K", With(model, feed, SpecKeys.Temperature, t => t + 2));
        }
        if (model.Objects.FirstOrDefault(o => o.Type is not (ObjectType.MaterialStream or ObjectType.EnergyStream or ObjectType.Set or ObjectType.Adjust or ObjectType.Recycle)
                && o.Specs.Any(s => s.Value.Si != 0)) is { } unit)
        {
            var key = unit.Specs.First(s => s.Value.Si != 0).Key;
            yield return ($"{unit.Tag} {key}×1.02", With(model, unit, key, v => v * 1.02));
        }
    }

    private static Flowsheet With(Flowsheet model, SimObject o, string key, Func<double, double> f) => model with
    {
        Objects = model.Objects.Select(x => x.Id != o.Id ? x : x with
        {
            Specs = new Dictionary<string, Spec>(x.Specs) { [key] = x.Specs[key] with { Si = f(x.Specs[key].Si) } },
        }).ToList(),
    };

    private static readonly Flowsheet Unrelated = new()
    {
        Compounds = ["Methane"],
        PropertyPackage = "Peng-Robinson (PR)",
        Objects =
        [
            new SimObject
            {
                Id = "u", Tag = "U", Type = ObjectType.MaterialStream, Composition = new() { ["Methane"] = 1.0 },
                Specs = new()
                {
                    [SpecKeys.Temperature] = new(300, "K"), [SpecKeys.Pressure] = new(1e6, "Pa"), [SpecKeys.MolarFlow] = new(1, "mol/s"),
                },
            },
        ],
    };

    /// <summary>Every number of every object agrees within 1e-4 relative (a recycle's own tolerance aside).</summary>
    private static void Same(string label, JsonNode expected, JsonNode actual)
    {
        var failures = new List<string>();
        void Walk(JsonNode? e, JsonNode? a, string path)
        {
            switch (e)
            {
                case JsonObject eo:
                    foreach (var (k, v) in eo) Walk(v, a?[k], path + "." + k);
                    break;
                case JsonArray ea:
                    for (var i = 0; i < ea.Count; i++) Walk(ea[i], a is JsonArray aa && i < aa.Count ? aa[i] : null, $"{path}[{i}]");
                    break;
                case JsonValue ev when ev.TryGetValue<double>(out var x):
                    if (a is not JsonValue av || !av.TryGetValue<double>(out var y)) { failures.Add($"{path}: {x} vs missing"); break; }
                    if (Math.Abs(x - y) > Math.Max(1e-9, 1e-4 * Math.Abs(x))) failures.Add($"{path}: fresh {x.ToString(CultureInfo.InvariantCulture)}, reused {y.ToString(CultureInfo.InvariantCulture)}");
                    break;
            }
        }
        Walk(expected["objects"], actual["objects"], "objects");
        Assert.True(failures.Count == 0, $"{label}: {failures.Count} difference(s)\n" + string.Join("\n", failures.Take(30)));
    }
}
