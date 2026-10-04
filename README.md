# Inconel 617 DRX simulator

This Java 17 program reconstructs the cellular automaton in the original files with consistent units, reproducible randomness, corrected cell ownership and post-update output. It runs without third-party libraries. The original Java prototypes and supplied reports remain in the parent project directory, outside this repository’s working tree. Source names and hashes below identify those external historical inputs.

From this repository’s root (`corrected/` in the original project), run:

```bash
./test.sh
./run.sh --temperature 1100 --strain-rate 0.001 --seed 42
./run.sh --temperature 1100 --strain-rate 0.1 --seed 42
```

For the newer `files654` final-report setup, run:

```bash
./run.sh --preset final-report --temperature 1050 --strain-rate 0.001 --seed 42
./run.sh --preset final-report --temperature 1050 --strain-rate 0.1 --seed 42
```

`--preset final-report` selects the distinct `final_ca` law set, 400 × 400 cells, and a 58 μm nominal initial diameter. At the default 1 μm spacing this is a 400 × 400 μm domain. Explicit options override preset values regardless of their position. The other settings, including the 1 μm nucleus, 2 μm nucleation band and target strain 1.38, remain explicit inherited assumptions. The original defaults below are preserved for existing commands.

Use the same seed, grid and initial grain diameter when comparing rates. The defaults use a 100 × 100 grid, 1 μm cells, a 13 μm initial equivalent grain diameter, a 1 μm DRX nucleus diameter, a 2 μm physical nucleation band width and applied true strain 1.38. These are configurable; the initial diameter controls the area-derived seed count, and the achieved mean diameter is measured from the resulting microstructure. A larger grid reduces finite-domain effects and takes longer to run.

`./run.sh --help` lists every option. Temperature is supplied in Celsius, lengths in micrometres, and strain rate in s⁻¹. The CLI accepts 900–1200 °C and 0.001–0.1 s⁻¹, the intended comparison range, rather than silently extrapolating. The constructor used by tests accepts finite positive temperature/rate values. A signed 64-bit seed controls all stochastic choices. The rate is used exactly as supplied.

For a short run that saves tables only:

```bash
./run.sh --temperature 1100 --strain-rate 0.001 --strain 0.1 \
  --width 60 --height 60 --grain-size 13 --no-images --output /tmp/inconel-short-run
```

Both scripts work from any current directory. Compilation writes only to `build/classes`. Default outputs go into a unique directory under `runs`; an explicit relative output path is relative to the calling directory. An explicitly selected directory must be new or empty, so a previous run is not overwritten.

## Material equations

The `--model` switch keeps the archived material parameter sets separate:

| Model | Source | Constitutive approach |
| --- | --- | --- |
| `cac2` (default) | `CAC_final (2).java` | Legacy Zener–Hollomon based parameter set. |
| `main9` | `Main (9).java` | Later Zener–Hollomon based critical/steady-state and mobility/nucleation laws. |
| `final_ca` | `files654/final-CA-15-4/untitled/src/Main.java` | Active final-report source laws: critical factor 0.95 and temperature-only nucleation exponent. |
| `variant` | `Main_variant.java` | Early temperature-linear recovery/peak-stress relations with fixed nucleation coefficient. |

These empirical equations retain their source provenance. Correcting their implementation does not validate the material calibration. Compare predicted flow stress, DRX fraction and grain diameter against experimental Inconel 617 data before treating numerical values as predictions. A parameter set's intended domain and original calibration should be checked independently.

`final_ca` differs from `main9` in its critical-density threshold and nucleation exponent. It retains the active Java stress exponent 3.86; the PDF's fitted value 3.91342 needs reconciliation with the stress-law intercept before refitting. The source's hidden doubling of strain rate and random grain-density modifiers are excluded. The legacy modulus constant 1453°C and the effective diffusion-prefactor units are recorded as unresolved material assumptions. The supplied saved `Main.class` uses a different nucleation law and is not used. See [MODEL_NOTES.md](MODEL_NOTES.md) for equations, units and source identities.

## Numerical and state corrections

- The engine stores all lengths in metres and temperature in kelvin. Initial and DRX grain diameters use the same units, and curvature uses the actual cell spacing.
- Initial grains use bounded placement of separated random seeds followed by a fully filled Voronoi lattice. This replaces the prototypes' recursive stochastic filling; the same seed fixes the starting structure for comparable runs.
- Cell ownership, live grain cell counts and boundary membership are rebuilt consistently. Extinct grains do not contribute to active counts or nucleation.
- Migration applies simultaneous capture proposals from the previous state. There are no shared displacement-history arrays. The sum of incoming capture hazards at a cell is limited by `--max-cell-travel`; it is a nondimensional timestep control, rather than a measurement of a continuously tracked boundary.
- Integration chooses the step before mutation, bounded by the requested strain increment and total incoming migration hazard. The final step lands on the requested sample strain rather than overshooting it. Density uses second-order split integration with exact recovery and Heun hardening, constrained to finite nonnegative values.
- Initial densities retain the source range 10⁶–10⁷ m⁻². Newly nucleated grains start at 1 m⁻² after the density update and begin hardening on the next step. Artificial yield-density floors are removed rather than overriding these low-density states.
- Nucleation evaluates a per-cell probability `1 - exp(-nDot * reactiveBoundaryArea * dt * excessDensityWeight)`, where `reactiveBoundaryArea = 0.5 * bandWidth * cellSize * unlikeNeighborFaces`. This assigns a fixed physical reactive width to the interface rather than letting it shrink with the grid spacing. Bounded eligible-cell selection cannot repeatedly replace a newborn grain or loop forever after exhausting candidates. Each nucleus is a circular patch of the configured physical diameter, restricted to cells of its parent grain; simultaneous patches cannot overlap.
- Boundary energy uses a scalar orientation angle modulo π in two dimensions; this does not represent full crystallographic orientations. Physical kink curvature uses the configured cell spacing and clamped domain-edge neighbors.
- The seeded random generator produces reproducible microstructures and stochastic evolution for the same configuration.
- Flow stress, grain diameter and DRX fraction are calculated from the state after an update. Tables include the initial state and the exact final strain, and the initial grain diameter is measured rather than written as zero.

The tests check cell conservation, boundary membership, finite nonnegative densities and outputs, monotone strain/time, timestep hazard bounds, seed reproducibility, straight-interface nucleation intensity under spatial refinement, and selected defect regressions. Such tests verify program behavior, not empirical agreement.

See [VERIFICATION.md](VERIFICATION.md) for the completed test scenarios and the two saved example runs under `examples/`.

The default 1 μm nucleus diameter inherits the single-cell scale of the original CAC2 setup and needs calibration. It must be at least the cell size, no larger than the initial grain diameter, and fit inside the domain. Refining the grid keeps this nominal physical diameter fixed rather than shrinking newborn grains with the pixel size. A patch includes cell centers within its radius and is clipped by the parent grain, occupied cells and the domain, so its measured area-equivalent diameter may differ from the requested diameter. Surface-energy pressure may prevent small nuclei from growing even when nucleation occurs; the model does not promise DRX growth at every temperature/rate combination.

The default 2 μm nucleation band width corresponds to the original two-sided, one-cell-wide band at 1 μm spacing. This grid-derived physical assumption also needs calibration; change it with `--nucleation-band`. The width must fit inside the domain and remains fixed when the grid is refined. For an axis-aligned straight interface, summed reactive area equals interface length times this width at every resolution, so the integrated nucleation intensity no longer vanishes as cells become smaller. For other shapes, length is measured along square-lattice faces; diagonal staircases retain orientation bias and require a spatial-convergence study.

## Saved results

`results.csv` includes strain, time (s), flow stress (MPa), mean grain diameter (μm), mean DRX grain diameter (μm), DRX fraction, active grain counts, cumulative nuclei and step counts, and `max_cell_travel` (the maximum observed nondimensional incoming migration hazard). Sampling defaults to strain intervals of 0.05 and includes the target strain even when it is not an interval multiple. DRX grain diameter averages active recrystallized grains; fraction measures recrystallized cells over the whole grid.

`run.properties` records the resolved configuration, model source, units, seed, Java version, physical assumptions and SHA-256 fingerprint of the actual executable classes. `summary.txt` is written only on successful completion. Optional PNGs show the initial and sampled microstructures. The tables are actual CSV rather than text files disguised with an `.xls` extension.

The reproducible batch workflow and comparison outputs are under [analysis/](analysis/). It freezes a clean compiled build, records source/build identities, and checks completed runs before reusing them. These comparisons assess numerical and parameter sensitivity; they do not supply missing experimental validation data. The revised technical report is under [report/](report/).

Additional [numerical result checks](analysis/validation.md) cover all 696 stored samples from the 24 completed production runs, count/fraction consistency, area-derived diameter bounds, and qualitative temperature/rate stress trends. The [partial grid diagnostic](analysis/partial_grid_diagnostic.md) compares matched strain 0.5 using sealed raw evidence; it is separate from completed endpoint results.

## Scope

This remains a two-dimensional, fixed-grid model of discontinuous dynamic recrystallization driven by stored dislocation energy and grain-boundary migration. It does not represent continuous DRX, twinning, subgrain physics, macroscopic deformation of the specimen, or grain-shape change with imposed strain. Temperature and strain rate remain uniform and constant throughout a run. A seed-controlled run is one stochastic realization; repeat with several seeds for ensemble comparisons.
