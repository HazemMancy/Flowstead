// Flowstead web units (D108): the quantities the web tools display, mirroring android/core's Units.kt
// (same factors, offsets and decimals). Checked against shared/units/test-vectors.json.
// SPDX-License-Identifier: GPL-3.0-or-later
const LB_KG = 0.45359237, FT_M = 0.3048, FT3_M3 = FT_M ** 3, PSI_PA = 6894.757293168361, BAR_PA = 1e5;
const BTU_IT_J = 1055.05585262, HOUR_S = 3600, DAY_S = 86400, US_GAL_M3 = 3.785411784e-3, BBL_M3 = 42 * US_GAL_M3, CAL_IT_J = 4.1868;
const R = 8.314462618;
const metricMolarVolume = (R * 288.15) / 101325;                                      // 15 °C, 101.325 kPa (ISO 13443)
const fieldMolarVolume = (R * ((60 + 459.67) * 5) / 9) / (14.696 * PSI_PA);         // 60 °F, 14.696 psia

export const PATM_DEFAULT = 101325;
const u = (id, symbol, quantity, factor, decimals, offset = () => 0) => ({ id, symbol, quantity, factor, decimals, offset });

export const UNITS = [
  u('K', 'K', 'Temperature', 1, 2), u('C', '°C', 'Temperature', 1, 2, () => 273.15),
  u('F', '°F', 'Temperature', 5 / 9, 2, () => (459.67 * 5) / 9), u('R', '°R', 'Temperature', 5 / 9, 2),
  u('Pa', 'Pa', 'Pressure', 1, 0), u('kPa', 'kPa', 'Pressure', 1e3, 2), u('kPag', 'kPag', 'Pressure', 1e3, 2, (c) => c.patm),
  u('MPa', 'MPa', 'Pressure', 1e6, 4), u('bara', 'bara', 'Pressure', BAR_PA, 3), u('barg', 'barg', 'Pressure', BAR_PA, 3, (c) => c.patm),
  u('psia', 'psia', 'Pressure', PSI_PA, 2), u('psig', 'psig', 'Pressure', PSI_PA, 2, (c) => c.patm),
  u('mol/s', 'mol/s', 'MolarFlow', 1, 4), u('kgmol/h', 'kgmol/h', 'MolarFlow', 1e3 / HOUR_S, 2),
  u('lbmol/h', 'lbmol/h', 'MolarFlow', (LB_KG * 1e3) / HOUR_S, 2),
  u('Sm3/h', 'Sm³/h', 'MolarFlow', 1 / (metricMolarVolume * HOUR_S), 1), u('Sm3/d', 'Sm³/d', 'MolarFlow', 1 / (metricMolarVolume * DAY_S), 0),
  u('MMSCFD', 'MMSCFD', 'MolarFlow', (1e6 * FT3_M3) / (fieldMolarVolume * DAY_S), 4),
  u('kg/s', 'kg/s', 'MassFlow', 1, 4), u('kg/h', 'kg/h', 'MassFlow', 1 / HOUR_S, 1), u('t/d', 't/d', 'MassFlow', 1e3 / DAY_S, 2),
  u('lb/h', 'lb/h', 'MassFlow', LB_KG / HOUR_S, 1),
  u('m3/s', 'm³/s', 'VolumeFlow', 1, 5), u('m3/h', 'm³/h', 'VolumeFlow', 1 / HOUR_S, 3), u('bbl/d', 'bbl/d', 'VolumeFlow', BBL_M3 / DAY_S, 1),
  u('USGPM', 'USGPM', 'VolumeFlow', US_GAL_M3 / 60, 2),
  u('J/kg', 'J/kg', 'MassEnthalpy', 1, 0), u('kJ/kg', 'kJ/kg', 'MassEnthalpy', 1e3, 3), u('Btu/lb', 'Btu/lb', 'MassEnthalpy', BTU_IT_J / LB_KG, 3),
  u('J/mol', 'J/mol', 'MolarEnthalpy', 1, 1), u('kJ/kgmol', 'kJ/kgmol', 'MolarEnthalpy', 1, 1),
  u('Btu/lbmol', 'Btu/lbmol', 'MolarEnthalpy', BTU_IT_J / (LB_KG * 1e3), 1),
  u('kJ/kg.K', 'kJ/kg·K', 'MassEntropy', 1e3, 4), u('Btu/lb.F', 'Btu/lb·°F', 'MassEntropy', CAL_IT_J * 1e3, 4),
  u('cp:kJ/kg.K', 'kJ/kg·K', 'MassHeatCapacity', 1e3, 4),
  u('kg/m3', 'kg/m³', 'Density', 1, 2), u('lb/ft3', 'lb/ft³', 'Density', LB_KG / FT3_M3, 3),
  u('kg/kmol', 'kg/kmol', 'MolecularWeight', 1, 3),
  u('Pa.s', 'Pa·s', 'Viscosity', 1, 6), u('cP', 'cP', 'Viscosity', 1e-3, 4),
  u('W/m.K', 'W/m·K', 'ThermalConductivity', 1, 4), u('Btu/h.ft.F', 'Btu/h·ft·°F', 'ThermalConductivity', BTU_IT_J / (HOUR_S * FT_M * (5 / 9)), 4),
  u('m2/s', 'm²/s', 'KinematicViscosity', 1, 9), u('cSt', 'cSt', 'KinematicViscosity', 1e-6, 4),
  u('N/m', 'N/m', 'SurfaceTension', 1, 5), u('mN/m', 'mN/m', 'SurfaceTension', 1e-3, 3), u('dyne/cm', 'dyne/cm', 'SurfaceTension', 1e-3, 3),
  u('frac', '', 'Fraction', 1, 4), u('%', '%', 'Fraction', 0.01, 2), u('ratio', '', 'Ratio', 1, 3),
];
const BY_ID = new Map(UNITS.map((x) => [x.id, x]));
export const unit = (id) => { const x = BY_ID.get(id); if (!x) throw new Error(`Unknown unit ${id}`); return x; };
export const unitsOf = (quantity) => UNITS.filter((x) => x.quantity === quantity);
const ctx = (patm) => ({ patm: patm ?? PATM_DEFAULT });

/** Value in `id` → SI. */
export function toSi(value, id, patm) { const x = unit(id); return value * x.factor + x.offset(ctx(patm)); }
/** SI → value in `id`. */
export function fromSi(si, id, patm) { const x = unit(id); return (si - x.offset(ctx(patm))) / x.factor; }

/** Formats an SI value in `id` with the unit's decimals (thin grouping, never exponent for ordinary values). */
export function format(si, id, patm) {
  if (si === null || si === undefined || !Number.isFinite(si)) return '—';
  const v = fromSi(si, id, patm);
  const d = unit(id).decimals;
  const a = Math.abs(v);
  if (a !== 0 && (a < 10 ** -d || a >= 1e9)) return v.toPrecision(4);
  return v.toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });
}

/** Display units for the web tools (SI-metric, as the app's SI set with bara and °C). */
export const DISPLAY = {
  Temperature: 'C', Pressure: 'bara', MolarFlow: 'kgmol/h', MassFlow: 'kg/h', VolumeFlow: 'm3/h',
  MassEnthalpy: 'kJ/kg', MolarEnthalpy: 'kJ/kgmol', MassEntropy: 'kJ/kg.K', MassHeatCapacity: 'cp:kJ/kg.K',
  Density: 'kg/m3', MolecularWeight: 'kg/kmol', Viscosity: 'cP', ThermalConductivity: 'W/m.K',
  KinematicViscosity: 'cSt', SurfaceTension: 'mN/m', Fraction: 'frac', Ratio: 'ratio',
};
