# Partial grid diagnostic at matched strain 0.5

This intermediate diagnostic compares the completed 1 µm baseline with a saved prefix of the stopped 0.5 µm grid at FINAL_CA, 1100 °C and 0.001 s⁻¹. It is not a completed refinement or an endpoint comparison at strain 1.38. Both physical domains are 400 × 400 µm.

| Quantity | Baseline, 1 µm | Partial grid, 0.5 µm | Change (%) |
|---|---:|---:|---:|
| Flow stress (MPa) | 56.438888 | 56.387695 | -0.091 |
| Mean grain diameter (µm) | 126.59115 | 138.89294 | +9.718 |
| DRX area fraction | 1 | 1 | +0.000 |
| Cumulative nuclei/µm² | 0.0063125 | 0.0101125 | +60.198 |

The two prefixes passed configuration/source/executable identity checks, exact common samples, finite nonnegative outputs, monotone strain/time, time = strain/rate, and DRX/hazard bounds. The baseline CSV matches its saved completion hash; both raw CSV and metadata hashes are recorded in the JSON.

The same seed does not preserve physical Voronoi seed positions across cell resolutions. This diagnostic therefore includes a changed initial realization and subsequent stochastic evolution. It does not establish spatial convergence or empirical agreement. Raw results were not altered.

Sealed raw CSVs, run metadata and controller identities are retained under `partial-grid-evidence/`, outside ignored run directories. Its manifest records SHA-256 hashes and original paths. The copied baseline completion and stopped-grid records preserve their distinct statuses.
