# Episode 2 — one-take script for Gemini TTS (Google AI Studio)

One audio file, generated in one go. Chapters are separated by `[long pause]` so the cut points are easy to find; the
video is cut to the audio. Every sentence carries its own expression cue. Numbers: see `script.md`.

## Character

Sam — a senior process engineer, years on gas plants, showing a younger colleague a new tool over their shoulder.
Voice: **Charon** (Informative). Alternatives: Sadaltager (Knowledgeable), Iapetus (Clear). Temperature about 0.8.

## Style instructions

```
You are Sam, a senior process engineer with years on gas plants, showing a younger colleague a new simulation tool over their shoulder. Warm, confident and unhurried; a measured conversational pace, about 140 words a minute. Curious when asking, quietly pleased when a number comes out right, serious about safety; never salesy, never loud. Say every number clearly and slightly slower than the words around it. Read units in full: "bar absolute", "bar gauge", "degrees Celsius", "metres per second", "kilograms an hour". Say "N P S" as letters, "rho v squared" for the Greek letters, "API five twenty-one" for the standard. Pause briefly at full stops. At every [long pause], stop for two full seconds of silence before the next part. Follow every bracketed cue for tone and timing, and never read the cues aloud.
```

## Script

```
[warmly] Welcome back to Getting Started with Flowstead. [friendly] In episode one, we built a well gas and read its properties. [confident] Today, we take that gas where it really goes: through the choke, into an inlet separator, and out into a line. [reassuring] And at every step, we'll check that the numbers hold up.

[long pause]

[thoughtful] A real well doesn't make dry gas. [matter-of-fact] So first, in the Basis, we add water, and give the well stream seven mole percent of it. [steady] Same conditions as before: thirty degrees Celsius, seventy bar absolute, one thousand kilogram moles an hour. [slightly amused] Now it's a gas, a condensate and free water, all in one pipe.

[long pause]

[confident] Next, the choke. [matter-of-fact] We drop in a valve, connect it, and set the outlet to forty bar absolute. [short pause] [brisk] Solve. [emphasis] The gas leaves at eighteen degrees Celsius. [explaining] That's twelve degrees of Joule–Thomson cooling, about zero point four degrees per bar. [pleased] And the enthalpy in equals the enthalpy out, exactly as a valve should.

[long pause]

[curious] So, where does that put us? [inviting] Let's trace the phase envelope, with the hydrate curve switched on. [explaining] The envelope reaches a cricondenbar of one hundred and twenty-five bar, and a cricondentherm of forty-six degrees. [matter-of-fact] Our stream sits inside it: two phase, with free water. [serious] And here's the line that matters. [emphasis] Hydrates form below fourteen degrees at forty bar. [short pause] [concerned] We're at eighteen: only four degrees of margin. [thoughtful] Put twenty weight percent methanol in the water, and the hydrate line drops seven degrees, to about seven. [wry] Good to know before winter.

[long pause]

[confident] Now, the inlet separator. [matter-of-fact] A three-phase vessel, with three outlets: gas, condensate and water. [short pause] [brisk] Solve. [steady] Eighteen tonnes an hour of gas, nine hundred and fifty-eight kilograms an hour of condensate, and twelve hundred and fifty-six kilograms an hour of water. [pleased] And every compound balances: what goes in comes out, even the gas dissolved in the water.

[long pause]

[curious] How big does it need to be? [matter-of-fact] Open the Design tab, and choose horizontal. [emphasis] Flowstead sizes it at one point six eight metres, by eight point four metres, tangent to tangent. [explaining] The Souders–Brown K is zero point one one four metres per second, so the gas may move at zero point five one, and it actually moves at zero point four three. [satisfied] The levels, the oil–water interface and the nozzles come with it: an eight-inch inlet, and a five-inch gas outlet.

[long pause]

[confident] The gas now needs a line, two kilometres of it. [matter-of-fact] In the stream's analysis, open line sizing: design mode, schedule forty, and our criteria, eighteen metres per second, and a rho v squared of fifteen thousand. [emphasis] It picks N P S four, at seventeen metres per second. [short pause] [thoughtful] But look at the pressure drop: eighteen bar per kilometre. [slightly amused] Over two kilometres, that line would eat most of our pressure. [decisive] So we add a third limit, two and a half bar per kilometre, and size again. [pleased] N P S six: seven metres per second, two point one bar per kilometre, and three point nine bar over the whole line.

[long pause]

[serious] Finally, safety. [grave] If there's a fire or a leak, V-100 must blow down. [matter-of-fact] We give it its design pressure, fifty bar gauge. [explaining] Flowstead takes the target from API five twenty-one: the lower of six point nine bar gauge, and half the design pressure. [confident] It sizes the orifice at sixteen millimetres. [emphasis] And it reaches six point nine bar gauge in fourteen and a half minutes, inside the fifteen. [thoughtful] The gas cools to minus fifteen degrees on the way down, while the wall stays above nine. [knowing] That's the number your material selection will ask about.

[long pause]

[warmly] Everything we did is in the calculation report, every number straight from DWSIM. [friendly] And the same case opens on your phone, for when the question comes up on site.

[long pause]

[warmly] That's episode two. [inviting] Try it on a case you know, and tell me what you find. [cheerful] See you in the next one.
```
