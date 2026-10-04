# Material-law provenance and units

The four model options share the corrected numerical engine. An option selects a candidate empirical parameter set, not a verified prediction of Inconel 617. The original sources and reports are preserved.

## Final-CA source identity

`final_ca` imports the active expressions in `files654/final-CA-15-4/untitled/src/Main.java`, inspected on 4 October 2026. Its source SHA-256 at import is `50d4c90f0c5c432eef81b68d6a523801665c7e5d4eceeb884c9cb7b75606bf89`.

The alternative `files654/Main.java` has SHA-256 `6df0fde97571719477b4e6bfc8b38e1a4e64b5fe0e02edf89c0eb18d5ed80338`. It has different diffusion/nucleation fits, a 1600 × 1600 grid, and critical factor 0.84. It is not silently merged with final_ca.

The saved nested `Main.class` uses `np = 0.75 / (1 + (T/1383)^107) + 0.18`, whereas the active nested source uses the expression below. The legacy `.xls` files contain tab-separated simulation output with no temperature, rate, seed or source identity. They are historical output of uncertain provenance, not an experimental validation dataset.

## Active FINAL_CA equations

Here T is kelvin, r is the supplied positive strain rate in s⁻¹, b = 2.5 × 10⁻¹⁰ m, R = 8.314 J mol⁻¹ K⁻¹ and k_B = 1.38064852 × 10⁻²³ J K⁻¹. Logarithms are natural. Taylor alpha = 0.5 is distinct from the constitutive stress coefficient alpha_A = 0.005439 MPa⁻¹.

| Quantity | Implemented expression | Units / meaning |
| --- | --- | --- |
| ln Z | ln r + 493039/(R T) | Legacy numerical convention for Z; Q = 493039 J mol⁻¹ |
| k2 | exp(8.9 − 0.15 ln Z) | Per unit true strain |
| sigma_p | asinh(exp((ln Z − 40.26)/3.86))/0.005439 | MPa; fitted stress scale, not guaranteed simulated peak |
| G | 78.9 × 10⁹ [1 − 0.64 (T − 300)/(1453 + 273.15)] | Pa; inherited empirical modulus fit |
| k1 | k2 sigma_p × 10⁶/(0.5 G b) | m⁻¹ per unit true strain |
| rho_sat | (k1/k2)² | m⁻²; diagnostic scale, no imposed density ceiling |
| rho_cr | (0.95 k1/k2)² | m⁻²; nucleation threshold |
| f_ss | min(1, 0.0096 ln Z + 0.526) | Multiplier on DRX hardening coefficient |
| Q_f | 130/[1 + (T/1333)^37] + 620 | kJ mol⁻¹ |
| D_eff | 1.7 × 10⁻²² exp[−1000 Q_f/(R T) + 0.091 Q_f] | Effective prefactor interpretation unresolved |
| M | D_eff b/(k_B T) | Effective m Pa⁻¹ s⁻¹ required by v = M P |
| n_p | 0.61/[1 + (T/1338)^59] + 0.39 | Dimensionless; depends only on T |
| nDot | 3.83 × 10¹⁵ exp[−120000/(R T)] (r/0.2)^n_p | Assumed m⁻² s⁻¹ in the corrected reactive-band formulation |

For MAIN9, rho_cr instead uses `(0.0026 ln Z + 0.81)² rho_sat`, and n_p has the additional multiplier `(r/0.001)^0.05`. CAC2 and VARIANT retain their separate archived laws. FINAL_CA and MAIN9 share the stress scale, recovery law, modulus and mobility at the same supplied temperature/rate.

## Intentional corrections and unresolved fits

- The entered strain rate is used directly. The nested source doubled it; a run entered as 0.1 s⁻¹ consequently used 0.2 s⁻¹. Reusing fits tuned with that behavior requires renewed calibration.
- Celsius converts with 273.15. Physical lengths inside the engine are metres, including the initial diameter in the size-dependent hardening term.
- The active stress law uses exponent 3.86 and ln A = 40.26. The final PDF reports n = 3.91342. Replacing n alone would change the fit; the underlying stress data and consistent intercept are needed to resolve this.
- The PDF labels alpha_A = 0.005439 as Pa⁻¹. It is MPa⁻¹ in the implemented stress expression, equivalent to 5.439 × 10⁻⁹ Pa⁻¹. Taylor alpha = 0.5 is a different coefficient.
- The PDF mobility denominator `K2 T` is inconsistent with the code. It should use `k_B T` in this formulation. To make M dimensionally m Pa⁻¹ s⁻¹, D_eff must have units m³ s⁻¹, as for a boundary width multiplied by diffusivity. The inherited report calls it self-diffusivity without establishing that width or the prefactor units. This ambiguity remains unresolved.
- The 1453°C constant is retained as an inherited fit coefficient, not asserted as the actual melting point of alloy 617. The manufacturer lists a melting range of 1332–1380°C; the modulus fit needs independent assessment before replacement.
- Per-grain random hardening/density modifiers are excluded. Initial density remains 10⁶–10⁷ m⁻²; nuclei begin at 1 m⁻². The latter is an inherited low-density numerical assumption, not a measured nucleus density. The nominal yield density is diagnostic only.
- A 1 μm nucleus diameter and 2 μm reactive band width are held physically fixed between domain/spacing studies. Both require calibration. Grain-ID colors are visualization colors, not crystallographic inverse-pole-figure colors.

These choices make the implementation reproducible. They do not resolve identifiability between nucleation, mobility, initial microstructure and omitted mechanisms. Validate stress curves, DRX area fraction and consistently defined grain-size distributions against independent measurements.

## Sources

- Supplied nested Java source and `files654/PROJECT REPORT (FINAL).pdf`, particularly printed pages 13–14, 20–21 and 24–27.
- [Special Metals: INCONEL alloy 617 technical bulletin](https://www.specialmetals.com/documents/technical-bulletins/inconel/inconel-alloy-617.pdf), composition, melting range and modulus data.
- [Babu et al. (2016), Characterization of hot deformation behavior of alloy 617](https://doi.org/10.1016/j.msea.2016.04.004).
- [Du et al. (2026), Hot deformation behavior and microstructure evolution of Inconel 617](https://doi.org/10.1557/s43578-026-01878-7), evidence that recovery/continuous DRX and twinning are relevant beyond this fixed-grid DDRX engine.
