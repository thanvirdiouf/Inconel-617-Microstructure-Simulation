#!/usr/bin/env python3
"""Freeze Java sources and run explicit, resumable comparison configurations.

Run with the bundled Python interpreter. No existing output is overwritten.
Resume requires a successful summary, matching configuration/source hash, and
unchanged results. At most two Java processes run concurrently.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor, as_completed
import csv
from datetime import datetime, timezone
import hashlib
import fcntl
import json
import math
from pathlib import Path
import subprocess
import time

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / 'src' / 'inconel617'
CONDITIONS = [(1050, .001), (1050, .1), (1200, .001), (1200, .1), (1100, .1), (950, .001)]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def write_new_json(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2, allow_nan=False)
        stream.write('\n')


def class_fingerprint(classes):
    directory = classes / 'inconel617'
    paths = sorted(p for p in directory.rglob('*.class')
                   if not p.name.startswith('SimulationTest'))
    if not paths:
        raise RuntimeError(f'No executable classes in frozen build: {classes}')
    return sha256(b''.join(p.relative_to(directory).as_posix().encode() + b'\0' + p.read_bytes()
                          for p in paths))


def verify_build_integrity(build):
    classes = build / 'classes'
    actual = class_fingerprint(classes)
    integrity = build / 'class-integrity.json'
    if not integrity.exists():
        # This guard is written immediately after fresh compilation, and also
        # binds the already freshly compiled first matrix build from this task.
        write_new_json(integrity, {'engine_bytecode_sha256': actual,
                       'class_files': {p.relative_to(classes).as_posix(): sha256(p.read_bytes())
                                       for p in sorted(classes.rglob('*.class'))}})
    saved = json.loads(integrity.read_text())
    actual_files = {p.relative_to(classes).as_posix(): sha256(p.read_bytes())
                    for p in sorted(classes.rglob('*.class'))}
    if saved['engine_bytecode_sha256'] != actual or saved['class_files'] != actual_files:
        raise RuntimeError(f'Frozen executable classes changed; refusing reuse: {classes}')
    return actual


@contextmanager
def java_slot():
    """Share two process slots across concurrent batch-script invocations."""
    locks = HERE / '.worker-locks'
    locks.mkdir(exist_ok=True)
    acquired = None
    while acquired is None:
        for index in range(2):
            handle = (locks/f'slot-{index}.lock').open('a')
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                acquired = handle
                break
            except BlockingIOError:
                handle.close()
        if acquired is None:
            time.sleep(.2)
    try:
        yield
    finally:
        fcntl.flock(acquired.fileno(), fcntl.LOCK_UN)
        acquired.close()


def freeze_build():
    files = sorted(SOURCE.glob('*.java'))
    contents = {p.name: p.read_bytes() for p in files}
    if contents != {p.name: p.read_bytes() for p in files}:
        raise RuntimeError('Model sources changed during snapshot; retry after source edits finish.')
    digest = sha256(b''.join(name.encode() + b'\0' + data for name, data in contents.items()))
    build = HERE / 'builds' / digest
    source_dir, classes = build / 'src' / 'inconel617', build / 'classes'
    if not (build / 'compiled.json').exists():
        if build.exists():
            raise RuntimeError(f'Incomplete build preserved; refusing overwrite: {build}')
        source_dir.mkdir(parents=True)
        classes.mkdir()
        for name, data in contents.items():
            (source_dir / name).write_bytes(data)
        compile_command = ['javac', '-encoding', 'UTF-8', '-d', str(classes),
                           *map(str, sorted(source_dir.glob('*.java')))]
        compile_result = subprocess.run(compile_command,
                                        capture_output=True, text=True)
        (build / 'compile.log').write_text(compile_result.stdout + compile_result.stderr)
        if compile_result.returncode:
            raise RuntimeError(f'Compilation failed; see {build / "compile.log"}')
        write_new_json(build / 'compiled.json', {'source_sha256': digest,
                       'source_files': {name: sha256(data) for name, data in contents.items()},
                       'compile_command': compile_command,
                       'javac_version': subprocess.run(['javac', '--version'], capture_output=True,
                                                       text=True).stdout.strip(),
                       'java_version': subprocess.run(['java', '-version'], capture_output=True,
                                                      text=True).stderr.strip(),
                       'compiled_utc': datetime.now(timezone.utc).isoformat()})
    else:
        for name, data in contents.items():
            if (source_dir / name).read_bytes() != data:
                raise RuntimeError('Saved build source differs from its source hash.')
    verify_build_integrity(build)
    return digest, classes


def config(model='final_ca', temperature=1100, rate=.001, seed=42, domain=400,
           cell=1, max_step=.00138, target=1.38):
    cells = round(domain / cell)
    assert math.isclose(cells * cell, domain)
    return {'model': model, 'temperature_C': temperature, 'strain_rate_s^-1': rate,
            'target_strain': target, 'width': cells, 'height': cells,
            'cell_size_um': cell, 'initial_grain_diameter_um': 58,
            'nucleus_diameter_um': 1, 'nucleation_band_width_um': 2,
            'seed': seed, 'max_strain_step': max_step, 'max_cell_travel': .25,
            'output_strain_step': .05, 'images': False}


def jobs(scope, models):
    if scope == 'benchmark':
        return [('benchmark', config('main9', rate=.1, target=.03))]
    result = []
    if scope in ('matrix', 'all'):
        result += [('condition_matrix', config(model, temperature, rate))
                   for temperature, rate in CONDITIONS for model in models]
    if scope in ('sensitivity', 'all'):
        result += [('selected_baseline', config()),
                   ('seed_sensitivity', config(seed=43)),
                   ('seed_sensitivity', config(seed=44)),
                   ('domain_sensitivity', config(domain=200)),
                   ('domain_sensitivity', config(domain=800))]
    if scope in ('optional', 'all'):
        result += [('timestep_sensitivity', config(max_step=.00069)),
                   ('grid_sensitivity', config(cell=.5))]
    return result


def job_name(settings):
    def fmt(value):
        return f'{value:g}'.replace('.', 'p')
    return (f'{settings["model"]}_{fmt(settings["temperature_C"])}C_'
            f'rate{fmt(settings["strain_rate_s^-1"])}_seed{settings["seed"]}_'
            f'{settings["width"]}x{settings["height"]}_cell{fmt(settings["cell_size_um"])}um_'
            f'step{fmt(settings["max_strain_step"])}_strain{fmt(settings["target_strain"])}')


def cli(settings, output):
    names = {'temperature_C': 'temperature', 'strain_rate_s^-1': 'strain-rate',
             'target_strain': 'strain', 'cell_size_um': 'cell-size',
             'initial_grain_diameter_um': 'grain-size', 'nucleus_diameter_um': 'nucleus-size',
             'nucleation_band_width_um': 'nucleation-band', 'max_strain_step': 'max-strain-step',
             'max_cell_travel': 'max-cell-travel', 'output_strain_step': 'output-strain-step'}
    args = []
    for name, value in settings.items():
        if name == 'images':
            if not value:
                args.append('--no-images')
        else:
            args += ['--' + names.get(name, name), str(value)]
    return args + ['--output', str(output)]


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)


def verify_completed(output, settings, engine_sha256=None):
    summary = properties(output / 'summary.txt')
    if summary.get('status') != 'complete':
        raise RuntimeError('Run lacks successful completion status.')
    metadata = properties(output / 'run.properties')
    if engine_sha256 is not None and metadata.get('engine_bytecode_sha256') != engine_sha256:
        raise RuntimeError('Run executable fingerprint differs from the verified frozen build.')
    mapping = {'cell_size_um': 'cell_size_m', 'initial_grain_diameter_um': 'initial_grain_diameter_m',
               'nucleus_diameter_um': 'nucleus_diameter_m',
               'nucleation_band_width_um': 'nucleation_band_width_m'}
    for name, expected in settings.items():
        key = mapping.get(name, name)
        actual = metadata[key]
        if name in ('model', 'images'):
            desired = str(expected).lower()
            if actual != desired:
                raise RuntimeError(f'Metadata differs at {key}: {actual} != {desired}')
        else:
            desired = expected * 1e-6 if name in mapping else expected
            if not math.isclose(float(actual), desired, rel_tol=2e-10, abs_tol=1e-15):
                raise RuntimeError(f'Metadata differs at {key}: {actual} != {desired}')
    with (output / 'results.csv').open() as stream:
        rows = [{k: float(v) for k, v in row.items()} for row in csv.DictReader(stream)]
    if len(rows) < 2 or rows[0]['strain'] != 0:
        raise RuntimeError('Results lack initial/final rows.')
    target = settings['target_strain']
    if not math.isclose(rows[-1]['strain'], target, rel_tol=0, abs_tol=2e-11):
        raise RuntimeError('Results do not reach exact target strain.')
    expected_samples = [0]
    index = 1
    while expected_samples[-1] < target:
        expected_samples.append(min(target, index * settings['output_strain_step']))
        index += 1
    if len(rows) != len(expected_samples) or any(
            not math.isclose(row['strain'], strain, abs_tol=2e-11)
            for row, strain in zip(rows, expected_samples)):
        raise RuntimeError('Sampling coordinates are incomplete or differ.')
    for i, row in enumerate(rows):
        if not all(math.isfinite(v) and v >= 0 for v in row.values()):
            raise RuntimeError(f'Invalid result at row {i+2}.')
        if row['drx_fraction'] > 1 or row['max_cell_travel'] > settings['max_cell_travel']+2e-10:
            raise RuntimeError('Fraction or integration hazard bound failed.')
        if not math.isclose(row['time_s'], row['strain']/settings['strain_rate_s^-1'],
                            rel_tol=2e-10, abs_tol=1e-9):
            raise RuntimeError('Time and applied rate do not reconcile.')
        if i and (row['strain'] <= rows[i-1]['strain'] or row['time_s'] <= rows[i-1]['time_s']):
            raise RuntimeError('Strain/time fail monotonicity.')
    last = rows[-1]
    pairs = {'final_strain': 'strain', 'final_time_s': 'time_s',
             'final_flow_stress_MPa': 'flow_stress_MPa', 'final_drx_fraction': 'drx_fraction',
             'final_mean_grain_diameter_um': 'mean_grain_diameter_um',
             'final_mean_drx_grain_diameter_um': 'mean_drx_grain_diameter_um',
             'steps': 'step_count', 'maximum_observed_cell_travel': 'max_cell_travel'}
    for summary_key, csv_key in pairs.items():
        if not math.isclose(float(summary[summary_key]), last[csv_key], rel_tol=2e-10, abs_tol=1e-10):
            raise RuntimeError(f'Summary/CSV mismatch at {summary_key}.')
    return {'rows': len(rows), 'final': last,
            'sampled_peak_stress_MPa': max(r['flow_stress_MPa'] for r in rows),
            'sampled_peak_strain': max(rows, key=lambda r: r['flow_stress_MPa'])['strain'],
            'results_sha256': sha256((output / 'results.csv').read_bytes()),
            'metadata_sha256': sha256((output / 'run.properties').read_bytes())}


def run_one(group, settings, digest, classes, runs, timeout, dry_run):
    name = job_name(settings)
    job_dir = runs / name
    output = job_dir / 'output'
    identity = {'config': settings, 'source_sha256': digest}
    engine_sha256 = verify_build_integrity(classes.parent)
    if job_dir.exists():
        completed = job_dir / 'completed.json'
        if not completed.exists():
            raise RuntimeError(f'Existing incomplete job preserved; refusing overwrite: {job_dir}')
        saved = json.loads(completed.read_text())
        if saved['identity'] != identity:
            raise RuntimeError(f'Existing job config/source differs; refusing reuse: {job_dir}')
        verified = verify_completed(output, settings, engine_sha256)
        if verified != saved['verification']:
            raise RuntimeError(f'Completed artifacts changed; refusing reuse: {job_dir}')
        print(f'REUSE {name}', flush=True)
        return {'name': name, 'group': group, 'status': 'complete', 'reused': True,
                'config': settings, 'source_sha256': digest, 'runtime_seconds': saved['runtime_seconds'],
                'output_directory': str(output), **verified}
    command = ['java', '-Xmx1024m', '-Djava.awt.headless=true', '-cp', str(classes),
               'inconel617.Main', *cli(settings, output)]
    if dry_run:
        return {'name': name, 'group': group, 'status': 'planned', 'config': settings,
                'source_sha256': digest, 'command': command}
    job_dir.mkdir(parents=True)
    write_new_json(job_dir / 'requested.json', {'identity': identity, 'group': group, 'command': command})
    with java_slot():
        print(f'START {name}', flush=True)
        started = time.monotonic()
        with (job_dir / 'console.log').open('x') as log:
            try:
                result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
                elapsed = time.monotonic() - started
                if result.returncode:
                    raise RuntimeError(f'Java exited {result.returncode}; see {job_dir / "console.log"}')
                verified = verify_completed(output, settings, engine_sha256)
            except Exception as error:
                elapsed = time.monotonic() - started
                write_new_json(job_dir / 'failed.json', {'identity': identity, 'runtime_seconds': elapsed,
                               'error': str(error), 'outputs_preserved': True})
                print(f'FAILED {name}: {error}', flush=True)
                return {'name': name, 'group': group, 'status': 'failed', 'config': settings,
                        'source_sha256': digest, 'runtime_seconds': elapsed, 'error': str(error),
                        'output_directory': str(output)}
    write_new_json(job_dir / 'completed.json', {'identity': identity, 'runtime_seconds': elapsed,
                   'verification': verified})
    print(f'DONE {name}: {elapsed:.1f}s; {verified["final"]["step_count"]:.0f} steps', flush=True)
    return {'name': name, 'group': group, 'status': 'complete', 'reused': False,
            'config': settings, 'source_sha256': digest, 'runtime_seconds': elapsed,
            'output_directory': str(output), **verified}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scope', choices=['benchmark','matrix','sensitivity','optional','all'], default='matrix')
    parser.add_argument('--models', nargs='+', default=['cac2','main9','final_ca'])
    parser.add_argument('--workers', type=int, choices=[1,2], default=2)
    parser.add_argument('--timeout-seconds', type=float, default=3600)
    parser.add_argument('--runs-dir', type=Path, default=HERE/'runs')
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    digest, classes = freeze_build()
    planned = jobs(args.scope, args.models)
    started = time.monotonic()
    results = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(run_one, group, settings, digest, classes, args.runs_dir,
                               args.timeout_seconds, args.dry_run) for group, settings in planned]
        for future in as_completed(futures):
            results.append(future.result())
    manifest = args.manifest or HERE / f'{args.scope}-{digest[:12]}.json'
    payload = {'created_utc': datetime.now(timezone.utc).isoformat(), 'scope': args.scope,
               'source_sha256': digest, 'workers': args.workers, 'timeout_seconds': args.timeout_seconds,
               'wall_seconds': time.monotonic()-started,
               'results': sorted(results, key=lambda value: value['name'])}
    if manifest.exists():
        raise RuntimeError(f'Manifest exists; specify a new --manifest: {manifest}')
    write_new_json(manifest, payload)
    print(f'MANIFEST {manifest}', flush=True)
    return 1 if any(r['status']=='failed' for r in results) else 0


if __name__ == '__main__':
    raise SystemExit(main())
