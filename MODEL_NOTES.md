# Material equations, units and calibration assumptions

The four model options share the corrected numerical engine. An option selects a candidate empirical parameter set, not a verified prediction of Inconel 617. The equations below describe the implementation included in this repository.

## Implementation

All four parameter sets are implemented in [MaterialModels.java](src/inconel617/MaterialModels.java). They share the numerical engine in [DrxSimulation.java](src/inconel617/DrxSimulation.java). The recorded comparison build and executable fingerprints are documented in [VERIFICATION.md](VERIFICATION.md).

## Active FINAL_CA equations

Here T is kelvin, r is the supplied positive strain rate in s⁻¹, b = 2.5 × 10⁻¹⁰ m, R = 8.314 J mol⁻¹ K⁻¹ and k_B = 1.38064852 × 10⁻²³ J K⁻¹. Logarithms are natural. Taylor alpha = 0.5 is distinct from the constitutive stress coefficient alpha_A = 0.005439 MPa⁻¹.

| Quantity | Implemented expression | Units / meaning |
| --- | --- | --- |
| ln Z | ln r + 493039/(R T) | Numerical convention for Z; Q = 493039 J mol⁻¹ |
| k2 | exp(8.9 − 0.15 ln Z) | Per unit true strain |
| sigma_p | asinh(exp((ln Z − 40.26)/3.86))/0.005439 | MPa; fitted stress scale, not guaranteed simulated peak |
| G | 78.9 × 10⁹ [1 − 0.64 (T − 300)/(1453 + 273.15)] | Pa; empirical modulus fit |
| k1 | k2 sigma_p × 10⁶/(0.5 G b) | m⁻¹ per unit true strain |
| rho_sat | (k1/k2)² | m⁻²; diagnostic scale, no imposed density ceiling |
| rho_cr | (0.95 k1/k2)² | m⁻²; nucleation threshold |
| f_ss | min(1, 0.0096 ln Z + 0.526) | Multiplier on DRX hardening coefficient |
| Q_f | 130/[1 + (T/1333)^37] + 620 | kJ mol⁻¹ |
| D_eff | 1.7 × 10⁻²² exp[−1000 Q_f/(R T) + 0.091 Q_f] | Effective prefactor interpretation unresolved |
| M | D_eff b/(k_B T) | Effective m Pa⁻¹ s⁻¹ required by v = M P |
| n_p | 0.61/[1 + (T/1338)^59] + 0.39 | Dimensionless; depends only on T |
| nDot | 3.83 × 10¹⁵ exp[−120000/(R T)] (r/0.2)^n_p | Assumed m⁻² s⁻¹ in the corrected reactive-band formulation |

For MAIN9, rho_cr instead uses `(0.0026 ln Z + 0.81)² rho_sat`, and n_p has the additional multiplier `(r/0.001)^0.05`. CAC2 and VARIANT use their own distinct constitutive laws. FINAL_CA and MAIN9 share the stress scale, recovery law, modulus and mobility at the same supplied temperature/rate.

## Intentional corrections and unresolved fits

- The entered strain rate is used directly: an input of 0.1 s⁻¹ produces an applied rate of 0.1 s⁻¹.
- Celsius converts with 273.15. Physical lengths inside the engine are metres, including the initial diameter in the size-dependent hardening term.
- The stress law uses exponent 3.86 and ln A = 40.26. These fitted constants must be assessed together against stress–strain data; replacing the exponent alone does not constitute a consistent refit.
- The constitutive coefficient alpha_A = 0.005439 has units MPa⁻¹, equivalent to 5.439 × 10⁻⁹ Pa⁻¹. Taylor alpha = 0.5 is a different, dimensionless coefficient.
- Mobility uses the denominator `k_B T`. To make M dimensionally m Pa⁻¹ s⁻¹, D_eff must have units m³ s⁻¹, as for a boundary width multiplied by diffusivity. The effective boundary width and prefactor interpretation have not been established; this remains a calibration assumption.
- The 1453°C constant is used as an empirical fit coefficient, not asserted as the actual melting point of alloy 617. The manufacturer lists a melting range of 1332–1380°C; the modulus fit needs independent assessment before replacement.
- Per-grain random hardening/density modifiers are excluded. Initial density remains 10⁶–10⁷ m⁻²; nuclei begin at 1 m⁻². The latter is a low-density numerical assumption, not a measured nucleus density. The nominal yield density is diagnostic only.
- A 1 μm nucleus diameter and 2 μm reactive band width are held physically fixed between domain/spacing studies. Both require calibration. Grain-ID colors are visualization colors, not crystallographic inverse-pole-figure colors.

These choices make the implementation reproducible. They do not resolve identifiability between nucleation, mobility, initial microstructure and omitted mechanisms. Validate stress curves, DRX area fraction and consistently defined grain-size distributions against independent measurements.

## References

- [Revised technical report](report/inconel617_revised_technical_report.pdf), governing equations, assumptions and numerical results.
- [Special Metals: INCONEL alloy 617 technical bulletin](https://www.specialmetals.com/documents/technical-bulletins/inconel/inconel-alloy-617.pdf), composition, melting range and modulus data.
- [Babu et al. (2016), Characterization of hot deformation behavior of alloy 617](https://doi.org/10.1016/j.msea.2016.04.004).
- [Du et al. (2026), Hot deformation behavior and microstructure evolution of Inconel 617](https://doi.org/10.1557/s43578-026-01878-7), evidence that recovery/continuous DRX and twinning are relevant beyond this fixed-grid DDRX engine.
