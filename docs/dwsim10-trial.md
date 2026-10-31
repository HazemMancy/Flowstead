# DWSIM 10: liquid densities (D88 step 3, D91)

Each state is a single-phase liquid, solved by Flowstead's engine worker as a lone Peng–Robinson stream
(`engine/tools/densities/`: `density_cases.json`, `densities.py`; `compare.py` makes the comparison). Reference: CoolProp 8.0,
IAPWS-95 for water, the reference equations of state for pure hydrocarbons and GERG-2008 for mixtures (liquid root
imposed). Each column is the deviation from the reference. SRK gives the same liquid densities as PR here: both use the
same correlations, not the cubic.

| Liquid | Conditions | Reference (kg/m³) | DWSIM 9.0.5 default (Flowstead 2.x) | DWSIM 10.2.12 default | DWSIM 10.2.12 COSTALD | **Flowstead 3.0** (D91) |
|---|---|---|---|---|---|---|
| water-40C-20bar | 40 °C, 20 bar | 993.0 | -0.12 % | -0.12 % | +0.20 % | -0.12 % |
| water-80C-5bar | 80 °C, 5 bar | 972.0 | -0.04 % | -0.04 % | -0.95 % | -0.04 % |
| water-150C-10bar | 150 °C, 10 bar | 917.3 | -0.00 % | -0.00 % | -1.81 % | -0.00 % |
| propane-20C-15bar | 20 °C, 15 bar | 501.9 | +0.09 % | +0.09 % | -0.24 % | +0.09 % |
| propane-60C-30bar | 60 °C, 30 bar | 434.5 | -0.85 % | -0.85 % | -0.82 % | -0.85 % |
| nbutane-20C-5bar | 20 °C, 5 bar | 579.1 | +0.19 % | +0.19 % | -0.04 % | +0.19 % |
| npentane-25C-2bar | 25 °C, 2 bar | 621.3 | +0.04 % | +0.04 % | -0.10 % | +0.04 % |
| nhexane-25C-2bar | 25 °C, 2 bar | 655.0 | +0.31 % | +0.31 % | +0.31 % | +0.31 % |
| ndecane-25C-2bar | 25 °C, 2 bar | 726.6 | +0.32 % | +0.32 % | +0.13 % | +0.32 % |
| ethane-0C-50bar | 0 °C, 50 bar | 414.7 | -0.73 % | -0.73 % | -0.68 % | -0.73 % |
| c3-nc4-20C-20bar | 20 °C, 20 bar | 547.6 | -0.40 % | -0.40 % | -0.00 % | -0.00 % |
| ngl-20C-40bar | 20 °C, 40 bar | 567.3 | -1.25 % | -0.57 % | +0.51 % | +0.51 % |
| ngl-ethane-rich-0C-50bar | 0 °C, 50 bar | 548.0 | -2.28 % | -2.28 % | +0.20 % | +0.20 % |
| lng-minus160C-3bar | -160 °C, 3 bar | 459.5 | -0.81 % | -0.81 % | +0.38 % | +0.38 % |
| condensate-40C-10bar | 40 °C, 10 bar | 660.1 | -0.05 % | -0.05 % | +0.58 % | +0.58 % |
| live-oil-30C-80bar | 30 °C, 80 bar | 655.9 | +1.48 % | +0.31 % | +1.77 % | +1.77 % |
| ngl-0C-30bar | 0 °C, 30 bar | 588.4 | -0.46 % | -0.46 % | +0.56 % | +0.56 % |
| ngl-50C-40bar | 50 °C, 40 bar | 530.3 | -5.43 % | -1.15 % | +0.33 % | +0.33 % |
| ngl-20C-100bar | 20 °C, 100 bar | 576.8 | -1.31 % | -0.69 % | +0.66 % | +0.66 % |
| lng--150C-5bar | -150 °C, 5 bar | 445.6 | -0.93 % | -0.93 % | +0.30 % | +0.30 % |
| lng--165C-2bar | -165 °C, 2 bar | 466.3 | -0.79 % | -0.79 % | +0.41 % | +0.41 % |
| condensate-20C-5bar | 20 °C, 5 bar | 677.0 | +0.06 % | +0.06 % | +0.64 % | +0.64 % |
| condensate-80C-20bar | 80 °C, 20 bar | 625.4 | -0.82 % | -0.30 % | +0.36 % | +0.36 % |
| lng-n2-minus162C-2bar | -162 °C, 2 bar | 465.0 | -0.95 % | -0.95 % | +0.44 % | +0.44 % |
| ngl-co2-10C-50bar | 10 °C, 50 bar | 557.3 | -1.52 % | -0.62 % | +0.84 % | +0.84 % |
| c3-ic4-0C-10bar | 0 °C, 10 bar | 548.3 | +0.08 % | +0.08 % | -0.02 % | -0.02 % |
| c5-c8-60C-5bar | 60 °C, 5 bar | 625.0 | +1.78 % | +1.78 % | +1.86 % | +1.86 % |
| ethane-propane-minus20C-30bar | -20 °C, 30 bar | 503.0 | -0.75 % | -0.75 % | -0.16 % | -0.16 % |
| live-oil-60C-150bar | 60 °C, 150 bar | 638.6 | +1.34 % | +0.12 % | +1.66 % | +1.66 % |
| **mean \|error\|** | | | 0.87 % | 0.54 % | 0.58 % | 0.50 % |
| **worst** | | | 5.43 % | 2.28 % | 1.86 % | 1.86 % |

**Reading.** Pure liquids are the same on both DWSIM versions (experimental correlations). DWSIM 10's own default mixes
by volume and keeps dissolved light compounds in the sum, fixing DWSIM 9's worst case (NGL at 50 °C, 40 bar: −5.4 %),
but is still 2.3 % low for an ethane-rich NGL. DWSIM 10's COSTALD is within 0.9 % on every hydrocarbon mixture except a
C5–C8 liquid (1.9 %) and the live oils (1.7–1.8 %), but 1–2 % low for water above 80 °C. Flowstead 3.0 therefore uses
COSTALD for liquid mixtures that are not mostly water and DWSIM's default for pure and water-rich liquids (D91).

A first comparison during the trial reported DWSIM 10's default as 2–8 % high for mixtures. That came from Flowstead
itself: it still handed DWSIM mass fractions, a correction for DWSIM 9's mole-fraction mixing that DWSIM 10 no longer
needs, so the volumes were weighted twice. The table above is without it.
