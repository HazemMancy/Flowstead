// Flowstead engine — browser entry points (D108).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// JavaScript calls Init once, then Call with one WorkerRequest JSON per request and gets one
// WorkerResponse JSON back: the protocol of the server worker and of the phone (EmbeddedHost).

using System.Globalization;
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
        Invariant();
        SingleThreaded();
        var ready = EmbeddedHost.Init(Home);
        SingleThreaded();
        return ready;
    }

    /// <summary>Handles one WorkerRequest (JSON) and returns its WorkerResponse (JSON). Never throws.</summary>
    [JSExport]
    public static string Call(string requestJson)
    {
        Invariant();
        SingleThreaded();
        return EmbeddedHost.Call(requestJson);
    }

    // The browser runs .NET in the visitor's language, but DWSIM reads its own data files ("-0.0044") and Flowstead's JSON
    // with the current culture: under Arabic (a different minus sign and decimal separator) DWSIM failed to load at all.
    // The server and the phone run invariant; so does the browser now, whatever the device's language.
    private static void Invariant()
    {
        CultureInfo.DefaultThreadCurrentCulture = CultureInfo.InvariantCulture;
        CultureInfo.DefaultThreadCurrentUICulture = CultureInfo.InvariantCulture;
        CultureInfo.CurrentCulture = CultureInfo.InvariantCulture;
        CultureInfo.CurrentUICulture = CultureInfo.InvariantCulture;
    }

    // WebAssembly here has one thread: DWSIM must not hand work to tasks that it then waits on.
    // The patch (engine/patches/dwsim-browser.patch) runs DWSIM's own TaskHelper work inline.
    private static void SingleThreaded() => DWSIM.GlobalSettings.Settings.EnableParallelProcessing = false;
}
