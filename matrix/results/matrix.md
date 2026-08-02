# Fault-by-check matrix — run of 2026-08-02T10:55:33Z

Every cell is the exit status of a real check against a really rebuilt pair of
artifacts. S/G/D are the matrix; U is the footnote (see run_matrix.sh).

| fault | S (same-runtime) | G (golden) | D (differential) | U (cadence unit test) |
|---|---|---|---|---|
| baseline | pass | pass | pass | pass |
| F1_runtime_supplied_rng | pass | pass | FAIL | pass |
| F2_shared_constant | pass | FAIL | pass | pass |
| F3_untraced_cadence | pass | pass | pass | pass |

Comparator verdicts per case are in `*_comparator.txt`; full log in `run.log`.
