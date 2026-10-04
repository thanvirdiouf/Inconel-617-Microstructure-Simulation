# Corrected model comparisons

Final coverage: 24 completed production runs (18 condition-matrix runs,
baseline, two extra seeds, two extra domains and timestep halving). The optional
0.5 µm grid run was stopped at the user's request to finish promptly. Its last
saved strain is 0.95 of the requested 1.38; its partial outputs are preserved
and excluded from completed metrics. The optional controller manifest records
`closed_with_optional_run_stopped` rather than successful completion.

The batch uses the corrected common Java engine with the distinct CAC2, MAIN9
and FINAL_CA material parameter sets. Its numerical outputs compare those
implementations and do not validate them against experiments.

## Required condition matrix

For each of CAC2, MAIN9 and FINAL_CA, run these six Celsius / s⁻¹ pairs:
1050 / 0.001, 1050 / 0.1, 1200 / 0.001, 1200 / 0.1, 1100 / 0.1,
and 950 / 0.001. Each run uses a 400 × 400 µm physical domain, 1 µm
cells (400 × 400 cells), nominal initial equivalent diameter 58 µm,
1 µm nucleus diameter, 2 µm reactive nucleation band, seed 42,
strain 1.38, maximum strain step 0.00138, incoming hazard cap 0.25,
and saved strain spacing 0.05 plus the exact endpoint.

The area-derived initial grain count is rounded to an integer, then a seeded
Voronoi initial microstructure is generated. The achieved initial mean diameter
is measured and may differ from its nominal 58 µm setting.

## Selected sensitivity coverage

Use FINAL_CA at 1100 °C / 0.001 s⁻¹ with the same physical assumptions:

- Baseline 400 × 400 µm domain, seed 42.
- Seeds 43 and 44 at the baseline size.
- Domains 200 × 200 µm and 800 × 800 µm, seed 42, keeping cell spacing,
  grain size, nucleus size and nucleation band fixed.
- If runtime permits: maximum strain step 0.00069 at baseline geometry.
- If runtime permits: 0.5 µm cells in the 400 × 400 µm domain (800 × 800
  cells), preserving grain, nucleus and band dimensions.

The three seeds give a small stochastic sensitivity sample. Domain changes
also change initial grain count and geometry, so a shared seed does not mean
the same microstructure. A single grid refinement and timestep halving are
diagnostics, not a complete convergence study.
Grid refinement changes the integer bounds used for Voronoi seed placement,
so a shared random seed does not preserve the same physical seed coordinates.
Minimum-spacing rejection may also change the later random stream. The grid
diagnostic therefore includes a changed initial realization; the current
engine does not provide shared physical seed coordinates for a paired study.

## Reproduction and output protection

One frozen Java 21 reference build is retained with the completed comparison
records so executable integrity can be checked after cloning the repository.
Other generated builds remain ignored. Run `python3
corrected/analysis/validate_results.py` from the repository root to recheck
the saved numeric records; this script prefers local run paths after a move.

Use the bundled Python interpreter at
`/home/spectre/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3`.
From the repository root:

```bash
python3 corrected/analysis/batch_compare.py --scope matrix --manifest corrected/analysis/matrix-recheck.json
python3 corrected/analysis/batch_compare.py --scope sensitivity --manifest corrected/analysis/sensitivity-recheck.json
python3 corrected/analysis/summarize_comparison.py
```

Substitute the bundled interpreter for `python3` if the shell does not resolve
it. The workflow snapshots source bytes and compiles them into a source-hashed
build below `corrected/analysis/builds`. It uses at most two Java processes,
each with a 1 GiB heap limit. Java and controller metadata record each run's
configuration and provenance. Tables are saved without microstructure images.
The saved batch was compiled with the available `javac 21.0.12.1` without a
`--release` target and executed on Java `21.0.12.1`. Its frozen class files
have major version 65 and require Java 21 or newer. This differs from the
regular `corrected/test.sh` compilation, which explicitly targets Java 17.
No completed comparison build was rebuilt to change its target.
The commands above verify and reuse matching completed runs and save new
recheck manifests. Choose another unused `--manifest` filename for each later
invocation. For fresh simulations, choose a new `--runs-dir` below
`corrected/analysis/runs/`, share that directory across the three scopes, and
pass it to `summarize_comparison.py --runs-dir` as well.
The `optional` scope includes the stopped grid job, so replaying that scope in
the original run directory is intentionally refused. To run both optional
checks afresh later, select a new run directory and unused manifest filename;
the stopped simulation is not a restartable checkpoint.

The summary/plot script uses bundled ReportLab scientific `LinePlot` charts
and the bundled Node.js Sharp rasterizer. It saves SVG source figures and
white-background PNGs with units on their axes. It does not require installing
Matplotlib. Its Node executable and Sharp package paths are within the same
bundled dependency runtime; the plotting font is system DejaVu Sans.
Batch invocations share two advisory Java-worker locks, so concurrent scopes
also remain bounded to two Java processes. The selected matrix, sensitivity
and optional scopes were orchestrated within this same limit.

Existing complete runs may be reused only after configuration/source hashes,
frozen class-file integrity, actual runtime executable fingerprint, saved file
hashes, endpoint, samples, time/rate reconciliation, finite outputs and
hazard/fraction bounds pass. Incomplete or mismatching directories are
preserved and refused. A new manifest name must be supplied if the default
already exists. Numerical integration can become more expensive after DRX
starts, so the early benchmark does not guarantee full-run time.

The archived files654 TSVs have unknown run conditions and are retained only
as a reference audit; they are not experimental measurements and are excluded
from condition-matched model plots.

## Deadline-limited validation follow-up

A fresh 0.5 µm grid retry ran in `grid-retry-20261004/` under a strict 720-second limit, then timed out with last saved strain 0.85 (96,511 steps). Its controller result and explicit outcome are saved in `grid-retry-20261004.json` and `grid-retry-outcome.json`. No completed endpoint was added. The separately validated comparison at common strain 0.5 uses the original stopped run and is retained in `partial_grid_diagnostic.json` / `.md`, with sealed raw evidence under `partial-grid-evidence/`. It is an intermediate diagnostic and includes changed initial seed geometry; it does not establish convergence.
