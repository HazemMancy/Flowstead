// Flowstead engine tests — bubble and dew points on demand (D119). A solve can leave them out and ask for chosen streams;
// what it returns for a stream must equal what a solve that calculates them all returns.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Nodes;
using Flowstead.Engine.Contracts;
using Xunit;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class SaturationOnDemandTests(EngineFixture engine)
{
    private static readonly string[] Keys = ["bubblePointTemperature", "dewPointTemperature", "bubblePointPressure", "dewPointPressure"];

    private static async Task<JsonNode> Solve(HttpClient client, Flowsheet f)
    {
        var response = await client.PostAsJsonAsync("/solve", f, Json.Options);
        response.EnsureSuccessStatusCode();
        return JsonNode.Parse(await response.Content.ReadAsStringAsync())!;
    }

    private static bool Has(JsonNode r, string id) => Keys.Any(k => r["objects"]![id]!["properties"]![k] is not null);

    [Fact]
    public async Task OnlyTheStreamsAskedForGetTheirPoints_AndTheyEqualWhatASolveForEveryStreamGives()
    {
        var path = Path.Combine(Path.GetFullPath(EngineFixture.Meta("BenchmarksDir")), "06-compressor-aftercooler", "input.json");
        var model = JsonNode.Parse(await File.ReadAllTextAsync(path))!["flowsheet"].Deserialize<Flowsheet>(Json.Options)!;
        var streams = model.Objects.Where(o => o.Type == ObjectType.MaterialStream).Select(o => o.Id).ToList();
        Assert.True(streams.Count >= 2, "the benchmark has a feed and a product");
        using var client = engine.Authed();

        var all = await Solve(client, model);                                   // null: every stream (what the app asks)
        Assert.Equal("converged", all["status"]!.GetValue<string>());
        var withPoints = streams.Where(id => Has(all, id)).ToList();
        Assert.True(withPoints.Count >= 1, "a stream has saturation points");

        var none = await Solve(client, model with { SaturationFor = [] });
        foreach (var id in streams) Assert.False(Has(none, id), $"{id} has points though none were asked for");
        // Everything else is the same: a stream's temperature and enthalpy do not depend on the points.
        foreach (var id in streams)
            foreach (var k in new[] { "temperature", "pressure", "massEnthalpy", "vapourFraction" })
                Assert.Equal(all["objects"]![id]!["properties"]![k]?.ToJsonString(), none["objects"]![id]!["properties"]![k]?.ToJsonString());

        var one = withPoints[0];
        var asked = await Solve(client, model with { SaturationFor = [one] });  
        foreach (var id in streams)
            if (id == one) foreach (var k in Keys) Assert.Equal(all["objects"]![id]!["properties"]![k]?.ToJsonString(), asked["objects"]![id]!["properties"]![k]?.ToJsonString());
            else Assert.False(Has(asked, id), $"{id} was not asked for");
    }
}
