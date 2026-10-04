#!/usr/bin/env python3
"""Verify completed runs, aggregate numerical metrics, and make scientific plots."""
from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path

from reportlab.graphics.charts.lineplots import LinePlot
from reportlab.graphics.shapes import Drawing, String, Line, Rect, Group
from reportlab.graphics import renderSVG
from reportlab.lib import colors
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
import subprocess

from batch_compare import CONDITIONS, HERE, properties, verify_completed, verify_build_integrity

COLORS = {'cac2': '#2474b8', 'main9': '#e27b22', 'final_ca': '#278558'}
LABELS = {'cac2': 'CAC2', 'main9': 'MAIN9', 'final_ca': 'FINAL_CA'}


def read_records(runs, reuse_verification=False):
    records, incomplete = [], []
    for path in sorted(runs.iterdir() if runs.exists() else []):
        if not path.is_dir():
            continue
        if not (path/'completed.json').exists():
            incomplete.append(str(path))
            continue
        completed = json.loads((path/'completed.json').read_text())
        settings = completed['identity']['config']
        if settings['target_strain'] != 1.38:
            continue
        output = path / 'output'
        if reuse_verification:
            verified=completed['verification']
        else:
            engine = verify_build_integrity(HERE/'builds'/completed['identity']['source_sha256'])
            verified = verify_completed(output, settings, engine)
            if verified != completed['verification']:
                raise RuntimeError(f'Artifacts changed after completion: {path}')
        requested = json.loads((path/'requested.json').read_text())
        with (output/'results.csv').open() as stream:
            rows = [{key: float(value) for key, value in row.items()} for row in csv.DictReader(stream)]
        records.append({'name': path.name, 'group': requested['group'], 'config': settings,
                        'source_sha256': completed['identity']['source_sha256'],
                        'runtime_seconds': completed['runtime_seconds'], 'rows': rows,
                        'output_directory': str(output), 'verification': verified,
                        'metadata': properties(output/'run.properties')})
    digests = set(r['source_sha256'] for r in records)
    if len(digests) > 1:
        raise RuntimeError('Completed production runs have mixed source hashes; use separate --runs-dir.')
    if len(set(r['metadata']['engine_bytecode_sha256'] for r in records)) > 1:
        raise RuntimeError('Completed production runs have mixed executable fingerprints.')
    return records, incomplete


def metric(record):
    c, rows, verified = record['config'], record['rows'], record['verification']
    first, last = rows[0], rows[-1]
    area = c['width'] * c['height'] * c['cell_size_um']**2
    result = {'run': record['name'], 'group': record['group'], 'model': c['model'],
              'temperature_C': c['temperature_C'], 'strain_rate_s^-1': c['strain_rate_s^-1'],
              'seed': c['seed'], 'domain_width_um': c['width']*c['cell_size_um'],
              'domain_height_um': c['height']*c['cell_size_um'], 'cell_size_um': c['cell_size_um'],
              'physical_domain_area_um2': area,
              'max_strain_step': c['max_strain_step'], 'max_cell_travel_cap': c['max_cell_travel'],
              'sample_spacing_strain': c['output_strain_step'],
              'nominal_initial_grain_diameter_um': c['initial_grain_diameter_um'],
              'nucleus_diameter_um': c['nucleus_diameter_um'],
              'nucleation_band_width_um': c['nucleation_band_width_um'],
              'actual_initial_mean_diameter_um': first['mean_grain_diameter_um'],
              'initial_grain_count': int(first['grain_count']), 'final_strain': last['strain'],
              'final_time_s': last['time_s'], 'final_flow_stress_MPa': last['flow_stress_MPa'],
              'sampled_peak_stress_MPa': verified['sampled_peak_stress_MPa'],
              'sampled_peak_strain': verified['sampled_peak_strain'],
              'final_mean_grain_diameter_um': last['mean_grain_diameter_um'],
              'final_mean_drx_grain_diameter_um': last['mean_drx_grain_diameter_um'],
              'final_drx_fraction': last['drx_fraction'],
              'maximum_sampled_drx_fraction': max(r['drx_fraction'] for r in rows),
              'final_grain_count': int(last['grain_count']),
              'final_drx_grain_count': int(last['drx_grain_count']),
              'cumulative_nuclei': int(last['nuclei_count']), 'integration_steps': int(last['step_count']),
              'cumulative_nuclei_per_um2': last['nuclei_count']/area,
              'maximum_observed_cell_travel': last['max_cell_travel'],
              'wall_runtime_seconds': record['runtime_seconds'],
              'source_sha256': record['source_sha256'],
              'engine_bytecode_sha256': record['metadata'].get('engine_bytecode_sha256', 'unrecorded'),
              'output_directory': record['output_directory']}
    for fraction, label in [(0,'positive'),(.01,'at_least_0p01'),(.5,'at_least_0p5'),(.9,'at_least_0p9')]:
        found = next((r['strain'] for r in rows
                      if (r['drx_fraction'] > 0 if fraction == 0
                          else r['drx_fraction'] >= fraction)), None)
        result['first_sample_strain_DRX_'+label] = found
    return result


def setup_plot():
    font = Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
    pdfmetrics.registerFont(TTFont('DejaVu',str(font)))


def ticks_for(values):
    largest=max(values,default=0)
    if largest<=0:
        return 1,[0,.25,.5,.75,1]
    desired=largest/4
    power=10**math.floor(math.log10(desired))
    step=next(mult*power for mult in [1,2,2.5,5,10] if mult*power>=desired)
    upper=math.ceil(largest/step)*step
    return upper,[i*step for i in range(round(upper/step)+1)]


def add_plot(drawing, x, y, width, height, series, field, ylabel, title):
    drawing.add(String(x+width/2,y+height-20,title,fontName='DejaVu',fontSize=15,textAnchor='middle'))
    if not series:
        drawing.add(String(x+width/2,y+height/2,'Run pending',fontName='DejaVu',fontSize=14,textAnchor='middle'))
        return
    chart=LinePlot()
    chart.x=x+90
    chart.y=y+63
    chart.width=width-110
    chart.height=height-110
    chart.data=[[(r['strain'],r[field]) for r in record['rows']] for record,_,_ in series]
    chart.xValueAxis.valueMin=0
    chart.xValueAxis.valueMax=1.38
    chart.xValueAxis.valueSteps=[0,.3,.6,.9,1.2,1.38]
    chart.xValueAxis.labelTextFormat='%g'
    chart.xValueAxis.labels.fontName='DejaVu'
    chart.xValueAxis.labels.fontSize=11
    chart.yValueAxis.labels.fontName='DejaVu'
    chart.yValueAxis.labels.fontSize=11
    chart.yValueAxis.valueMin=0
    chart.yValueAxis.labelTextFormat='%g'
    if field=='drx_fraction':
        chart.yValueAxis.valueMax=1
        chart.yValueAxis.valueSteps=[0,.25,.5,.75,1]
    else:
        upper,ticks=ticks_for([r[field] for record,_,_ in series for r in record['rows']])
        chart.yValueAxis.valueMax=upper
        chart.yValueAxis.valueSteps=ticks
    chart.yValueAxis.visibleGrid=1
    chart.yValueAxis.gridStrokeColor=colors.HexColor('#e2e5e9')
    chart.yValueAxis.gridStrokeWidth=.6
    chart.xValueAxis.strokeColor=chart.yValueAxis.strokeColor=colors.HexColor('#65717f')
    for i,(_,color,dashes) in enumerate(series):
        chart.lines[i].strokeColor=colors.HexColor(color)
        chart.lines[i].strokeWidth=2.3
        if dashes:
            chart.lines[i].strokeDashArray=dashes
    drawing.add(chart)
    drawing.add(String(x+90+chart.width/2,y+13,'Applied true strain (dimensionless)',
                       fontName='DejaVu',fontSize=12,textAnchor='middle'))
    label=Group(String(0,0,ylabel,fontName='DejaVu',fontSize=12,textAnchor='middle'))
    label.translate(x+22,y+63+chart.height/2)
    label.rotate(90)
    drawing.add(label)


def add_legend(drawing, series, y):
    gap=220
    start=(drawing.width-(len(series)-1)*gap)/2-60
    for i,(label,color,dashes) in enumerate(series):
        line=Line(start+i*gap,y,start+i*gap+38,y,strokeColor=colors.HexColor(color),strokeWidth=2.3)
        if dashes:
            line.strokeDashArray=dashes
        drawing.add(line)
        drawing.add(String(start+i*gap+48,y-4,label,fontName='DejaVu',fontSize=13))


def white_drawing(width,height):
    drawing=Drawing(width,height)
    drawing.add(Rect(0,0,width,height,strokeColor=None,fillColor=colors.white))
    return drawing


def save_plot(drawing,path):
    svg=path.with_suffix('.svg')
    svg.write_text(renderSVG.drawToString(drawing))
    node=Path('/home/spectre/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node')
    sharp=Path('/home/spectre/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/sharp')
    code='const sharp=require(process.argv[1]); sharp(process.argv[2],{density:144}).png().toFile(process.argv[3]).catch(e=>{console.error(e);process.exit(1)});'
    subprocess.run([str(node),'-e',code,str(sharp),str(svg),str(path)],check=True,capture_output=True,text=True)


def plot_matrix(records, output):
    figures = []
    matrix = [r for r in records if r['group']=='condition_matrix']
    if not matrix:
        return figures
    specs = [('flow_stress_MPa','Flow stress (MPa)','flow_stress'),
             ('drx_fraction','DRX area fraction','drx_fraction'),
             ('mean_grain_diameter_um','Mean grain diameter (µm)','grain_diameter'),
             ('mean_drx_grain_diameter_um','Mean DRX grain diameter (µm)','drx_grain_diameter')]
    for field, ylabel, stem in specs:
        drawing=white_drawing(1440,850)
        drawing.add(String(720,821,'Corrected models: 400 × 400 µm domain, 58 µm nominal initial grains, seed 42',
                           fontName='DejaVu',fontSize=17,textAnchor='middle'))
        add_legend(drawing,[(LABELS[m],COLORS[m],None) for m in COLORS],790)
        if field=='mean_drx_grain_diameter_um':
            drawing.add(String(720,766,'Zero DRX diameter marks no active DRX grains; it is not a physical zero-size grain.',
                               fontName='DejaVu',fontSize=11,textAnchor='middle'))
        for index,(temperature, rate) in enumerate(CONDITIONS):
            subset = [r for r in matrix if (r['config']['temperature_C'],r['config']['strain_rate_s^-1'])==(temperature,rate)]
            series=[(r,COLORS[r['config']['model']],None) for r in sorted(subset,key=lambda r:list(COLORS).index(r['config']['model']))]
            add_plot(drawing,(index%3)*480,385 if index<3 else 10,470,360,series,field,ylabel,
                     f'{temperature} °C, {rate:g} s⁻¹')
        path = output/f'comparison_{stem}.png'
        save_plot(drawing,path)
        figures.append(str(path))
    return figures


def plot_sensitivity(records, output):
    figures = []
    baseline = next((r for r in records if r['group']=='selected_baseline'),None)
    if not baseline:
        return figures
    panels = [('seed_sensitivity', 'Random seed', lambda r:f'Seed {r["config"]["seed"]}'),
              ('domain_sensitivity','Domain extent', lambda r:f'{r["config"]["width"]*r["config"]["cell_size_um"]:g} × {r["config"]["height"]*r["config"]["cell_size_um"]:g} µm'),
              ('timestep_sensitivity','Strain step cap', lambda r:f'Δε ≤ {r["config"]["max_strain_step"]:g}'),
              ('grid_sensitivity','Cell spacing', lambda r:f'{r["config"]["cell_size_um"]:g} µm')]
    specs = [('flow_stress_MPa','Flow stress (MPa)'),('drx_fraction','DRX area fraction'),
             ('mean_grain_diameter_um','Mean grain diameter (µm)')]
    for group,title,label in panels:
        comparisons = [r for r in records if r['group']==group]
        if not comparisons:
            continue
        drawing=white_drawing(1440,465)
        drawing.add(String(720,448,f'FINAL_CA at 1100 °C, 0.001 s⁻¹: {title.lower()}',
                           fontName='DejaVu',fontSize=17,textAnchor='middle'))
        colors = ['#222222','#577dbb','#c37330']
        series=[(r,colors[i%len(colors)],None if i==0 else [8,4] if i==1 else [2,3])
                for i,r in enumerate([baseline]+comparisons)]
        add_legend(drawing,[(label(r),color,dashes) for r,color,dashes in series],412)
        for index,(field,ylabel) in enumerate(specs):
            add_plot(drawing,index*480,25,470,350,series,field,ylabel,'')
        if group=='grid_sensitivity':
            drawing.add(String(720,8,'Same seed; integer seed placement changes the initial realization across grids.',
                               fontName='DejaVu',fontSize=11,textAnchor='middle'))
        path=output/f'{group}.png'
        save_plot(drawing,path)
        figures.append(str(path))
    return figures


def plot_final_ca(records,output):
    matrix=[r for r in records if r['group']=='condition_matrix' and r['config']['model']=='final_ca']
    if len(matrix)!=len(CONDITIONS):
        return []
    drawing=white_drawing(1440,500)
    drawing.add(String(720,477,'FINAL_CA: six reported conditions, 400 × 400 µm domain, seed 42',
                       fontName='DejaVu',fontSize=17,textAnchor='middle'))
    palette=['#0072b2','#d55e00','#009e73','#cc79a7','#e69f00','#56b4e9']
    series=[]
    for index,(temperature,rate) in enumerate(CONDITIONS):
        record=next(r for r in matrix if (r['config']['temperature_C'],r['config']['strain_rate_s^-1'])==(temperature,rate))
        color=palette[index]
        dashes=None if index%2==0 else [7,4]
        series.append((record,color,dashes))
        x=100+(index%3)*450
        y=438 if index<3 else 405
        line=Line(x,y,x+35,y,strokeColor=colors.HexColor(color),strokeWidth=2.3)
        if dashes:
            line.strokeDashArray=dashes
        drawing.add(line)
        drawing.add(String(x+45,y-4,f'{temperature} °C, {rate:g} s⁻¹',fontName='DejaVu',fontSize=13))
    for index,(field,ylabel) in enumerate([('flow_stress_MPa','Flow stress (MPa)'),
                                          ('drx_fraction','DRX area fraction'),
                                          ('mean_grain_diameter_um','Mean grain diameter (µm)')]):
        add_plot(drawing,index*480,5,470,365,series,field,ylabel,'')
    path=output/'final_ca_process_curves.png'
    save_plot(drawing,path)
    return [str(path)]


def pct_delta(value, baseline):
    return None if baseline==0 else (value/baseline-1)*100


def sensitivity_metrics(metrics):
    base=next((m for m in metrics if m['group']=='selected_baseline'),None)
    if not base:
        return {}
    fields=['final_flow_stress_MPa','final_mean_grain_diameter_um','final_drx_fraction','cumulative_nuclei_per_um2']
    result={'baseline':base['run'],'relative_comparisons':[]}
    for m in metrics:
        if m['group'] in ('seed_sensitivity','domain_sensitivity','timestep_sensitivity','grid_sensitivity'):
            result['relative_comparisons'].append({'run':m['run'],'group':m['group'],
                'absolute_deltas':{field:m[field]-base[field] for field in fields},
                'percent_deltas':{field:pct_delta(m[field],base[field]) for field in fields}})
    seeds=sorted([m for m in metrics if m['group'] in ('selected_baseline','seed_sensitivity')],
                 key=lambda m:m['seed'])
    if len(seeds)>1:
        result['seed_ensemble']={'n':len(seeds), 'seeds':[m['seed'] for m in seeds],
                'final_ranges':{field:{'minimum':min(m[field] for m in seeds),
                                      'maximum':max(m[field] for m in seeds),
                                      'mean':sum(m[field] for m in seeds)/len(seeds)} for field in fields}}
    return result


def format_table(metrics):
    lines=['| Model | T (°C) | Rate (s⁻¹) | Final stress (MPa) | Sampled peak (MPa) | Final DRX fraction | Final mean diameter (µm) | Nuclei | Steps |',
           '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    for m in metrics:
        if m['group']=='condition_matrix':
            lines.append(f'| {LABELS[m["model"]]} | {m["temperature_C"]} | {m["strain_rate_s^-1"]:g} | {m["final_flow_stress_MPa"]:.3f} | {m["sampled_peak_stress_MPa"]:.3f} | {m["final_drx_fraction"]:.6f} | {m["final_mean_grain_diameter_um"]:.3f} | {m["cumulative_nuclei"]} | {m["integration_steps"]} |')
    return lines


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs-dir',type=Path,default=HERE/'runs')
    parser.add_argument('--output-dir',type=Path,default=HERE)
    parser.add_argument('--reuse-verification',action='store_true',
                        help='Aggregate already verified completed records without repeating verification.')
    args=parser.parse_args()
    args.output_dir.mkdir(parents=True,exist_ok=True)
    records,incomplete=read_records(args.runs_dir,args.reuse_verification)
    metrics=[metric(r) for r in records]
    metrics.sort(key=lambda m:(m['group'],m['temperature_C'],m['strain_rate_s^-1'],m['model'],m['seed'],m['domain_width_um']))
    if not metrics:
        raise RuntimeError('No verified complete production runs to summarize.')
    with (args.output_dir/'comparison_metrics.csv').open('w',newline='') as stream:
        writer=csv.DictWriter(stream,fieldnames=list(metrics[0]),lineterminator='\n')
        writer.writeheader()
        writer.writerows(metrics)
    setup_plot()
    figures=plot_matrix(records,args.output_dir)+plot_sensitivity(records,args.output_dir)+plot_final_ca(records,args.output_dir)
    expected={(model,t,r) for model in LABELS for t,r in CONDITIONS}
    available={(m['model'],m['temperature_C'],m['strain_rate_s^-1']) for m in metrics if m['group']=='condition_matrix'}
    sensitivity=sensitivity_metrics(metrics)
    counts={group:sum(m['group']==group for m in metrics) for group in [
            'condition_matrix','selected_baseline','seed_sensitivity','domain_sensitivity',
            'timestep_sensitivity','grid_sensitivity']}
    class_versions=sorted(set(int.from_bytes(path.read_bytes()[6:8],'big')
                        for path in (HERE/'builds'/records[0]['source_sha256']/'classes').rglob('*.class')))
    summary={'scope':'corrected-model numerical comparison; empirical calibration unverified',
             'completed_runs':len(metrics),'matrix_complete':expected==available,
             'verification_reused':args.reuse_verification,
             'coverage_counts':counts,
             'sensitivity_complete':counts['selected_baseline']==1 and counts['seed_sensitivity']==2 and counts['domain_sensitivity']==2,
             'optional_refinements_complete':counts['timestep_sensitivity']==1 and counts['grid_sensitivity']==1,
             'missing_matrix_runs':[{'model':m,'temperature_C':t,'strain_rate_s^-1':r} for m,t,r in sorted(expected-available)],
             'incomplete_run_directories':incomplete,'source_sha256':records[0]['source_sha256'],
             'engine_bytecode_sha256':sorted(set(m['engine_bytecode_sha256'] for m in metrics)),
             'java_runtime_versions':sorted(set(r['metadata']['java_version'] for r in records)),
             'batch_class_major_versions':class_versions,
             'compiler_note':'This saved batch used the available javac 21 without --release; executable class major version 65. Regular test.sh targets Java 17 separately.',
             'figures':figures,'metrics':metrics,'sensitivity':sensitivity,
             'coverage_notes':['Peak stress is the maximum of saved samples at strain spacing 0.05 and the exact endpoint, not the maximum of every integration step.',
                               'DRX threshold columns record the first observed saved crossing; earlier crossings may occur between samples because current DRX ownership can decrease.',
                               'Three seeds form a small stochastic sample, not an uncertainty distribution.',
                               'Domain variation changes initial grain number/geometry; a shared seed does not preserve the same initial microstructure.',
                               'Cumulative nuclei is an extensive count that scales with domain area. Domain comparisons use cumulative nuclei per µm².',
                               'One timestep halving and one spatial refinement, when present, do not establish full convergence.',
                               'Grid refinement also changes the initial realization: integer seed placement uses different grid bounds, so the same random seed does not preserve physical Voronoi seed coordinates.',
                               'DRX fraction describes current recrystallized cell ownership and may decrease as small nuclei disappear.',
                               'Matched-condition plots contain recorded simulator outputs, not experimental measurements.']}
    diagnostic_path=HERE/'partial_grid_diagnostic.json'
    if counts['grid_sensitivity']==0 and diagnostic_path.exists():
        diagnostic=json.loads(diagnostic_path.read_text())
        summary['partial_grid_diagnostic']={
            'status':diagnostic['status'],'matched_strain':diagnostic['matched_strain'],
            'json_path':str(diagnostic_path),'markdown_path':str(HERE/'partial_grid_diagnostic.md'),
            'sealed_evidence_directory':diagnostic['sealed_evidence_directory'],
            'differences':diagnostic['differences']}
    (args.output_dir/'summary.json').write_text(json.dumps(summary,indent=2,allow_nan=False)+'\n')
    lines=['# Corrected Inconel 617 numerical comparisons','',
           f'{len(metrics)} verified completed runs. Condition matrix: {"complete (18 of 18)" if expected==available else f"{len(available)} of 18 complete"}.',
           '', 'All matrix runs use a 400 × 400 µm domain, 1 µm spacing, nominal initial grain diameter 58 µm, nucleus diameter 1 µm, reactive band 2 µm, seed 42, strain 1.38, strain cap 0.00138 and hazard cap 0.25. Saved sample spacing is 0.05 plus the exact endpoint.',
           '', 'The material parameter sets retain different constitutive laws. These are numerical comparisons without experimental validation.',
           '', '## Matrix results','',*format_table(metrics),'',
           'Peak stresses are sampled maxima. A stress peak between samples may be higher. DRX thresholds record first observed saved crossings; earlier crossings may occur between samples if current DRX ownership later decreases.',
           '', '## Sensitivity coverage','']
    for group in ['selected_baseline','seed_sensitivity','domain_sensitivity','timestep_sensitivity','grid_sensitivity']:
        subset=[m for m in metrics if m['group']==group]
        lines.append(f'- {group.replace("_"," ")}: {len(subset)} complete run(s).')
    if sensitivity.get('seed_ensemble'):
        ensemble=sensitivity['seed_ensemble']
        lines += ['',f'Seeds {ensemble["seeds"]} provide a small stochastic sensitivity sample.']
        for field,units in [('final_flow_stress_MPa','MPa'),('final_mean_grain_diameter_um','µm'),('final_drx_fraction','')]:
            values=ensemble['final_ranges'][field]
            lines.append(f'- {field.replace("_"," ")}: {values["minimum"]:.6g}–{values["maximum"]:.6g} {units}.')
    lines += ['', '| Change | Seed | Domain (µm) | Cell (µm) | Strain cap | Final stress (MPa) | Final mean diameter (µm) | DRX fraction | Nuclei/µm² |',
              '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    names={'selected_baseline':'Baseline','seed_sensitivity':'Seed',
           'domain_sensitivity':'Domain','timestep_sensitivity':'Timestep','grid_sensitivity':'Grid'}
    for m in sorted((m for m in metrics if m['group']!='condition_matrix'),
                    key=lambda m:(list(names).index(m['group']),m['seed'],m['domain_width_um'])):
        lines.append(f'| {names[m["group"]]} | {m["seed"]} | {m["domain_width_um"]:g} | {m["cell_size_um"]:g} | {m["max_strain_step"]:g} | {m["final_flow_stress_MPa"]:.4f} | {m["final_mean_grain_diameter_um"]:.4f} | {m["final_drx_fraction"]:.6f} | {m["cumulative_nuclei_per_um2"]:.8f} |')
    lines += ['', 'Domain changes keep physical cell, grain, nucleus and band sizes fixed, but change grain number and seeded geometry. The optional timestep/grid comparisons are individual diagnostics, not a full convergence study.',
              '', 'Grid refinement changes integer seed-placement bounds. The same random seed therefore does not preserve physical Voronoi seed coordinates, and minimum-spacing rejection may change later random draws. This grid diagnostic includes a changed initial realization; a paired refinement study would require shared physical seed coordinates.',
              '', 'Mean diameters are number means of live area-equivalent grain diameters. A zero DRX mean means no active DRX grain. Current DRX area fraction can decline when nuclei disappear; cumulative nuclei count can continue increasing. Cumulative nuclei is an extensive count, so domain sensitivity comparisons use cumulative nuclei per physical area (µm⁻²).',
              '', '## Verification and provenance','',
              'Every aggregated run passed matching configuration metadata, successful completion status, saved-file hashes, exact target strain, complete sample coordinates, finite nonnegative values, time = strain/rate, monotone strain/time, summary/CSV reconciliation, DRX fraction bounds and observed hazard ≤ 0.25.',
              '', f'Source SHA-256: `{summary["source_sha256"]}`.',
              '', 'The saved comparison batch used the available Java 21 compiler without a --release target and ran on Java 21. The frozen class files have major version 65 and require Java 21 or newer. The separate regular test script targets Java 17; its bytecode is not the saved comparison build.',
              '', 'Raw results, metadata, console logs and controller manifests are retained under `runs/`.',
              '', '## Interpretation limits','',
              'At 1200 °C / 0.001 s⁻¹, all three corrected models end with one live grain filling the 400 × 400 µm domain. The reported 451.352 µm diameter is the equivalent circular diameter of that square area; it demonstrates domain-limited coarsening in this simulation and should not be treated as a material-scale prediction.',
              '', '## Figures','']
    lines.extend(f'- [{Path(path).name}]({Path(path).name})' for path in figures)
    if incomplete:
        lines += ['', 'Incomplete runs were preserved and excluded:', *[f'- `{Path(path).relative_to(HERE) if Path(path).is_relative_to(HERE) else path}`' for path in incomplete]]
        if any((Path(path)/'stopped.json').exists() for path in incomplete):
            lines += ['', 'The optional 0.5 µm grid run was stopped at the user’s request to finish promptly. Its partial CSV and metadata are preserved, and it is not treated as a completed refinement or included in comparison metrics.']
    if 'partial_grid_diagnostic' in summary:
        lines += ['', '## Intermediate grid diagnostic','',
                  'A separately validated saved prefix compares the 1 µm baseline and stopped 0.5 µm grid at exact common strain 0.5. It is not an endpoint comparison at 1.38 or a completed refinement. See [partial_grid_diagnostic.md](partial_grid_diagnostic.md) and its sealed raw evidence under `partial-grid-evidence/`.']
    (args.output_dir/'summary.md').write_text('\n'.join(lines)+'\n')
    print(json.dumps({'completed_runs':len(metrics),'matrix_complete':summary['matrix_complete'],
                      'figures':figures,'summary':str(args.output_dir/'summary.md')},indent=2))


if __name__=='__main__':
    main()
