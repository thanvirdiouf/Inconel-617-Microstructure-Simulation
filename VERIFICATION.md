# Verification record — 4 October 2026

The corrected sources compile with Java 17 compatibility (`javac --release 17 -Xlint:all`) using the available Java 21 compiler. No third-party runtime dependencies are required.

`./test.sh` passes 16 regression tests covering input validation, unit conversion, material coefficients, initial state, grain conservation, boundary edges/clearing, physical nucleation area, seeded reproducibility, output strain/time, defensive state copies, migration limits, nucleus density, and selected integration/spatial refinements. New checks independently anchor FINAL_CA coefficients at 1050°C/0.001 s⁻¹ and 1200°C/0.1 s⁻¹, guard unchanged MAIN9 coefficients, verify temperature-only FINAL_CA nucleation rate scaling, and check the final-report preset and order-independent explicit overrides.

In the single-grain density-integration refinement check, coarse/fine/reference stresses were 109.963566, 110.147820, and 110.186725 MPa. The corresponding errors against the reference were 0.2025% and 0.0353%. This is a solver check, not validation of a material fit. The physical-domain refinement check holds a 24 × 24 μm single-grain domain fixed while changing cell spacing from 1 to 0.5 μm. A separate interface check verifies 48 μm² reactive area for a 24 μm axis-aligned boundary and a 2 μm physical nucleation band at both resolutions.

Twelve short endpoint scenarios completed: all three equation sets (`cac2`, `main9`, `variant`), both 900°C and 1200°C, and both 0.001 and 0.1 s⁻¹. Each used a 24 × 24 grid, 1 μm cells, 8 μm initial grain diameter, seed 42, and target strain 0.1. Checks required finite output, an exact final strain/time, DRX fractions in [0, 1], and an incoming migration hazard no greater than 0.25.

Two complete default-size examples are saved under `examples/`, at 1100°C, seed 42, target strain 1.38, 100 × 100 cells, 1 μm cell size, 13 μm initial grain diameter, 1 μm nucleus diameter, 2 μm nucleation band and model `cac2`:

| Rate (s⁻¹) | Final time (s) | Integration steps | CSV rows | PNG snapshots |
| --- | --- | --- | --- | --- |
| 0.001 | 1380 | 13662 | 15 | 15 |
| 0.1 | 13.8 | 1196 | 15 | 15 |

CLI checks also confirmed consistent CSV endpoints, image dimensions, metadata, unchanged dynamics when image saving is disabled, rejection of invalid options, and refusal to overwrite a nonempty output directory.

## Final-report comparison matrix

All 18 full-strain condition comparisons completed under the same corrected engine: CAC2, MAIN9 and FINAL_CA at 1050°C/0.001 s⁻¹, 1050°C/0.1 s⁻¹, 1200°C/0.001 s⁻¹, 1200°C/0.1 s⁻¹, 1100°C/0.1 s⁻¹ and 950°C/0.001 s⁻¹. Each used a 400 × 400 μm physical domain, 1 μm cells, 58 μm nominal initial grain diameter, 1 μm nucleus diameter, 2 μm reactive band, seed 42, target strain 1.38, strain-step cap 0.00138, incoming-hazard cap 0.25 and output spacing 0.05 plus the exact endpoint.

The frozen matrix sources have SHA-256 `219dc596d88136b03574f0dcd49976812deb0c82019c03a57c099302bf240217`; their executable classes have SHA-256 `b8ab94c497bd8dcadb1f66af85f2e4d33511350a984b443c6778ac46f45514f0`. This batch was compiled and run using the available Java 21 installation. The separate normal build and regression tests compile with Java 17 compatibility. Run manifests retain compiler/runtime, resolved configuration, input identities and hashes of saved results. Aggregation verifies source/class integrity, actual runtime fingerprint, successful completion, exact sample coordinates/endpoints, time = strain/rate, summary/CSV agreement, finite nonnegative values, fraction bounds and the migration-hazard bound.

At 1200°C/0.001 s⁻¹, every compared model ends with one grain occupying the entire 400 μm square. Its 451.352 μm area-equivalent diameter follows from the domain area and is not a resolved material grain-size prediction. Similar stress predictions can also conceal different DRX fractions and grain sizes; none of these comparisons selects an experimentally validated parameter set. See [analysis/summary.md](analysis/summary.md) for measured comparisons and sensitivity results, and [analysis/PLAN.md](analysis/PLAN.md) for reproduction instructions.

Six additional full-strain FINAL_CA runs at 1100°C/0.001 s⁻¹ cover the 400 μm baseline, seeds 43 and 44, domains of 200 and 800 μm, and a halved strain-step cap. This brings the completed production comparison total to 24. Across the three baseline-size seeds, final stress ranges from 56.6033 to 56.7104 MPa while mean diameter ranges from 128.743 to 163.282 μm. Halving the strain-step cap changes final mean diameter by −21.94% and stress by +0.0792%; altered timesteps also alter the stochastic draw sequence, so this single comparison does not isolate deterministic integration error. Domain changes likewise alter the initial grain realization. The optional 0.5 μm grid run was stopped to deliver the project promptly; its partial outputs are retained and excluded from completed results. Full spatial and stochastic convergence remains unestablished.

The stopped fine-grid trajectory also supports an explicitly intermediate comparison at the common saved strain 0.5. Changing spacing from 1 to 0.5 μm while holding the 400 μm domain, 58 μm nominal grains, 1 μm nucleus and 2 μm band fixed changes stress from 56.438888 to 56.387695 MPa (−0.0907%), number-mean grain diameter from 126.591145 to 138.892939 μm (+9.7177%), and cumulative nuclei per μm² from 0.0063125 to 0.0101125 (+60.1980%); DRX area fraction is 1 in both. The same seed changes physical Voronoi coordinates and subsequent draws at the refined spacing. This is a partial-run sensitivity observation, not a completed endpoint or proof of convergence. A fresh full-strain retry reached saved strain 0.85 and 96,511 steps before its 720-second timeout during the requested 15-minute follow-up. Its partial outputs are preserved and excluded from completed endpoint results.

These checks establish executable behavior and selected numerical properties. They do not establish full stochastic spatial/time convergence or agreement with experimental Inconel 617 results. Nucleus size, nucleation-band width, empirical parameters and omitted continuous-DRX/twinning mechanisms remain calibration/model-scope assumptions described in `README.md`.

## Independent result consistency and trend checks

[analysis/validate_results.py](analysis/validate_results.py) reverified all 24 production runs and 696 stored samples, including source/executable identities, resolved configurations, saved hashes, endpoints, and time/rate consistency. Additional strict checks passed for integer DRX cell areas, active population counts, zero/full DRX populations, single-cell lower diameter bounds, number-mean area/count upper bounds, exact one-grain diameters and sampled peaks. The records are [analysis/validation.md](analysis/validation.md) and [analysis/validation.json](analysis/validation.json).

All six available matched rate pairs increase endpoint and sampled-peak stress at higher rate; all twelve adjacent matched temperature pairs decrease those stresses at higher temperature. These are qualitative diagnostics, not empirical agreement or mathematical invariants. No monotonic DRX criterion is imposed. The three domain-filling single-grain endpoints are within 0.068% of an independently evaluated equilibrium of the complete fixed-diameter KM equation; their finite-strain comparison is an asymptotic check rather than a controlled convergence test.
