# Worked call sequences

Run each in the Flowstead tab (`const api = window.flowstead.api;` first). Every call that changes the case is one
undo step; `await` the long ones.

## 1. Build and solve a compressor and aftercooler (benchmark 06)

```js
const api = window.flowstead.api;
api.newCase({ propertyPackage: 'Peng-Robinson (PR)', compounds: ['Methane', 'Ethane', 'Propane'], name: 'K-100 train' });
api.add('material_stream', { tag: 'SUCTION' });
api.add('compressor', { tag: 'K-100' });
api.connect('SUCTION', 'K-100', { side: 'inlet', index: 0 });
api.connectNew('K-100', { side: 'outlet', index: 0, tag: 'DISCHARGE' });
api.add('cooler', { tag: 'E-100' });
api.connect('DISCHARGE', 'E-100', { side: 'inlet', index: 0 });
api.connectNew('E-100', { side: 'outlet', index: 0, tag: 'COOLED' });
api.setSpecs('SUCTION', { temperature: [30, 'C'], pressure: [5, 'bara'], molarFlow: [500, 'kgmol/h'] });
api.setComposition('SUCTION', { Methane: 0.8, Ethane: 0.15, Propane: 0.05 });
api.setSpecs('K-100', { outletPressure: [15, 'bara'], efficiency: [75, '%'] });
api.setSpecs('E-100', { outletTemperature: [40, 'C'], pressureDrop: [0, 'bar'] });
api.layout();
const s = await api.solve();                 // {status: 'converged', notReady: {}, ...}
const k = api.results('K-100');              // k.properties.power.si (W), k.properties.adiabaticHead…
const driver = api.sizing('K-100');          // lines: Shaft power, Margin (API 617), Driver at least, Standard motor
```

Judge: driver ≥ 1.10 × power (API 617); discharge temperature against the project's limit (NEEDS CRITERION if none);
surge margin if the compressor has a curve.

## 2. Review an open case

```js
const api = window.flowstead.api;
const c = api.case();                         // objects, specs, joins, what each still needs
const issues = api.check();                   // missing specs, loose ends, engine warnings
const s = await api.solve();
const all = api.allResults();                 // every object's results, SI and display units
```

Then go through `checks.md` section by section for the equipment present, and report in the table format of SKILL.md.

## 3. Size a relief valve on a separator for fire

```js
const api = window.flowstead.api;
await api.solve();
const r = await api.safety('relief', {
  tag: 'PSV-100', protectedId: 'V-100', setPressure: 40e5,           // Pa absolute
  vessel: { diameter: 1.5, length: 4, liquidLevel: 1.2 },            // m; vertical on grade
  scenarios: [{ kind: 'fire', name: 'Fire' }, { kind: 'blockedOutlet', name: 'Blocked outlet' }],
  inletLine: { insideDiameter: 0.0525, length: 2, fittingsLength: 1, roughness: 4.57e-5, elevationChange: 0 },
});
// r.result.governing, r.result.orifice (API 526 letter), r.result.requiredArea (m²), r.result.inlet (3 % check), r.report (the app's report)
```

Cross-check you may add (label it yours): fire heat Q = 43.2 kW · F · A^0.82 with A the wetted area
(1.084 D² + π D (h − D/4) for a vertical vessel with 2:1 heads); it should equal `r.result.scenarios[0].details.heatInput`.

## 4. Line sizing against the project's criteria

```js
const api = window.flowstead.api;
api.setLineCriteria('gas', { maxVelocity: 20, maxMomentumFlux: 15000, erosionalC: 100 });   // from the user's design basis
await api.solve();
const line = api.sizing('PIPE-100');          // {service, checks: [{label, value, limit, passes}]}
```

## 5. A case study

```js
const api = window.flowstead.api;
const report = await api.caseStudy({
  name: 'Discharge pressure', objectId: 'K-100', specKey: 'outletPressure',
  from: { value: 10, unit: 'bara' }, to: { value: 20, unit: 'bara' }, steps: 5,
  outputs: [{ objectId: 'K-100', key: 'power' }, { objectId: 'DISCHARGE', key: 'temperature' }],
});
// report.sections: the table and the plot, every value the engine's
```

## 6. Work inside a sub-flowsheet

```js
const api = window.flowstead.api;
api.group(['E-100']);                         // puts E-100 in a new block, streams crossing its edge matched
api.moveInto(['K-100'], 'SUB-1');             // drags K-100 in too
api.openLevel('SUB-1');                       // new objects now go inside SUB-1
api.openLevel(null);                          // back to the main flowsheet
```
