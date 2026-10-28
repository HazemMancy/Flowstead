// ThermoDock engine API — SQLite storage for flowsheets, results and API keys.
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using ThermoDock.Engine.Contracts;
using Microsoft.Data.Sqlite;
using Json = ThermoDock.Engine.Contracts.Json;

namespace ThermoDock.Engine.Api;

public sealed record StoredFlowsheet(string Id, string Name, Flowsheet Flowsheet, DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt);

public sealed record StoredResult(SolveResult Result, string ModelHash, DateTimeOffset SolvedAt);

public sealed record FlowsheetSummary(string Id, string Name, DateTimeOffset UpdatedAt);

public sealed class Store
{
    private readonly string _connectionString;

    public Store(EngineOptions options)
    {
        _connectionString = new SqliteConnectionStringBuilder { DataSource = options.DatabasePath, Pooling = true }.ToString();
        using var c = Open();
        Exec(c, """
            PRAGMA journal_mode = WAL;
            CREATE TABLE IF NOT EXISTS flowsheets (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, json TEXT NOT NULL,
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS results (
                flowsheet_id TEXT PRIMARY KEY REFERENCES flowsheets(id) ON DELETE CASCADE,
                json TEXT NOT NULL, model_hash TEXT NOT NULL, solved_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS api_keys (
                key_hash TEXT PRIMARY KEY, device TEXT NOT NULL, created_at TEXT NOT NULL, revoked_at TEXT);
            """);
    }

    private SqliteConnection Open()
    {
        var c = new SqliteConnection(_connectionString);
        c.Open();
        Exec(c, "PRAGMA foreign_keys = ON;");
        return c;
    }

    private static void Exec(SqliteConnection c, string sql, params (string, object?)[] args)
    {
        using var cmd = Command(c, sql, args);
        cmd.ExecuteNonQuery();
    }

    private static SqliteCommand Command(SqliteConnection c, string sql, (string, object?)[] args)
    {
        var cmd = c.CreateCommand();
        cmd.CommandText = sql;
        foreach (var (k, v) in args) cmd.Parameters.AddWithValue(k, v ?? DBNull.Value);
        return cmd;
    }

    public static string ModelHash(Flowsheet fs) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(fs, Json.Options)))).ToLowerInvariant();

    // ---- flowsheets

    public StoredFlowsheet Create(string name, Flowsheet fs)
    {
        var now = DateTimeOffset.UtcNow;
        var stored = new StoredFlowsheet(Guid.NewGuid().ToString("n"), name, fs, now, now);
        using var c = Open();
        Exec(c, "INSERT INTO flowsheets (id, name, json, created_at, updated_at) VALUES ($id, $n, $j, $c, $u)",
            ("$id", stored.Id), ("$n", name), ("$j", JsonSerializer.Serialize(fs, Json.Options)),
            ("$c", now.ToString("O")), ("$u", now.ToString("O")));
        return stored;
    }

    public StoredFlowsheet? Get(string id)
    {
        using var c = Open();
        using var cmd = Command(c, "SELECT id, name, json, created_at, updated_at FROM flowsheets WHERE id = $id", [("$id", id)]);
        using var r = cmd.ExecuteReader();
        if (!r.Read()) return null;
        return new StoredFlowsheet(r.GetString(0), r.GetString(1),
            JsonSerializer.Deserialize<Flowsheet>(r.GetString(2), Json.Options)!,
            DateTimeOffset.Parse(r.GetString(3)), DateTimeOffset.Parse(r.GetString(4)));
    }

    public List<FlowsheetSummary> List()
    {
        using var c = Open();
        using var cmd = Command(c, "SELECT id, name, updated_at FROM flowsheets ORDER BY updated_at DESC", []);
        using var r = cmd.ExecuteReader();
        var list = new List<FlowsheetSummary>();
        while (r.Read()) list.Add(new FlowsheetSummary(r.GetString(0), r.GetString(1), DateTimeOffset.Parse(r.GetString(2))));
        return list;
    }

    public bool Update(string id, string name, Flowsheet fs)
    {
        using var c = Open();
        using var cmd = Command(c, "UPDATE flowsheets SET name = $n, json = $j, updated_at = $u WHERE id = $id",
            [("$id", id), ("$n", name), ("$j", JsonSerializer.Serialize(fs, Json.Options)), ("$u", DateTimeOffset.UtcNow.ToString("O"))]);
        return cmd.ExecuteNonQuery() == 1;
    }

    public bool Delete(string id)
    {
        using var c = Open();
        using var cmd = Command(c, "DELETE FROM flowsheets WHERE id = $id", [("$id", id)]);
        return cmd.ExecuteNonQuery() == 1;
    }

    // ---- results

    public void SaveResult(string id, SolveResult result, string modelHash)
    {
        using var c = Open();
        Exec(c, """
            INSERT INTO results (flowsheet_id, json, model_hash, solved_at) VALUES ($id, $j, $h, $t)
            ON CONFLICT(flowsheet_id) DO UPDATE SET json = excluded.json, model_hash = excluded.model_hash, solved_at = excluded.solved_at
            """,
            ("$id", id), ("$j", JsonSerializer.Serialize(result, Json.Options)), ("$h", modelHash), ("$t", DateTimeOffset.UtcNow.ToString("O")));
    }

    public StoredResult? GetResult(string id)
    {
        using var c = Open();
        using var cmd = Command(c, "SELECT json, model_hash, solved_at FROM results WHERE flowsheet_id = $id", [("$id", id)]);
        using var r = cmd.ExecuteReader();
        if (!r.Read()) return null;
        return new StoredResult(JsonSerializer.Deserialize<SolveResult>(r.GetString(0), Json.Options)!, r.GetString(1), DateTimeOffset.Parse(r.GetString(2)));
    }

    // ---- API keys (decision D4): only SHA-256 hashes are stored

    public static string HashKey(string key) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(key))).ToLowerInvariant();

    public string AddKey(string device)
    {
        var key = "fs_" + Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).Replace('+', '-').Replace('/', '_').TrimEnd('=');
        using var c = Open();
        Exec(c, "INSERT INTO api_keys (key_hash, device, created_at) VALUES ($h, $d, $t)",
            ("$h", HashKey(key)), ("$d", device), ("$t", DateTimeOffset.UtcNow.ToString("O")));
        return key;
    }

    public int RevokeKeys(string device)
    {
        using var c = Open();
        using var cmd = Command(c, "UPDATE api_keys SET revoked_at = $t WHERE device = $d AND revoked_at IS NULL",
            [("$d", device), ("$t", DateTimeOffset.UtcNow.ToString("O"))]);
        return cmd.ExecuteNonQuery();
    }

    public List<(string Device, DateTimeOffset Created, bool Revoked)> ListKeys()
    {
        using var c = Open();
        using var cmd = Command(c, "SELECT device, created_at, revoked_at IS NOT NULL FROM api_keys ORDER BY created_at", []);
        using var r = cmd.ExecuteReader();
        var list = new List<(string, DateTimeOffset, bool)>();
        while (r.Read()) list.Add((r.GetString(0), DateTimeOffset.Parse(r.GetString(1)), r.GetBoolean(2)));
        return list;
    }

    /// <summary>Returns the device name for a valid, unrevoked key.</summary>
    public string? ValidateKey(string key)
    {
        using var c = Open();
        using var cmd = Command(c, "SELECT device FROM api_keys WHERE key_hash = $h AND revoked_at IS NULL", [("$h", HashKey(key))]);
        return cmd.ExecuteScalar() as string;
    }
}
