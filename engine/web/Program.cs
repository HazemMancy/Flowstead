// Flowstead engine — browser entry points (D108).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// JavaScript calls Init once, then Call with one WorkerRequest JSON per request and gets one
// WorkerResponse JSON back: the protocol of the server worker and of the phone (EmbeddedHost).

using System.Runtime.InteropServices.JavaScript;
using Flowstead.Engine.Worker;

public static partial class FlowsteadEngine
{
    private const string Home = "/dwsim";

    public static void Main() { }

    /// <summary>Loads DWSIM. Returns the ready response (id 0) or an error response.</summary>
    [JSExport]
    public static string Init()
    {
        // The browser bundle already holds every assembly; EmbeddedHost only checks for this marker.
        Directory.CreateDirectory(Home);
        var marker = Path.Combine(Home, "DWSIM.Thermodynamics.dll");
        if (!File.Exists(marker)) File.WriteAllBytes(marker, []);
        SingleThreaded();
        var ready = EmbeddedHost.Init(Home);
        SingleThreaded();
        return ready;
    }

    /// <summary>Handles one WorkerRequest (JSON) and returns its WorkerResponse (JSON). Never throws.</summary>
    [JSExport]
    public static string Call(string requestJson)
    {
        SingleThreaded();
        return EmbeddedHost.Call(requestJson);
    }

    // WebAssembly here has one thread: DWSIM must not hand work to tasks that it then waits on.
    // The patch (engine/patches/dwsim-browser.patch) runs DWSIM's own TaskHelper work inline.
    private static void SingleThreaded() => DWSIM.GlobalSettings.Settings.EnableParallelProcessing = false;
}
