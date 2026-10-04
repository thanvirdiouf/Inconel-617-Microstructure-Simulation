#!/usr/bin/env python3
"""Validate a saved partial grid prefix and compare its exact common strain.

This is an intermediate observation, not completed-run or endpoint validation.
It never modifies the raw run artifacts.
"""
import csv
import hashlib
import json
import math
from pathlib import Path
from batch_compare import HERE, properties, verify_build_integrity

ROOT = HERE / 'runs'
EVIDENCE = HERE / 'partial-grid-evidence'
BASE = EVIDENCE / 'baseline' if EVIDENCE.exists() else ROOT / 'final_ca_1100C_rate0p001_seed42_400x400_cell1um_step0p00138_strain1p38'
GRID = EVIDENCE / 'grid' if EVIDENCE.exists() else ROOT / 'final_ca_1100C_rate0p001_seed42_800x800_cell0p5um_step0p00138_strain1p38'
STRAIN = .5


def filehash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_csv(path):
    with path.open() as stream:
        return [{k: float(v) for k, v in row.items()} for row in csv.DictReader(stream)]


def main():
    baseline_complete=json.loads((BASE/'completed.json').read_text())
    grid_request=json.loads((GRID/'requested.json').read_text())
    stopped=json.loads((GRID/'stopped.json').read_text())
    base_identity=baseline_complete['identity']
    grid_identity=grid_request['identity']
    assert stopped['identity']==grid_identity and stopped['status']=='stopped_optional'
    assert not (GRID/'completed.json').exists() and not (GRID/'output/summary.txt').exists()
    digest=grid_identity['source_sha256']
    assert digest==base_identity['source_sha256']
    build=HERE/'builds'/digest
    snapshot=sorted((build/'src/inconel617').glob('*.java'))
    actual_source=hashlib.sha256(b''.join(p.name.encode()+b'\0'+p.read_bytes() for p in snapshot)).hexdigest()
    assert actual_source==digest==json.loads((build/'compiled.json').read_text())['source_sha256']
    engine=verify_build_integrity(build)
    mapping={'cell_size_um':'cell_size_m','initial_grain_diameter_um':'initial_grain_diameter_m',
             'nucleus_diameter_um':'nucleus_diameter_m','nucleation_band_width_um':'nucleation_band_width_m'}
    data=[]
    for run,identity,role in [(BASE,base_identity,'completed_baseline'),(GRID,grid_identity,'stopped_optional_grid')]:
        settings=identity['config']
        metadata=properties(run/'output/run.properties')
        assert metadata['engine_bytecode_sha256']==engine
        for name,value in settings.items():
            actual=metadata[mapping.get(name,name)]
            if name in ('model','images'):
                assert actual==str(value).lower()
            else:
                expected=value*1e-6 if name in mapping else value
                assert math.isclose(float(actual),expected,rel_tol=2e-10,abs_tol=1e-15)
        assert float(metadata['strain_rate_multiplier'])==1
        rows=read_csv(run/'output/results.csv')
        prefix=[r for r in rows if r['strain']<=STRAIN]
        assert len(prefix)==11
        for index,row in enumerate(prefix):
            assert all(math.isfinite(v) and v>=0 for v in row.values())
            assert math.isclose(row['strain'],index*.05,abs_tol=2e-11)
            assert math.isclose(row['time_s'],row['strain']/settings['strain_rate_s^-1'],rel_tol=2e-10,abs_tol=1e-9)
            assert row['drx_fraction']<=1
            assert row['max_cell_travel']<=settings['max_cell_travel']+2e-10
            if index:
                assert row['strain']>prefix[index-1]['strain']
                assert row['time_s']>prefix[index-1]['time_s']
        selected=prefix[-1]
        assert selected['strain']==STRAIN
        domain_area=settings['width']*settings['height']*settings['cell_size_um']**2
        selected={**selected,'cumulative_nuclei_per_um2':selected['nuclei_count']/domain_area}
        data.append({'role':role,'run_directory':str(run),'config':settings,
                     'source_sha256':digest,'engine_bytecode_sha256':engine,
                     'csv_sha256':filehash(run/'output/results.csv'),
                     'metadata_sha256':filehash(run/'output/run.properties'),
                     'prefix_rows_checked':len(prefix),'last_saved_strain':rows[-1]['strain'],
                     'matched_sample':selected})
    assert data[0]['csv_sha256']==baseline_complete['verification']['results_sha256']
    # All physical assumptions except resolution are shared; integer seed
    # positions and subsequent stochastic events are not physically paired.
    base_config,grid_config=data[0]['config'],data[1]['config']
    for name in base_config:
        if name not in ('width','height','cell_size_um'):
            assert base_config[name]==grid_config[name]
    for name in ('width','height'):
        assert base_config[name]*base_config['cell_size_um']==grid_config[name]*grid_config['cell_size_um']==400
    fields=['flow_stress_MPa','mean_grain_diameter_um','drx_fraction','cumulative_nuclei_per_um2']
    differences={}
    for field in fields:
        base=data[0]['matched_sample'][field]
        grid=data[1]['matched_sample'][field]
        differences[field]={'baseline':base,'partial_grid':grid,'absolute_delta':grid-base,
                            'percent_delta':None if base==0 else (grid/base-1)*100}
    result={'status':'intermediate_diagnostic_only','matched_strain':STRAIN,
            'temperature_C':1100,'strain_rate_s^-1':.001,
            'source_sha256':digest,'engine_bytecode_sha256':engine,
            'runs':data,'differences':differences,
            'checks':['requested/stopped/config/source/executable identities match',
                      'baseline saved CSV hash unchanged',
                      'both prefixes contain exact 0..0.5 samples at 0.05 spacing',
                      'prefix finite nonnegative values, monotone strain/time, time=strain/rate, DRX and hazard bounds'],
            'sealed_evidence_directory':str(EVIDENCE) if EVIDENCE.exists() else None,
            'limitations':['The 0.5 µm grid run is incomplete and stopped at strain 0.95; these values are at common strain 0.5, not endpoint 1.38.',
                           'Grid refinement changes integer Voronoi seed-placement bounds, so shared seed 42 does not preserve the same physical initial realization.',
                           'This single intermediate comparison does not establish spatial convergence or empirical agreement.']}
    (HERE/'partial_grid_diagnostic.json').write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    lines=['# Partial grid diagnostic at matched strain 0.5','',
           'This intermediate diagnostic compares the completed 1 µm baseline with a saved prefix of the stopped 0.5 µm grid at FINAL_CA, 1100 °C and 0.001 s⁻¹. It is not a completed refinement or an endpoint comparison at strain 1.38. Both physical domains are 400 × 400 µm.',
           '', '| Quantity | Baseline, 1 µm | Partial grid, 0.5 µm | Change (%) |',
           '|---|---:|---:|---:|']
    for field,label in [('flow_stress_MPa','Flow stress (MPa)'),('mean_grain_diameter_um','Mean grain diameter (µm)'),('drx_fraction','DRX area fraction'),('cumulative_nuclei_per_um2','Cumulative nuclei/µm²')]:
        v=differences[field]
        delta='n.a.' if v['percent_delta'] is None else f'{v["percent_delta"]:+.3f}'
        lines.append(f'| {label} | {v["baseline"]:.8g} | {v["partial_grid"]:.8g} | {delta} |')
    lines += ['', 'The two prefixes passed configuration/source/executable identity checks, exact common samples, finite nonnegative outputs, monotone strain/time, time = strain/rate, and DRX/hazard bounds. The baseline CSV matches its saved completion hash; both raw CSV and metadata hashes are recorded in the JSON.',
              '', 'The same seed does not preserve physical Voronoi seed positions across cell resolutions. This diagnostic therefore includes a changed initial realization and subsequent stochastic evolution. It does not establish spatial convergence or empirical agreement. Raw results were not altered.',
              '', 'Sealed raw CSVs, run metadata and controller identities are retained under `partial-grid-evidence/`, outside ignored run directories. Its manifest records SHA-256 hashes and original paths. The copied baseline completion and stopped-grid records preserve their distinct statuses.']
    (HERE/'partial_grid_diagnostic.md').write_text('\n'.join(lines)+'\n')
    print(json.dumps({'matched_strain':STRAIN,'differences':differences},indent=2))


if __name__=='__main__':
    main()
