# Numerical result checks

24 verified completed runs; 696 stored samples. Output consistency: **pass**.

This record checks simulator outputs and contains no experimental validation or material fitting.

All runs were reverified against requested/completed identities, configuration, frozen executable integrity, runtime fingerprints, saved-result hashes, samples, endpoints and time/rate reconciliation.

Sample checks reconcile integer DRX cell areas with active DRX/non-DRX counts, zero/full DRX populations, one-cell lower diameter limits, number-mean area/count upper limits, exact one-grain diameters and sampled peaks.

## Rate comparisons

| Model | T (°C) | Rate pair (s⁻¹) | Endpoint Δσ (MPa) | Sampled peak Δσ (MPa) | ΔDRX fraction | Δmean diameter (µm) |
|---|---:|---|---:|---:|---:|---:|
| cac2 | 1050 | 0.001 → 0.1 | +110.912 | +142.777 | -2.5e-05 | -29.9211 |
| cac2 | 1200 | 0.001 → 0.1 | +52.1389 | +56.5525 | +0 | -357.83 |
| final_ca | 1050 | 0.001 → 0.1 | +142.066 | +148.176 | +0 | -72.0218 |
| final_ca | 1200 | 0.001 → 0.1 | +60.4436 | +61.1671 | +0 | -336.907 |
| main9 | 1050 | 0.001 → 0.1 | +114.621 | +143.605 | +0 | -63.1027 |
| main9 | 1200 | 0.001 → 0.1 | +60.1196 | +60.3024 | +0 | -204.056 |

## Temperature comparisons

| Model | Rate (s⁻¹) | Temperature pair (°C) | Endpoint Δσ (MPa) | Sampled peak Δσ (MPa) | ΔDRX fraction | Δmean diameter (µm) |
|---|---:|---|---:|---:|---:|---:|
| cac2 | 0.001 | 950 → 1050 | -77.6747 | -109.699 | +0 | +29.9941 |
| cac2 | 0.001 | 1050 → 1200 | -50.5016 | -54.1906 | +0 | +417.795 |
| cac2 | 0.1 | 1050 → 1100 | -70.848 | -59.5946 | +2.5e-05 | +13.5198 |
| cac2 | 0.1 | 1100 → 1200 | -38.4269 | -80.8209 | +0 | +76.366 |
| final_ca | 0.001 | 950 → 1050 | -123.019 | -119.976 | +0.865831 | +75.1486 |
| final_ca | 0.001 | 1050 → 1200 | -59.8441 | -60.6827 | +0 | +368.345 |
| final_ca | 0.1 | 1050 → 1100 | -67.1093 | -63.765 | +0 | +16.7708 |
| final_ca | 0.1 | 1100 → 1200 | -74.357 | -83.9267 | +0 | +86.689 |
| main9 | 0.001 | 950 → 1050 | -122.65 | -120.103 | +0.850681 | +66.4015 |
| main9 | 0.001 | 1050 → 1200 | -59.8264 | -62.9139 | +0 | +378.142 |
| main9 | 0.1 | 1050 → 1100 | -37.7292 | -64.3268 | +0 | +21.8969 |
| main9 | 0.1 | 1100 → 1200 | -76.5986 | -81.8899 | +0 | +215.292 |

Rate: 6/6 endpoint stress directions and 6/6 sampled-peak directions follow the expected qualitative direction. These are descriptive diagnostics. No DRX monotonicity is required.

Temperature: 12/12 endpoint stress directions and 12/12 sampled-peak directions follow the expected qualitative direction. These are descriptive diagnostics. No DRX monotonicity is required.

## Single-grain equilibrium diagnostics

| Run | Endpoint stress (MPa) | Fixed-diameter KM equilibrium (MPa) | Difference (%) |
|---|---:|---:|---:|
| cac2_1200C_rate0p001_seed42_400x400_cell1um_step0p00138_strain1p38 | 25.714371 | 25.7318266 | -0.0678364 |
| final_ca_1200C_rate0p001_seed42_400x400_cell1um_step0p00138_strain1p38 | 26.041115 | 26.0493669 | -0.0316781 |
| main9_1200C_rate0p001_seed42_400x400_cell1um_step0p00138_strain1p38 | 26.041115 | 26.0493669 | -0.0316781 |

## Interpretation limits

- Strict consistency checks use current cell ownership and area; they do not reconstruct grain shapes or crystallographic segmentation.
- Rate/temperature stress directions are expected qualitative diagnostics, not mathematical invariants or evidence of empirical agreement.
- DRX fraction, mean diameter and nuclei differences are reported without an imposed monotonic trend.
- Stress peaks are maxima of stored samples; earlier DRX threshold crossings may be missed.
- Single-grain equilibrium solves the full KM equation at fixed final domain diameter. Finite-strain endpoints need not equal this asymptote; the comparison is not a controlled solver-convergence test.
- Number-mean grain diameters alone do not recover total area. The reported area/count tests are rigorous bounds, with exact reconciliation only for a one-grain population.

Source SHA-256: `219dc596d88136b03574f0dcd49976812deb0c82019c03a57c099302bf240217`.

Full numeric deltas, nuclei per-area differences and per-run checks are recorded in `validation.json`.
