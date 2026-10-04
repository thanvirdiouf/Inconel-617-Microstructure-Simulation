# Corrected Inconel 617 numerical comparisons

24 verified completed runs. Condition matrix: complete (18 of 18).

All matrix runs use a 400 × 400 µm domain, 1 µm spacing, nominal initial grain diameter 58 µm, nucleus diameter 1 µm, reactive band 2 µm, seed 42, strain 1.38, strain cap 0.00138 and hazard cap 0.25. Saved sample spacing is 0.05 plus the exact endpoint.

The material parameter sets retain different constitutive laws. These are numerical comparisons without experimental validation.

## Matrix results

| Model | T (°C) | Rate (s⁻¹) | Final stress (MPa) | Sampled peak (MPa) | Final DRX fraction | Final mean diameter (µm) | Nuclei | Steps |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| CAC2 | 950 | 0.001 | 153.891 | 189.604 | 1.000000 | 3.563 | 17950 | 1021 |
| FINAL_CA | 950 | 0.001 | 208.904 | 209.229 | 0.134169 | 7.858 | 1199 | 1021 |
| MAIN9 | 950 | 0.001 | 208.517 | 209.058 | 0.149319 | 6.808 | 1551 | 1021 |
| CAC2 | 1050 | 0.001 | 76.216 | 79.905 | 1.000000 | 33.557 | 8850 | 3778 |
| FINAL_CA | 1050 | 0.001 | 85.885 | 89.253 | 1.000000 | 83.006 | 665 | 6828 |
| MAIN9 | 1050 | 0.001 | 85.868 | 88.955 | 1.000000 | 73.209 | 979 | 7601 |
| CAC2 | 1050 | 0.1 | 187.128 | 222.682 | 0.999975 | 3.636 | 15011 | 1021 |
| FINAL_CA | 1050 | 0.1 | 227.951 | 237.429 | 1.000000 | 10.985 | 2139 | 1457 |
| MAIN9 | 1050 | 0.1 | 200.489 | 232.560 | 1.000000 | 10.107 | 2762 | 1657 |
| CAC2 | 1100 | 0.1 | 116.280 | 163.088 | 1.000000 | 17.156 | 2107 | 1502 |
| FINAL_CA | 1100 | 0.1 | 160.842 | 173.664 | 1.000000 | 27.755 | 719 | 3567 |
| MAIN9 | 1100 | 0.1 | 162.759 | 168.233 | 1.000000 | 32.004 | 991 | 3900 |
| CAC2 | 1200 | 0.001 | 25.714 | 25.714 | 1.000000 | 451.352 | 490 | 74916 |
| FINAL_CA | 1200 | 0.001 | 26.041 | 28.570 | 1.000000 | 451.352 | 239 | 103613 |
| MAIN9 | 1200 | 0.001 | 26.041 | 26.041 | 1.000000 | 451.352 | 507 | 94491 |
| CAC2 | 1200 | 0.1 | 77.853 | 82.267 | 1.000000 | 93.522 | 463 | 17898 |
| FINAL_CA | 1200 | 0.1 | 86.485 | 89.737 | 1.000000 | 114.444 | 221 | 14531 |
| MAIN9 | 1200 | 0.1 | 86.161 | 86.344 | 1.000000 | 247.295 | 340 | 19963 |

Peak stresses are sampled maxima. A stress peak between samples may be higher. DRX thresholds record first observed saved crossings; earlier crossings may occur between samples if current DRX ownership later decreases.

## Sensitivity coverage

- selected baseline: 1 complete run(s).
- seed sensitivity: 2 complete run(s).
- domain sensitivity: 2 complete run(s).
- timestep sensitivity: 1 complete run(s).
- grid sensitivity: 0 complete run(s).

Seeds [42, 43, 44] provide a small stochastic sensitivity sample.
- final flow stress MPa: 56.6033–56.7104 MPa.
- final mean grain diameter um: 128.743–163.282 µm.
- final drx fraction: 1–1 .

| Change | Seed | Domain (µm) | Cell (µm) | Strain cap | Final stress (MPa) | Final mean diameter (µm) | DRX fraction | Nuclei/µm² |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Baseline | 42 | 400 | 1 | 0.00138 | 56.6033 | 163.2823 | 1.000000 | 0.00631250 |
| Seed | 43 | 400 | 1 | 0.00138 | 56.7104 | 129.7103 | 1.000000 | 0.00525625 |
| Seed | 44 | 400 | 1 | 0.00138 | 56.7055 | 128.7432 | 1.000000 | 0.00854375 |
| Domain | 42 | 200 | 1 | 0.00138 | 56.7444 | 126.4282 | 1.000000 | 0.00735000 |
| Domain | 42 | 800 | 1 | 0.00138 | 56.5880 | 163.9347 | 1.000000 | 0.00385000 |
| Timestep | 42 | 400 | 1 | 0.00069 | 56.6481 | 127.4661 | 1.000000 | 0.00523750 |

Domain changes keep physical cell, grain, nucleus and band sizes fixed, but change grain number and seeded geometry. The optional timestep/grid comparisons are individual diagnostics, not a full convergence study.

Grid refinement changes integer seed-placement bounds. The same random seed therefore does not preserve physical Voronoi seed coordinates, and minimum-spacing rejection may change later random draws. This grid diagnostic includes a changed initial realization; a paired refinement study would require shared physical seed coordinates.

Mean diameters are number means of live area-equivalent grain diameters. A zero DRX mean means no active DRX grain. Current DRX area fraction can decline when nuclei disappear; cumulative nuclei count can continue increasing. Cumulative nuclei is an extensive count, so domain sensitivity comparisons use cumulative nuclei per physical area (µm⁻²).

## Verification and provenance

Every aggregated run passed matching configuration metadata, successful completion status, saved-file hashes, exact target strain, complete sample coordinates, finite nonnegative values, time = strain/rate, monotone strain/time, summary/CSV reconciliation, DRX fraction bounds and observed hazard ≤ 0.25.

Source SHA-256: `219dc596d88136b03574f0dcd49976812deb0c82019c03a57c099302bf240217`.

The saved comparison batch used the available Java 21 compiler without a --release target and ran on Java 21. The frozen class files have major version 65 and require Java 21 or newer. The separate regular test script targets Java 17; its bytecode is not the saved comparison build.

Raw results, metadata, console logs and controller manifests are retained under `runs/`. Archived files654 tables have unknown run conditions and serve only as an audit reference.

## Interpretation limits

At 1200 °C / 0.001 s⁻¹, all three corrected models end with one live grain filling the 400 × 400 µm domain. The reported 451.352 µm diameter is the equivalent circular diameter of that square area; it demonstrates domain-limited coarsening in this simulation and should not be treated as a material-scale prediction.

## Figures

- [comparison_flow_stress.png](comparison_flow_stress.png)
- [comparison_drx_fraction.png](comparison_drx_fraction.png)
- [comparison_grain_diameter.png](comparison_grain_diameter.png)
- [comparison_drx_grain_diameter.png](comparison_drx_grain_diameter.png)
- [seed_sensitivity.png](seed_sensitivity.png)
- [domain_sensitivity.png](domain_sensitivity.png)
- [timestep_sensitivity.png](timestep_sensitivity.png)
- [final_ca_process_curves.png](final_ca_process_curves.png)

Incomplete runs were preserved and excluded:
- `/home/spectre/Projects/Inconel/corrected/analysis/runs/final_ca_1100C_rate0p001_seed42_800x800_cell0p5um_step0p00138_strain1p38`

The optional 0.5 µm grid run was stopped at the user’s request to finish promptly. Its partial CSV and metadata are preserved, and it is not treated as a completed refinement or included in comparison metrics.

## Intermediate grid diagnostic

A separately validated saved prefix compares the 1 µm baseline and stopped 0.5 µm grid at exact common strain 0.5. It is not an endpoint comparison at 1.38 or a completed refinement. See [partial_grid_diagnostic.md](partial_grid_diagnostic.md) and its sealed raw evidence under `partial-grid-evidence/`.

The fresh optional grid retry was limited to 720 seconds and timed out with last saved strain 0.85 (96,511 steps). Its partial outputs are preserved. It adds no completed endpoint run; final completed coverage remains 24. See `grid-retry-outcome.json` and `grid-retry-20261004.json`.
