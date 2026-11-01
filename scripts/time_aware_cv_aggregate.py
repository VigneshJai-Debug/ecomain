"""Aggregate the blocked time-aware EcoCloud CV runs.

Computes carbon reduction relative to FCFS paired within the same region/seed,
plus service-quality metrics. The script deliberately does not collapse the
metrics into a single weighted score: choose a deadline reliability constraint
first, then compare carbon savings among policies meeting it.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import numpy as np
import pandas as pd
from scipy import stats


def mean_ci(values: pd.Series) -> tuple[float, float, float]:
    x = pd.to_numeric(values, errors="coerce").dropna().to_numpy(dtype=float)
    if len(x) == 0:
        return np.nan, np.nan, np.nan
    mean = float(np.mean(x))
    if len(x) == 1:
        return mean, np.nan, np.nan
    sem = stats.sem(x)
    half = float(stats.t.ppf(0.975, len(x) - 1) * sem)
    return mean, mean - half, mean + half


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", type=Path, default=Path("results/time_aware_cv"))
    args = ap.parse_args()
    root = args.input
    files = sorted(root.glob("fold_*/**/*.csv"))
    # Do not accidentally read companion jobs/decisions files.
    files = [p for p in files if p.name in {"uk_clean.csv", "india_gapfilled_linear.csv"}]
    if not files:
        raise SystemExit(f"No fold result CSVs found under {root}")

    frames = []
    for path in files:
        df = pd.read_csv(path)
        required_cols = {
            "experiment_id", "region", "seed", "algorithm",
            "scheduled_jobs", "completed_jobs", "co2e_per_completed_job_g",
            "deadline_violations_over_60s", "deadline_violations",
            "mean_waiting_s", "scheduler_runtime_ms"
        }
        if not required_cols.issubset(df.columns):
            raise SystemExit(f"Unexpected result schema in {path}. Missing: {required_cols - set(df.columns)}")
        df["fold"] = int(path.parent.name.split("_")[-1])
        frames.append(df)
    data = pd.concat(frames, ignore_index=True)

    # 1. Distinguish submitted_jobs, admitted_jobs, rejected_jobs, and completed_jobs:
    if "submitted_jobs" in data.columns:
        data["submitted_jobs"] = data["submitted_jobs"].astype(int)
    else:
        # Sum of scheduled and failed equals total submitted workload
        data["submitted_jobs"] = (data["scheduled_jobs"] + data["failed_jobs"]).astype(int)

    if "admitted_jobs" in data.columns:
        data["admitted_jobs"] = data["admitted_jobs"].astype(int)
    else:
        data["admitted_jobs"] = data["scheduled_jobs"].astype(int)

    if "rejected_jobs" in data.columns:
        data["rejected_jobs"] = data["rejected_jobs"].astype(int)
    else:
        data["rejected_jobs"] = data["failed_jobs"].astype(int)

    data["completed_jobs"] = data["completed_jobs"].astype(int)

    # 2. Strict Validation of 60-job experiment design:
    expected_algorithms = {"FCFS", "Wiesner_2021_AdHoc", "Bahreini_SRR_APX", "Deadline_Carbon_Shift", "CADS_theta0", "CADS_theta5", "CADS_theta10"}
    expected_regions = {"GB", "INDIA"}
    expected_total_runs = 280  # 5 folds * 4 seeds * 2 regions * 7 algorithms

    if len(data) != expected_total_runs:
        raise SystemExit(f"Validation Error: Expected {expected_total_runs} CV run observations, found {len(data)}. Inspect fold execution logs.")

    found_algorithms = set(data["algorithm"].unique())
    if found_algorithms != expected_algorithms:
        raise SystemExit(f"Validation Error: Algorithm mismatch. Expected {expected_algorithms}, found {found_algorithms}")

    found_regions = set(data["region"].unique())
    if found_regions != expected_regions:
        raise SystemExit(f"Validation Error: Region mismatch. Expected {expected_regions}, found {found_regions}")

    # Check that EVERY run submitted exactly 60 jobs
    non_60_submitted = data[data["submitted_jobs"] != 60]
    if len(non_60_submitted) > 0:
        raise SystemExit(f"Validation Error: Found {len(non_60_submitted)} runs where submitted_jobs != 60!")

    # For Bahreini_SRR_APX, validate capacity-rejected jobs separately:
    bahreini_runs = data[data["algorithm"] == "Bahreini_SRR_APX"]
    non_bahreini_runs = data[data["algorithm"] != "Bahreini_SRR_APX"]

    bahreini_mismatch = bahreini_runs[bahreini_runs["admitted_jobs"] + bahreini_runs["rejected_jobs"] != 60]
    if len(bahreini_mismatch) > 0:
        raise SystemExit(f"Validation Error: Found {len(bahreini_mismatch)} Bahreini runs where admitted + rejected != 60!")

    # Non-Bahreini algorithms must have zero rejections and admit all 60 jobs
    non_bahreini_rejected = non_bahreini_runs[non_bahreini_runs["rejected_jobs"] != 0]
    if len(non_bahreini_rejected) > 0:
        raise SystemExit(f"Validation Error: Found {len(non_bahreini_rejected)} non-Bahreini runs with non-zero rejected jobs!")

    # All admitted jobs must execute to completion in CloudSim
    execution_mismatch = data[data["completed_jobs"] != data["admitted_jobs"]]
    if len(execution_mismatch) > 0:
        raise SystemExit(f"Validation Error: Found {len(execution_mismatch)} runs where completed_jobs != admitted_jobs!")

    # Check each (region, algorithm) pair has exactly 20 runs
    for (r, a), grp in data.groupby(["region", "algorithm"]):
        if len(grp) != 20:
            raise SystemExit(f"Validation Error: Region {r} Algorithm {a} has {len(grp)} runs (expected 20).")

    print(f"[CV Aggregate] Design verified: 5 folds, 2 regions, 7 algorithms, 60 submitted jobs/run, 280 runs total.")

    baseline = data[data.algorithm == "FCFS"][["region", "seed", "co2e_per_completed_job_g"]].rename(
        columns={"co2e_per_completed_job_g": "fcfs_co2e_per_job_g"})
    data = data.merge(baseline, on=["region", "seed"], how="left", validate="many_to_one")
    data["carbon_saving_pct"] = np.where(
        data.fcfs_co2e_per_job_g > 0,
        100.0 * (data.fcfs_co2e_per_job_g - data.co2e_per_completed_job_g) / data.fcfs_co2e_per_job_g,
        np.nan,
    )
    # Execution completion rate across admitted jobs
    data["completion_pct"] = 100.0 * data.completed_jobs / data.admitted_jobs.replace(0, np.nan)
    # Workload admission rate out of 60 submitted jobs
    data["admission_rate_pct"] = 100.0 * data.admitted_jobs / data.submitted_jobs.replace(0, np.nan)

    # Keep a full row-level file so every paired result can be audited.
    data.to_csv(root / "paired_runs_with_savings.csv", index=False)

    group_cols = ["region", "algorithm"]
    rows = []
    for keys, group in data.groupby(group_cols, dropna=False):
        region, algorithm = keys
        saving, saving_lo, saving_hi = mean_ci(group.carbon_saving_pct)
        material, material_lo, material_hi = mean_ci(group.deadline_violations_over_60s)
        any_late, any_late_lo, any_late_hi = mean_ci(group.deadline_violations)
        wait, wait_lo, wait_hi = mean_ci(group.mean_waiting_s / 60.0)
        completion, completion_lo, completion_hi = mean_ci(group.completion_pct)
        admission, admission_lo, admission_hi = mean_ci(group.admission_rate_pct)
        rows.append({
            "region": region, "algorithm": algorithm, "n_runs": len(group),
            "carbon_saving_pct_mean": saving, "carbon_saving_pct_ci95_low": saving_lo,
            "carbon_saving_pct_ci95_high": saving_hi,
            "material_deadline_misses_gt60s_mean_per_run": material,
            "material_misses_ci95_low": material_lo, "material_misses_ci95_high": material_hi,
            "any_deadline_violations_mean_per_run": any_late,
            "any_violations_ci95_low": any_late_lo, "any_violations_ci95_high": any_late_hi,
            "completion_pct_mean": completion, "completion_pct_ci95_low": completion_lo,
            "completion_pct_ci95_high": completion_hi,
            "admission_rate_pct_mean": admission,
            "mean_wait_minutes": wait, "wait_ci95_low_minutes": wait_lo,
            "wait_ci95_high_minutes": wait_hi,
            "mean_co2e_g_per_completed_job": group.co2e_per_completed_job_g.mean(),
            "mean_scheduler_runtime_ms": group.scheduler_runtime_ms.mean(),
            "mean_submitted_jobs": group.submitted_jobs.mean(),
            "mean_admitted_jobs": group.admitted_jobs.mean(),
            "mean_rejected_jobs": group.rejected_jobs.mean(),
            "mean_completed_jobs": group.completed_jobs.mean(),
        })
    summary = pd.DataFrame(rows).sort_values(["region", "algorithm"])
    summary.to_csv(root / "cv_summary.csv", index=False)

    fold_rows = []
    for (fold, region, algorithm), group in data.groupby(["fold", "region", "algorithm"]):
        fold_rows.append({
            "fold": fold, "region": region, "algorithm": algorithm,
            "n_runs": len(group),
            "carbon_saving_pct_mean": group.carbon_saving_pct.mean(),
            "material_deadline_misses_gt60s_mean_per_run": group.deadline_violations_over_60s.mean(),
            "any_deadline_violations_mean_per_run": group.deadline_violations.mean(),
            "completion_pct_mean": group.completion_pct.mean(),
            "admission_rate_pct_mean": group.admission_rate_pct.mean(),
            "mean_wait_minutes": group.mean_waiting_s.mean() / 60.0,
            "mean_submitted_jobs": group.submitted_jobs.mean(),
            "mean_admitted_jobs": group.admitted_jobs.mean(),
            "mean_rejected_jobs": group.rejected_jobs.mean(),
        })
    pd.DataFrame(fold_rows).sort_values(["fold", "region", "algorithm"]).to_csv(
        root / "fold_summary.csv", index=False)

    print(f"Wrote {root / 'cv_summary.csv'}")
    print(f"Wrote {root / 'fold_summary.csv'}")
    print("Policy selection rule: first enforce a predeclared deadline-miss/completion constraint; then compare carbon savings.")
    print("Caution: these are 5 blocked folds from one short trace, not independent seasonal replications.")


if __name__ == "__main__":
    main()
