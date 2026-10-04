# Inconel 617 hot-deformation simulation

The runnable implementation is in [corrected/](corrected/README.md). It models
discontinuous dynamic recrystallization with a two-dimensional cellular
automaton, consistent units and reproducible seeded runs. The original Java
prototypes and supplied reports are preserved for comparison.

Requires Java 17 or newer. From this directory:

```bash
./corrected/test.sh
./corrected/run.sh --preset final-report --temperature 1050 --strain-rate 0.001 --seed 42
```

- [Revised technical report — PDF](corrected/report/inconel617_revised_technical_report.pdf)
- [Editable report — Word](corrected/report/inconel617_revised_technical_report.docx)
- [Model comparison and sensitivity results](corrected/analysis/summary.md)
- [Material equations and source provenance](corrected/MODEL_NOTES.md)
- [Verification record](corrected/VERIFICATION.md)
- [Numerical consistency and process-trend checks](corrected/analysis/validation.md)
- [Reproduce the comparison batches](corrected/analysis/PLAN.md)

The project contains a working computational model and numerical checks.
Experimental validation requires measured stress–strain and microstructure
data; the current outputs are simulations rather than validated material
predictions. Generated builds and routine simulation outputs are excluded
from Git. Completed comparison records and report figures are retained,
along with one frozen reference build for checking their executable identity.
