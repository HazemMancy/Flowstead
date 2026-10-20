// Flowstead engine tests — benchmark harness (brief §4). Solves every case in benchmarks/ through the
// HTTP API and compares against expected.json. Any deviation outside tolerance fails the build.
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
public sealed class BenchmarkTests(EngineFixture engine, ITestOutputHelper output)
{
    private static readonly string Dir = Path.GetFullPath(EngineFixture.Meta("BenchmarksDir"));

    public static IEnumerable<object[]> Cases() =>
        Directory.GetDirectories(Dir)
            .Where(d => File.Exists(Path.Combine(d, "input.json")) && File.Exists(Path.Combine(d, "expected.json")))
            .Select(d => new object[] { Path.GetFileName(d) })
            .OrderBy(c => (string)c[0]);

    [Theory]
    [MemberData(nameof(Cases))]
    public async Task Case(string name)
    {
        var input = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(Dir, name, "input.json")))!;
        var expected = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(Dir, name, "expected.json")))!;
        var flowsheet = input["flowsheet"].Deserialize<Flowsheet>(Json.Options)!;

        using var client = engine.Authed();
        var response = await client.PostAsJsonAsync("/solve", flowsheet, Json.Options);
        response.EnsureSuccessStatusCode();
        var raw = await response.Content.ReadAsStringAsync();
        var result = JsonNode.Parse(raw)!;

        var expectedStatus = expected["status"]?.GetValue<string>() ?? "converged";
        Assert.True(result["status"]!.GetValue<string>() == expectedStatus,
            $"{name}: status {result["status"]} (expected {expectedStatus}); messages: {result["messages"]?.ToJsonString()}");

        var failures = new List<string>();
        var source = expected["source"]?.GetValue<string>() ?? "unknown";
        foreach (var check in expected["checks"]?.AsArray() ?? [])
        {
            Evaluate(check!, result, $"[{source}]", failures);
        }
        foreach (var reference in expected["reference"]?.AsArray() ?? [])
        {
            var label = $"[reference: {reference!["source"]}] {reference["label"]}";
            var refFailures = new List<string>();
            Evaluate(reference, result, label, refFailures);
            if (reference["blocking"]?.GetValue<bool>() == true) failures.AddRange(refFailures);
            else foreach (var f in refFailures) output.WriteLine("NON-BLOCKING " + f);
        }

        Assert.True(failures.Count == 0, $"{name}: {failures.Count} deviation(s)\n" + string.Join("\n", failures));
        output.WriteLine($"{name}: all checks within tolerance ({source})");
    }

    private static void Evaluate(JsonNode check, JsonNode result, string label, List<string> failures)
    {
        var path = check["path"]!.GetValue<string>();
        var expected = check["expected"]!.GetValue<double>();
        var actual = Lookup(result, path);
        if (check["minus"]?.GetValue<string>() is { } minus)
        {
            var b = Lookup(result, minus);
            actual = actual is null || b is null ? null : actual - b;
            path = $"{path} − {minus}";
        }
        if (actual is null)
        {
            failures.Add($"{label} {path}: missing from engine result (expected {Fmt(expected)})");
            return;
        }
        var tol = check["tol"]!;
        var abs = tol["abs"]?.GetValue<double>() ?? 0.0;
        var rel = tol["rel"]?.GetValue<double>() ?? 0.0;
        var allowed = Math.Max(abs, rel * Math.Abs(expected));
        var diff = Math.Abs(actual.Value - expected);
        if (!(diff <= allowed))
        {
            failures.Add($"{label} {path}: got {Fmt(actual.Value)}, expected {Fmt(expected)} ± {Fmt(allowed)} (off by {Fmt(diff)})");
        }
    }

    /// <summary>Dotted path into the result JSON; compound names may contain dots, so the last segment of a composition path is taken whole.</summary>
    private static double? Lookup(JsonNode root, string path)
    {
        var parts = path.Split('.');
        var compositionAt = Array.IndexOf(parts, "composition");
        if (compositionAt >= 0 && compositionAt < parts.Length - 1)
        {
            parts = [.. parts[..(compositionAt + 1)], string.Join('.', parts[(compositionAt + 1)..])];
        }
        JsonNode? node = root;
        foreach (var p in parts)
        {
            node = node?[p];
            if (node is null) return null;
        }
        return node.GetValue<double>();
    }

    private static string Fmt(double v) => v.ToString("G8", CultureInfo.InvariantCulture);
}
