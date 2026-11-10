# Getting started with Flowstead — Episode 2: From the well to the inlet separator

Narration script for **Gemini TTS in Google AI Studio** (single speaker). About 5 minutes: 10 segments. Generate
**one clip per segment** (paste the style instructions once, then each segment's text), so each clip can be timed to
its chapter on screen.

Every number below is the engine's, from the case in `ep2-case.json`. Each is checked in
`web/tests/browser.test.mjs` (test 4s) against a hand reference: the isenthalpic choke, the separator's balance,
Souders–Brown, the gas velocity and ρv², and API 521's blowdown target.

## Narrator

- **Name:** Sam
- **Voice:** Charon (Informative). Alternatives: Sadaltager (Knowledgeable) or Iapetus (Clear).
- **Model:** Gemini Flash TTS, temperature about 0.8 for natural pacing.

## Style instructions (paste into "Style instructions")

> You are Sam, a senior process engineer who has spent years on gas plants, showing a younger colleague a new tool
> over their shoulder. Speak in a warm, confident, unhurried voice, at a measured conversational pace, about 140
> words a minute. Sound curious and quietly pleased when a number comes out right, never salesy or loud. Say numbers
> clearly and a little slower than the words around them. Read units in full: "bar absolute" for bara, "degrees
> Celsius" for °C, "metres per second" for m/s, "N P S four" for NPS 4, "rho v squared" for ρv². Pause briefly
> at full stops and a little longer between paragraphs. Follow the bracketed cues for tone; never read them aloud.

## Voice expressions used

`[warmly]` `[curious]` `[confident]` `[thoughtful]` `[emphasis]` `[slightly amused]` `[pleased]` `[serious]`
`[short pause]` `[pause]`

---

## Segments

### 01 · Cold open — "From the well to the inlet separator" (≈ 20 s)
*On screen: dark title card "GETTING STARTED · Episode 2", then the WELL-GAS stream from episode 1.*

> [warmly] Welcome back to Getting Started with Flowstead. [short pause] In episode one we built a well gas and read
> its properties. [pause] Today we take that gas where it really goes: [confident] through the choke, into an inlet
> separator, and out into a line. [short pause] And at every step, we'll check that the numbers hold up.

### 02 · The well makes water too (≈ 25 s)
*On screen: Basis → add Water; WELL-GAS composition with 7 mol % water; 30 °C, 70 bar absolute, 1000 kgmol/h.*

> [thoughtful] A real well doesn't make dry gas. [short pause] So first, in the Basis, we add water, [pause] and give
> the well stream seven mole percent of it. [short pause] Same conditions as before: thirty degrees Celsius, seventy
> bar absolute, one thousand kilogram moles an hour. [slightly amused] Now it's a gas, a condensate and free water,
> all in one pipe.

### 03 · The choke (≈ 30 s)
*On screen: add a valve CHOKE; outlet pressure 40 bara; solve; SEP-FEED worksheet: 18.0 °C.*

> [confident] Next, the choke. [short pause] We drop in a valve, connect it, and set the outlet to forty bar absolute.
> [pause] Solve. [short pause] The gas leaves at [emphasis] eighteen degrees Celsius. [pause] Twelve degrees of
> Joule–Thomson cooling, about zero point four degrees per bar. [thoughtful] And the valve is isenthalpic: the
> enthalpy in equals the enthalpy out, exactly as it should.

### 04 · Phase envelope and hydrates (≈ 40 s)
*On screen: Analysis → Phase envelope on SEP-FEED, hydrate curve on; the stream's point; cricondenbar and
cricondentherm; then methanol 20 wt %.*

> [curious] So where does that put us? [short pause] Let's trace the phase envelope, with the hydrate curve switched on.
> [pause] The envelope reaches a cricondenbar of one hundred and twenty-five bar, and a cricondentherm of forty-six
> degrees. [short pause] Our stream sits inside it: two phase, with free water. [pause] [serious] And here's the line
> that matters. Hydrates form below [emphasis] fourteen degrees at forty bar. [short pause] We're at eighteen. [pause]
> Only four degrees of margin. [thoughtful] Put twenty weight percent methanol in the water, and the hydrate line drops
> seven degrees, to about seven. [short pause] Good to know before winter.

### 05 · The three-phase inlet separator (≈ 30 s)
*On screen: add a three-phase separator V-100; outlets GAS, CONDENSATE, WATER; solve; the three outlet worksheets.*

> [confident] Now the inlet separator. [short pause] A three-phase vessel, with three outlets: gas, condensate and
> water. [pause] Solve. [short pause] Eighteen tonnes an hour of gas, [short pause] nine hundred and fifty-eight
> kilograms an hour of condensate, [short pause] and twelve hundred and fifty-six kilograms an hour of water. [pause]
> [pleased] And every compound balances: what goes in comes out, even the gas dissolved in the water.

### 06 · Sizing V-100 (≈ 35 s)
*On screen: V-100 → Design tab; horizontal; diameter 1.676 m, length 8.4 m; K, allowable and actual gas velocity;
levels; nozzles.*

> [curious] How big does it need to be? [short pause] Open the Design tab and choose horizontal. [pause] Flowstead sizes
> it at [emphasis] one point six eight metres by eight point four metres, tangent to tangent. [short pause] The
> Souders–Brown K is zero point one one four metres per second, so the gas may move at zero point five one, [short pause]
> and it actually moves at zero point four three. [pause] [confident] The levels, the oil–water interface and the
> nozzles come with it: an eight-inch inlet, a five-inch gas outlet.

### 07 · Line sizing of the gas (≈ 45 s)
*On screen: GAS → Analysis → Line sizing; Design, Schedule 40, total length 2 km; criteria 18 m/s and ρv² 15 000 Pa →
NPS 4; then add ΔP 2.5 bar/km → NPS 6.*

> [confident] The gas now needs a line, two kilometres of it. [short pause] In the stream's analysis, line sizing:
> design mode, schedule forty, and our criteria, eighteen metres per second and a rho v squared of fifteen thousand.
> [pause] It picks [emphasis] N P S four: seventeen metres per second. [short pause] [thoughtful] But look at the pressure
> drop. Eighteen bar per kilometre. [slightly amused] Over two kilometres, that line would eat most of our pressure.
> [pause] So we add a third limit, two and a half bar per kilometre, [short pause] and size again. [pleased] N P S six:
> seven metres per second, two point one bar per kilometre, [short pause] and three point nine bar over the whole line.

### 08 · Blowdown to API 521 (≈ 40 s)
*On screen: Safety → Depressuring on V-100; design pressure 50 barg; orifice sized; plot of pressure, temperatures
and flow; target 6.9 barg reached in 14.6 min.*

> [serious] Finally, safety. [short pause] If there's a fire or a leak, V-100 must blow down. [pause] We give it its
> design pressure, fifty bar gauge, [short pause] and Flowstead takes the target from API five twenty-one: the lower of
> six point nine bar gauge and half the design pressure. [pause] It sizes the orifice at sixteen millimetres, and
> reaches six point nine bar gauge in [emphasis] fourteen and a half minutes, inside the fifteen. [short pause]
> [thoughtful] The gas cools to minus fifteen degrees on the way down, while the wall stays above nine. [pause] That's
> the number your material selection will ask about.

### 09 · Report, and the same case on the phone (≈ 20 s)
*On screen: Export → calculation report; then the Android app with the same case open.*

> [warmly] Everything we did is in the calculation report, [short pause] every number straight from DWSIM. [pause] And
> the same case opens on your phone, [short pause] for when the question comes up on site.

### 10 · Outro (≈ 10 s)
*On screen: dark card "THANKS FOR WATCHING · Try it on a case you know · hazemmancy.github.io/Flowstead".*

> [warmly] That's episode two. [short pause] Try it on a case you know, [short pause] and tell me what you find.
> [pause] See you in the next one.

---

## Numbers on screen and in the narration (engine, Web 1.19)

| What | Engine | Checked against |
|---|---|---|
| Choke outlet | 17.99 °C at 40 bara (from 30.00 °C, 70 bara) | Isenthalpic: h out = h in |
| Hydrate temperature at 40 bara | 14.00 °C (structure II); 20 wt % methanol: 6.96 °C | Engine hydrate tests vs measured data (D50) |
| Cricondenbar / cricondentherm | 124.8 bara / 46.0 °C | Engine envelope tests |
| Separator outlets | gas 18 012 kg/h, condensate 958 kg/h, water 1256 kg/h | Every compound balances (D150) |
| V-100 (horizontal) | 1.676 m × 8.4 m T/T; K 0.114 m/s; allowable 0.513 m/s; actual 0.429 m/s | Souders–Brown by hand |
| Gas line, 18 m/s and ρv² 15 000 Pa | NPS 4: 17.2 m/s, ρv² 10 500 Pa, 18.2 bar/km | ṁ/(ρA) and ρv² by hand |
| Gas line, plus 2.5 bar/km | NPS 6: 7.28 m/s, 2.14 bar/km, 3.91 bar over 2 km | The criteria applied by hand |
| Blowdown (design 50 barg) | target 6.9 barg (API 521), 14.56 min, orifice 15.8 mm, gas −15.4 °C, wall 9.2 °C | API 521 target; ≤ 15 min |

