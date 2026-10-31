# DWSIM 10 trial: liquid densities (D88 step 3, D91)

Each state is a single-phase liquid, solved by Flowstead's engine worker as a lone stream (`engine/trial/density_cases.json`,
`engine/trial/densities.py`), on DWSIM 9.0.5 locally and DWSIM 10.2.12 in the `dwsim10-trial` job. Reference: CoolProp 8.0,
IAPWS-95 for water, the reference equations of state for pure hydrocarbons and GERG-2008 for mixtures (liquid root imposed).
Columns are the deviation from the reference; "PR + COSTALD" is Flowstead's COSTALD option (D39). SRK and PR coincide because
both use the same liquid-density correlations, not the cubic.

| State | Reference (kg/m³) | DWSIM 9 PR | DWSIM 9 PR + COSTALD | DWSIM 9 SRK | DWSIM 10 PR | DWSIM 10 PR + COSTALD | DWSIM 10 SRK |
|---|---|---|---|---|---|---|---|
| water-40C-20bar | 993.0 | -0.12% | +0.04% | -0.12% | -0.12% | +0.20% | -0.12% |
| water-80C-5bar | 972.0 | -0.04% | -1.14% | -0.04% | -0.04% | -0.95% | -0.04% |
| water-150C-10bar | 917.3 | -0.00% | -2.10% | -0.00% | -0.00% | -1.81% | -0.00% |
| propane-20C-15bar | 501.9 | +0.09% | -0.20% | +0.09% | +0.09% | -0.24% | +0.09% |
| propane-60C-30bar | 434.5 | -0.85% | -0.73% | -0.85% | -0.85% | -0.82% | -0.85% |
| nbutane-20C-5bar | 579.1 | +0.19% | -0.06% | +0.19% | +0.19% | -0.04% | +0.19% |
| npentane-25C-2bar | 621.3 | +0.04% | -0.17% | +0.04% | +0.04% | -0.10% | +0.04% |
| nhexane-25C-2bar | 655.0 | +0.31% | +0.34% | +0.31% | +0.31% | +0.31% | +0.31% |
| ndecane-25C-2bar | 726.6 | +0.32% | +0.09% | +0.32% | +0.32% | +0.13% | +0.32% |
| ethane-0C-50bar | 414.7 | -0.73% | +1.04% | -0.73% | -0.73% | -0.68% | -0.73% |
| c3-nc4-20C-20bar | 547.6 | -0.40% | -0.92% | -0.40% | +0.58% | -0.00% | +0.58% |
| ngl-20C-40bar | 567.3 | -1.25% | -3.66% | -1.25% | +2.07% | +0.51% | +2.07% |
| ngl-ethane-rich-0C-50bar | 548.0 | -2.28% | -0.87% | -2.28% | +1.97% | +0.20% | +1.97% |
| lng-minus160C-3bar | 459.5 | -0.81% | -1.13% | -0.81% | +7.56% | +0.38% | +7.56% |
| condensate-40C-10bar | 660.1 | -0.05% | +0.53% | -0.05% | +2.53% | +0.58% | +2.53% |
| live-oil-30C-80bar | 655.9 | +1.48% | +1.48% | +1.48% | +1.77% | +1.77% | +1.77% |
| ngl-0C-30bar | 588.4 | -0.46% | -2.99% | -0.46% | +2.01% | +0.56% | +2.01% |
| ngl-50C-40bar | 530.3 | -5.43% | -5.43% | -5.43% | +0.33% | +0.33% | +0.33% |
| ngl-20C-100bar | 576.8 | -1.31% | -2.94% | -1.31% | +1.84% | +0.66% | +1.84% |
| lng--150C-5bar | 445.6 | -0.93% | -1.48% | -0.93% | +7.76% | +0.30% | +7.76% |
| lng--165C-2bar | 466.3 | -0.79% | -0.98% | -0.79% | +7.44% | +0.41% | +7.44% |
| condensate-20C-5bar | 677.0 | +0.06% | +0.60% | +0.06% | +2.42% | +0.64% | +2.42% |
| condensate-80C-20bar | 625.4 | -0.82% | +0.30% | -0.82% | +2.68% | +0.36% | +2.68% |
| lng-n2-minus162C-2bar | 465.0 | -0.95% | -0.91% | -0.95% | +7.39% | +0.44% | +7.39% |
| ngl-co2-10C-50bar | 557.3 | -1.52% | +1.08% | -1.52% | +2.23% | +0.84% | +2.23% |
| c3-ic4-0C-10bar | 548.3 | +0.08% | -1.36% | +0.08% | +0.69% | -0.02% | +0.69% |
| c5-c8-60C-5bar | 625.0 | +1.78% | +1.64% | +1.78% | +2.88% | +1.86% | +2.88% |
| ethane-propane-minus20C-30bar | 503.0 | -0.75% | +0.69% | -0.75% | +1.31% | -0.16% | +1.31% |
| live-oil-60C-150bar | 638.6 | +1.34% | +1.34% | +1.34% | +1.66% | +1.66% | +1.66% |
| **mean |error|** | | 0.87% | 1.25% | 0.87% | 2.06% | 0.58% | 2.06% |
| **max |error|** | | 5.43% | 5.43% | 5.43% | 7.76% | 1.86% | 7.76% |

**Reading.** Pure liquids are the same on both versions (experimental correlations). DWSIM 10's new default mixing rule
over-predicts hydrocarbon mixtures by 2–3 % and LNG by 7–8 %. DWSIM 10's COSTALD is within 0.9 % on every hydrocarbon mixture
but two (C5–C8 at 60 °C 1.9 %, live oil 1.7–1.8 %) and fixes DWSIM 9's worst case (NGL at 50 °C, 40 bar: −5.4 %), but is
1–2 % low for hot water. Hence D91.
