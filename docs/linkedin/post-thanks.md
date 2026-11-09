# LinkedIn post: thank you to the first 222 followers, and what shipped

Image: `post-thanks-1200x1500.png` (source `src/post-thanks.html`). The flowsheet is drawn by the app's own canvas code (`src/render-selection.mjs`: benchmark 06 with the compressor, the stream after it and the cooler selected). The pressure curve is the engine's own result for example 20. Every feature line comes from `web/README.md` (Web 1.6 to 1.11.1) and `docs/release-notes/v3.3.0.md`.

The post has 1626 characters (LinkedIn allows 3,000). Put the links in the first comment if you want the post to reach more people.

```text
Thank you, 222.

222 of you followed Flowstead within days of the page going live. I did not expect that for a free process simulator, so thank you for the interest, the shares and the messages.

Here is what has shipped since, in the browser and on Android:

Flowstead Web 1.11.1
• Dynamic simulation in the browser: run a flowsheet through time with PID controllers, and tune them from a step test
• Select several objects, move them together, copy and paste them (even into another project)
• Spreadsheet with formulas on solved results, and scenarios you can compare side by side
• Thermodynamics tools: binary and ternary diagrams, the mixture's critical point, one composition compared across property packages
• HYSYS-comparable enthalpy and entropy, SI, Field or your own units, and a Hold / Active solver

Flowstead Android 3.3.0
• Runs on DWSIM 10.2.15. Air dissolved in water is now within 1.2 % of the IAPWS G7-04 Henry's constants (it was up to 3.8 % off). Everything else moves by less than 0.1 %.

The web version needs nothing to install and runs on your own device, so it works on an iPhone, an iPad or a laptop. Every number still comes from the DWSIM engine, and each result is checked in a test against an independent reference.

Which would help your work most: a quick check of a separator feed, a PSV case, or a dynamic response? Tell me and I will build the next example around it.

Web flowsheet: https://hazemmancy.github.io/Flowstead/flowsheet.html
Android and source: https://github.com/HazemMancy/Flowstead

#ProcessEngineering #ProcessSimulation #OilAndGas #ChemicalEngineering #DWSIM #OpenSource
```
