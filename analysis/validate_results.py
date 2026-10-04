#!/usr/bin/env python3
"""Reverify saved results and report numerical consistency and trend diagnostics.

These checks concern simulator outputs. They do not compare against experiments,
fit material coefficients, or impose a monotonic DRX response.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path

from batch_compare import HERE, properties, verify_build_integrity, verify_completed


def near(a, b):
    return math.isclose(a, b, rel_tol=2e-10, abs_tol=2e-9)


def inspect_samples(metric, rows, settings):
    cells = settings['width'] * settings['height']
    h = settings['cell_size_um']
    area = cells * h * h
    domain_diameter = math.sqrt(4 * area / math.pi)
    failures = []
    largest_ratio = 0.0
    largest_cell_rounding_error = 0.0
    for index, row in enumerate(rows):
        prefix = f'row {index + 2}, strain {row["strain"]:g}'
        grains = round(row['grain_count'])
        drx_grains = round(row['drx_grain_count'])
        drx_cells_real = row['drx_fraction'] * cells
        drx_cells = round(drx_cells_real)
        rounding_error = abs(drx_cells_real - drx_cells)
        largest_cell_rounding_error = max(largest_cell_rounding_error, rounding_error)
        # CSV stores fractions to 12 significant figures; retain that precision.
        if rounding_error > max(1e-7, cells * 2e-11):
            failures.append(f'{prefix}: DRX area fraction does not reconcile with integer cell area')
        if row['grain_count'] != grains or row['drx_grain_count'] != drx_grains:
            failures.append(f'{prefix}: noninteger active grain count')
        if not 1 <= grains <= cells or not 0 <= drx_grains <= grains:
            failures.append(f'{prefix}: active grain population outside lattice bounds')
            continue
        # Every active grain owns at least one cell. Apply this to both populations.
        if not drx_grains <= drx_cells <= cells - (grains - drx_grains):
            failures.append(f'{prefix}: DRX/non-DRX counts do not reconcile with available cell areas')
        if (drx_grains == 0) != (drx_cells == 0):
            failures.append(f'{prefix}: zero DRX population and zero DRX area disagree')
        if (drx_grains == grains) != (drx_cells == cells):
            failures.append(f'{prefix}: all-DRX population and full DRX area disagree')
        diameter = row['mean_grain_diameter_um']
        largest_ratio = max(largest_ratio, diameter / domain_diameter)
        # Jensen/Cauchy bound: mean(sqrt(area_i)) <= sqrt(total_area / count).
        upper = domain_diameter / math.sqrt(grains)
        cell_diameter = math.sqrt(4 * h * h / math.pi)
        if diameter > upper and not near(diameter, upper):
            failures.append(f'{prefix}: number-mean diameter exceeds area/count bound')
        if diameter < cell_diameter and not near(diameter, cell_diameter):
            failures.append(f'{prefix}: active mean diameter is smaller than one cell')
        drx_diameter = row['mean_drx_grain_diameter_um']
        if drx_grains == 0:
            if drx_diameter != 0:
                failures.append(f'{prefix}: no DRX grains but nonzero DRX mean diameter')
        else:
            drx_upper = math.sqrt(4 * drx_cells * h * h / (math.pi * drx_grains))
            if drx_diameter > drx_upper and not near(drx_diameter, drx_upper):
                failures.append(f'{prefix}: DRX number-mean diameter exceeds DRX area/count bound')
            if drx_diameter < cell_diameter and not near(drx_diameter, cell_diameter):
                failures.append(f'{prefix}: active DRX mean diameter is smaller than one cell')
        if grains == 1 and not near(diameter, domain_diameter):
            failures.append(f'{prefix}: single-grain diameter does not equal domain-equivalent diameter')
        if drx_grains == 1:
            exact_drx = math.sqrt(4 * drx_cells * h * h / math.pi)
            if not near(drx_diameter, exact_drx):
                failures.append(f'{prefix}: single-DRX-grain diameter does not match DRX area')
    observed_peak = max(row['flow_stress_MPa'] for row in rows)
    if not near(metric['sampled_peak_stress_MPa'], observed_peak):
        failures.append('Summary sampled peak differs from maximum stored stress')
    if metric['sampled_peak_stress_MPa'] < rows[-1]['flow_stress_MPa']:
        failures.append('Summary sampled peak is below endpoint stress')
    return {'run': metric['run'], 'samples_checked': len(rows),
            'status': 'pass' if not failures else 'fail', 'failures': failures,
            'domain_area_um2': area, 'domain_equivalent_diameter_um': domain_diameter,
            'maximum_mean_diameter_to_domain_ratio': largest_ratio,
            'maximum_Drx_cell_rounding_error': largest_cell_rounding_error,
            'observed_sampled_peak_MPa': observed_peak,
            'endpoint_stress_MPa': rows[-1]['flow_stress_MPa']}


def comparisons(matrix, variable):
    groups = {}
    for record in matrix:
        fixed = record['temperature_C'] if variable == 'rate' else record['strain_rate_s^-1']
        groups.setdefault((record['model'], fixed), []).append(record)
    result = []
    varied = 'strain_rate_s^-1' if variable == 'rate' else 'temperature_C'
    for (model, fixed), records in sorted(groups.items()):
        ordered = sorted(records, key=lambda record: record[varied])
        for lower, higher in zip(ordered, ordered[1:]):
            # Compare only the matrix's matched geometry, seed and solver controls.
            matching = ['seed', 'domain_width_um', 'domain_height_um', 'cell_size_um',
                        'nominal_initial_grain_diameter_um', 'nucleus_diameter_um',
                        'nucleation_band_width_um', 'max_strain_step', 'max_cell_travel_cap',
                        'sample_spacing_strain', 'final_strain',
                        'actual_initial_mean_diameter_um', 'initial_grain_count']
            if any(lower[key] != higher[key] for key in matching):
                raise RuntimeError(f'Unmatched {variable} comparison: {lower["run"]}, {higher["run"]}')
            stress_delta = higher['final_flow_stress_MPa'] - lower['final_flow_stress_MPa']
            peak_delta = higher['sampled_peak_stress_MPa'] - lower['sampled_peak_stress_MPa']
            result.append({'model': model, 'fixed_temperature_C' if variable == 'rate'
                           else 'fixed_strain_rate_s^-1': fixed,
                           'lower_condition': lower[varied], 'higher_condition': higher[varied],
                           'lower_run': lower['run'], 'higher_run': higher['run'],
                           'endpoint_stress_delta_MPa': stress_delta,
                           'sampled_peak_delta_MPa': peak_delta,
                           'expected_stress_direction': 'increase with rate' if variable == 'rate'
                           else 'decrease with temperature',
                           'endpoint_direction_observed': stress_delta >= 0 if variable == 'rate'
                           else stress_delta <= 0,
                           'sampled_peak_direction_observed': peak_delta >= 0 if variable == 'rate'
                           else peak_delta <= 0,
                           'DRX_fraction_delta': higher['final_drx_fraction'] - lower['final_drx_fraction'],
                           'mean_diameter_delta_um': higher['final_mean_grain_diameter_um']
                           - lower['final_mean_grain_diameter_um'],
                           'nuclei_per_um2_delta': higher['cumulative_nuclei_per_um2']
                           - lower['cumulative_nuclei_per_um2']})
    return result


def equilibrium(metric, metadata):
    if metric['final_grain_count'] != 1:
        return None
    domain_area_m2 = metric['domain_width_um'] * metric['domain_height_um'] * 1e-12
    diameter_m = math.sqrt(4 * domain_area_m2 / math.pi)
    k1, k2 = float(metadata['k1']), float(metadata['k2'])
    if metric['final_drx_grain_count'] == 1:
        k1 *= min(1.0, float(metadata['steady_factor']))
    b = float(metadata['burgers_vector_m'])
    source = 1 / (b * diameter_m)
    # Solve k2*x^2 - k1*x - 1/(b*d) = 0 for x=sqrt(rho).
    density_root = (k1 + math.sqrt(k1 * k1 + 4 * k2 * source)) / (2 * k2)
    expected = float(metadata['alpha']) * float(metadata['shear_modulus_Pa']) * b * density_root / 1e6
    actual = metric['final_flow_stress_MPa']
    return {'run': metric['run'], 'fixed_domain_diameter_um': diameter_m * 1e6,
            'constant_diameter_KM_equilibrium_stress_MPa': expected,
            'endpoint_stress_MPa': actual, 'difference_MPa': actual - expected,
            'relative_difference_percent': (actual / expected - 1) * 100}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--summary', type=Path, default=HERE / 'summary.json')
    parser.add_argument('--output-dir', type=Path, default=HERE)
    args = parser.parse_args()
    summary_bytes = args.summary.read_bytes()
    summary = json.loads(summary_bytes)
    metrics = summary['metrics']
    if not metrics:
        raise RuntimeError('Summary contains no completed results.')
    samples, equilibria = [], []
    for metric in metrics:
        local_output = HERE / 'runs' / metric['run'] / 'output'
        output = local_output if local_output.exists() else Path(metric['output_directory'])
        job = output.parent
        completed = json.loads((job / 'completed.json').read_text())
        requested = json.loads((job / 'requested.json').read_text())
        if completed['identity'] != requested['identity']:
            raise RuntimeError(f'Requested/completed identity mismatch: {job}')
        identity = completed['identity']
        if identity['source_sha256'] != summary['source_sha256']:
            raise RuntimeError(f'Summary/source identity mismatch: {job}')
        if metric['source_sha256'] != identity['source_sha256'] or metric['group'] != requested['group']:
            raise RuntimeError(f'Summary run/source/group identity mismatch: {job}')
        settings = identity['config']
        checks = {'model': settings['model'], 'temperature_C': settings['temperature_C'],
                  'strain_rate_s^-1': settings['strain_rate_s^-1'], 'seed': settings['seed'],
                  'domain_width_um': settings['width'] * settings['cell_size_um'],
                  'domain_height_um': settings['height'] * settings['cell_size_um'],
                  'cell_size_um': settings['cell_size_um'],
                  'nominal_initial_grain_diameter_um': settings['initial_grain_diameter_um'],
                  'nucleus_diameter_um': settings['nucleus_diameter_um'],
                  'nucleation_band_width_um': settings['nucleation_band_width_um'],
                  'max_strain_step': settings['max_strain_step'],
                  'max_cell_travel_cap': settings['max_cell_travel'],
                  'sample_spacing_strain': settings['output_strain_step'],
                  'final_strain': settings['target_strain']}
        for key, expected in checks.items():
            actual = metric[key]
            if (actual != expected if isinstance(expected, str) else not near(actual, expected)):
                raise RuntimeError(f'Summary configuration differs at {key}: {job}')
        build = HERE / 'builds' / identity['source_sha256']
        source_files = sorted((build / 'src' / 'inconel617').glob('*.java'))
        source_digest = hashlib.sha256(b''.join(path.name.encode() + b'\0' + path.read_bytes()
                                               for path in source_files)).hexdigest()
        if source_digest != identity['source_sha256']:
            raise RuntimeError(f'Frozen source bytes differ from saved identity: {build}')
        if not (build / 'class-integrity.json').exists():
            raise RuntimeError(f'No saved frozen-class integrity record: {build}')
        engine = verify_build_integrity(build)
        verified = verify_completed(output, identity['config'], engine)
        if verified != completed['verification']:
            raise RuntimeError(f'Saved output verification changed: {job}')
        if metric['engine_bytecode_sha256'] != engine:
            raise RuntimeError(f'Summary/runtime executable identity mismatch: {job}')
        for key, field in [('final_flow_stress_MPa', 'flow_stress_MPa'),
                           ('final_drx_fraction', 'drx_fraction'),
                           ('final_mean_grain_diameter_um', 'mean_grain_diameter_um'),
                           ('final_mean_drx_grain_diameter_um', 'mean_drx_grain_diameter_um'),
                           ('final_grain_count', 'grain_count'),
                           ('final_drx_grain_count', 'drx_grain_count'),
                           ('cumulative_nuclei', 'nuclei_count')]:
            if not near(metric[key], verified['final'][field]):
                raise RuntimeError(f'Summary endpoint metric differs at {key}: {job}')
        with (output / 'results.csv').open() as stream:
            rows = [{key: float(value) for key, value in row.items()} for row in csv.DictReader(stream)]
        samples.append(inspect_samples(metric, rows, identity['config']))
        diagnostic = equilibrium(metric, properties(output / 'run.properties'))
        if diagnostic:
            equilibria.append(diagnostic)
    matrix = [metric for metric in metrics if metric['group'] == 'condition_matrix']
    rates, temperatures = comparisons(matrix, 'rate'), comparisons(matrix, 'temperature')
    passed = all(record['status'] == 'pass' for record in samples)
    record = {'created_utc': datetime.now(timezone.utc).isoformat(),
              'scope': 'numerical output consistency and descriptive trend diagnostics; no experimental validation',
              'source_sha256': summary['source_sha256'],
              'engine_bytecode_sha256': sorted(set(metric['engine_bytecode_sha256'] for metric in metrics)),
              'summary_used': str(args.summary.resolve()), 'completed_runs_checked': len(metrics),
              'summary_sha256': hashlib.sha256(summary_bytes).hexdigest(),
              'samples_checked': sum(sample['samples_checked'] for sample in samples),
              'consistency_status': 'pass' if passed else 'fail', 'sample_checks': samples,
              'rate_comparisons': rates, 'temperature_comparisons': temperatures,
              'single_grain_equilibrium_diagnostics': equilibria,
              'interpretation': [
                  'Strict consistency checks use current cell ownership and area; they do not reconstruct grain shapes or crystallographic segmentation.',
                  'Rate/temperature stress directions are expected qualitative diagnostics, not mathematical invariants or evidence of empirical agreement.',
                  'DRX fraction, mean diameter and nuclei differences are reported without an imposed monotonic trend.',
                  'Stress peaks are maxima of stored samples; earlier DRX threshold crossings may be missed.',
                  'Single-grain equilibrium solves the full KM equation at fixed final domain diameter. Finite-strain endpoints need not equal this asymptote; the comparison is not a controlled solver-convergence test.',
                  'Number-mean grain diameters alone do not recover total area. The reported area/count tests are rigorous bounds, with exact reconciliation only for a one-grain population.'
              ]}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / 'validation.json').write_text(json.dumps(record, indent=2, allow_nan=False) + '\n')
    lines = ['# Numerical result checks', '',
             f'{len(metrics)} verified completed runs; {record["samples_checked"]} stored samples. '
             f'Output consistency: **{record["consistency_status"]}**.', '',
             'This record checks simulator outputs and contains no experimental validation or material fitting.', '',
             'All runs were reverified against requested/completed identities, configuration, frozen executable integrity, '
             'runtime fingerprints, saved-result hashes, samples, endpoints and time/rate reconciliation.', '',
             'Sample checks reconcile integer DRX cell areas with active DRX/non-DRX counts, zero/full DRX populations, '
             'one-cell lower diameter limits, number-mean area/count upper limits, exact one-grain diameters and sampled peaks.', '',
             '## Rate comparisons', '',
             '| Model | T (°C) | Rate pair (s⁻¹) | Endpoint Δσ (MPa) | Sampled peak Δσ (MPa) | ΔDRX fraction | Δmean diameter (µm) |',
             '|---|---:|---|---:|---:|---:|---:|']
    for item in rates:
        lines.append(f'| {item["model"]} | {item["fixed_temperature_C"]} | '
                     f'{item["lower_condition"]:g} → {item["higher_condition"]:g} | '
                     f'{item["endpoint_stress_delta_MPa"]:+.6g} | {item["sampled_peak_delta_MPa"]:+.6g} | '
                     f'{item["DRX_fraction_delta"]:+.6g} | {item["mean_diameter_delta_um"]:+.6g} |')
    lines += ['', '## Temperature comparisons', '',
              '| Model | Rate (s⁻¹) | Temperature pair (°C) | Endpoint Δσ (MPa) | Sampled peak Δσ (MPa) | ΔDRX fraction | Δmean diameter (µm) |',
              '|---|---:|---|---:|---:|---:|---:|']
    for item in temperatures:
        lines.append(f'| {item["model"]} | {item["fixed_strain_rate_s^-1"]:g} | '
                     f'{item["lower_condition"]:g} → {item["higher_condition"]:g} | '
                     f'{item["endpoint_stress_delta_MPa"]:+.6g} | {item["sampled_peak_delta_MPa"]:+.6g} | '
                     f'{item["DRX_fraction_delta"]:+.6g} | {item["mean_diameter_delta_um"]:+.6g} |')
    for label, items in [('Rate', rates), ('Temperature', temperatures)]:
        matched = sum(item['endpoint_direction_observed'] for item in items)
        peak_matched = sum(item['sampled_peak_direction_observed'] for item in items)
        lines += ['', f'{label}: {matched}/{len(items)} endpoint stress directions and '
                  f'{peak_matched}/{len(items)} sampled-peak directions follow the expected qualitative direction. '
                  'These are descriptive diagnostics. No DRX monotonicity is required.']
    if equilibria:
        lines += ['', '## Single-grain equilibrium diagnostics', '',
                  '| Run | Endpoint stress (MPa) | Fixed-diameter KM equilibrium (MPa) | Difference (%) |',
                  '|---|---:|---:|---:|']
        for item in equilibria:
            lines.append(f'| {item["run"]} | {item["endpoint_stress_MPa"]:.9g} | '
                         f'{item["constant_diameter_KM_equilibrium_stress_MPa"]:.9g} | '
                         f'{item["relative_difference_percent"]:+.6g} |')
    lines += ['', '## Interpretation limits', ''] + ['- ' + note for note in record['interpretation']]
    if not passed:
        lines += ['', '## Consistency failures', '']
        for sample in samples:
            lines += [f'- {sample["run"]}: {failure}' for failure in sample['failures']]
    lines += ['', f'Source SHA-256: `{record["source_sha256"]}`.', '',
              'Full numeric deltas, nuclei per-area differences and per-run checks are recorded in `validation.json`.']
    (args.output_dir / 'validation.md').write_text('\n'.join(lines) + '\n')
    print(json.dumps({'consistency_status': record['consistency_status'],
                      'runs': len(metrics), 'samples': record['samples_checked'],
                      'rate_pairs': len(rates), 'temperature_pairs': len(temperatures),
                      'single_grain_diagnostics': len(equilibria)}, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
