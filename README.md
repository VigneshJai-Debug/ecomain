# EcoCloud: Cross-Region Carbon-Aware Cloud Workload Scheduling

EcoCloud is an empirical cloud scheduling simulation framework built on CloudSim Plus to evaluate the cross-region transferability of carbon-aware scheduling policies between national electricity grids—specifically comparing Great Britain (GB, National Energy System Operator / NESO) and India (Central Electricity Authority / CEA).

---

## 1. Project Purpose

Carbon-aware cloud scheduling shifts flexible compute workloads to hours of lower electricity grid carbon intensity. Most published benchmarks evaluate policies on a single Western European or North American grid with high wind/nuclear penetration. EcoCloud investigates whether reported gains and service-quality trade-offs transfer to coal-heavy, lower-variability grids such as India under controlled, matched workload and deadline constraints.

### Simulation Validation vs. Scientific Validation
- **Simulation and Implementation Validation (Complete)**: Code paths, mathematical formulas, timestamp parsing, LP formulations, randomized rounding, and job constraints have been audited and verified via automated test suites (including 20/20 audit invariant tests and deterministic preprocessing tests).
- **Scientific Validation (Ongoing / Provisional)**: The paper's empirical conclusions are evaluated on limited contiguous trace periods (29 days) and simulated CloudSim environments. They do not constitute proof of real-world production performance, long-term seasonal generalization, or multi-year transferability across broader geographical regions.

---

## 2. Prerequisites

- **Java JDK**: Version 21 or higher (`javac`, `java` available on PATH).
- **Python**: Version 3.10 or higher (`python` on PATH).
- **Python Packages**:
  ```bash
  pip install -r requirements.txt
  ```
  *(Packages: pandas, numpy, scipy, matplotlib, requests)*
- **PowerShell**: Windows PowerShell 5.1+ or PowerShell Core 7+.
- **Dependencies (JARs)**: Pre-bundled in `lib/`:
  - `cloudsimplus-8.5.7.jar`
  - `commons-math3-3.6.1.jar`
  - `commons-lang3-3.17.0.jar`
  - `commons-collections4-4.4.jar`
  - `gson-2.12.1.jar`
  - `slf4j-api-2.0.16.jar`
  - `slf4j-simple-2.0.16.jar`

---

## 3. Data Preprocessing & Timezone Alignment

The raw generation data from India's Central Electricity Authority (CEA) contains naive local timestamps representing Indian Standard Time (IST, UTC+05:30). Solar generation physical peak occurs at 11:00–13:00 IST (local midday).

To ensure zero timezone misalignment across grids, the preprocessing pipeline:
1. Interpolates 15 missing hours in the 696-hour trace on the continuous IST operational cycle.
2. Localizes naive timestamps to `Asia/Kolkata` (IST) and converts them to UTC instants.
3. Resamples/interpolates across the half-hour (+05:30) offset onto exact hourly UTC boundaries (`2026-08-19T00:00:00Z` to `2026-09-16T17:00:00Z`, 690 records).
4. Cleans and aligns the UK NESO half-hourly trace to 1-hour UTC averages.

### Run Preprocessing:
```powershell
.\ecocloud.ps1 preprocess
# or directly:
python scripts/process_india_data.py
python scripts/process_uk_data.py
python scripts/test_india_preprocessing.py
```

---

## 4. Build and Test Commands

Clean and compile all Java classes:
```powershell
.\ecocloud.ps1 clean
.\ecocloud.ps1 build
```

Run the automated verification and audit test suites:
```powershell
.\ecocloud.ps1 test
```
*Executes:*
- `com.ecocloud.AuditValidationTest`: 20 implementation-level invariant tests (energy formulas, boundary integration, 60-job determinism, timestamp mapping, deadline constraints).
- `com.ecocloud.TestCarbonTrace`: Verifies trace loading, UTC record indexing, and common-window coverage.
- `com.ecocloud.TestCarbonScheduler`: Verifies candidate slot selection and feasibility constraints.

---

## 5. Canonical Simulation Pipeline

The canonical experiment workflow is orchestrated via `ecocloud.ps1` (or `ecocloud.cmd`):

### A. Run Entire Canonical Pipeline
Executes preprocessing, clean build, tests, numerical audits, forecast-guided experiment, matched 60-job comparison, 5-fold temporal cross-validation, and plot generation:
```powershell
.\ecocloud.ps1 all
```

### B. Individual Canonical Steps
- **Data Preprocessing & Alignment**:
  ```powershell
  .\ecocloud.ps1 preprocess
  ```
- **Numerical LP Rounding Audit**:
  ```powershell
  .\ecocloud.ps1 audit
  ```
  Generates detailed Simplex LP fractionality logs in `audit_output/` (`lp_candidate_variables.csv`, `lp_seed_summary.csv`, `workload_lp_audit.md`).
- **Forecast-Guided Experiment (Table 3)**:
  ```powershell
  .\ecocloud.ps1 sim
  ```
  Runs `com.ecocloud.EcoCloudForecastGuidedExperiment` across 3 seeds on India trace at Hour 432 origin (2026-09-06T00:00:00Z / 05:30 IST).
- **Matched 60-Job Multi-Seed Comparison**:
  ```powershell
  .\ecocloud.ps1 matched
  ```
  Runs `com.ecocloud.EcoCloudReview3MultiSeed` on GB and India for 3 seeds under oracle information mode.
- **Time-Aware Five-Fold Cross-Validation (Tables 1 & 2)**:
  ```powershell
  .\ecocloud.ps1 cv
  ```
  Executes `scripts/time_aware_cv.ps1` across 5 temporal folds, 4 seeds per fold, 2 regions (GB and India), and 7 algorithms (280 runs total), aggregated by `scripts/time_aware_cv_aggregate.py`.
- **Plot Generation**:
  ```powershell
  .\ecocloud.ps1 plot
  ```
  Regenerates paper trace figures and forecast comparison figures in `figures/`.

---

## 6. Workload & Policy Specifications

### Canonical 60-Job Workload Design
- **Workload Size**: 60 non-preemptive batch jobs per simulation run.
- **Job CPU**: Uniform discrete distribution $[1, 10]$ PEs.
- **Runtime**: Uniform discrete distribution $[1, 10]$ slots (0.5 to 5.0 hours).
- **Release Time**: Uniform discrete distribution $[0, 7]$ slots (0.0 to 3.5 hours into day).
- **Slack & Deadline**: Extra slack $2 + [0, 12]$ slots; deadline $\min(\text{release} + \text{runtime} + \text{slack}, 47)$ slots.
- **Infrastructure**: 2 logical sites $\times$ 50 PEs (100 PEs total capacity, 1000 MIPS/PE, 150 W active power proxy).
- **Contention**: ~60% average compute load factor over the 24-hour horizon.

### Evaluated Scheduling Policies
1. **FCFS (Baseline)**: Immediate first-come first-served execution without carbon awareness.
2. **Wiesner 2021 Ad-Hoc**: Evaluates lowest duration-weighted mean carbon intensity up to 6 hours deferral cap.
3. **Bahreini SRR-APX**: Linear Programming relaxation maximizing Green Resource Utilization (GRU) with Monte Carlo randomized rounding and greedy capacity enforcement.
4. **Deadline_Carbon_Shift**: Searches lowest mean carbon window in $[\text{arrival}, \text{deadline} - \text{runtime}]$ bounded by each job's explicit deadline.
5. **CADS ($\theta \in \{0.00, 0.05, 0.10\}$)**: Capacity-Aware Deadline-Sensitive scheduler with reservation ledger and parametric threshold $\theta$.

---

## 7. Paper Results & Output Mapping

| Manuscript Artifact | Description | Generating Runner / Script | Authoritative Output File |
| :--- | :--- | :--- | :--- |
| **Table 1** | Cross-Region Bahreini SRR-APX Results (5 Folds, 20 Runs/Region) | `scripts/time_aware_cv.ps1` + `scripts/time_aware_cv_aggregate.py` | `results/time_aware_cv/cv_summary.csv` (rows: `GB, Bahreini_SRR_APX` & `INDIA, Bahreini_SRR_APX`) |
| **Table 2** | India Policy Comparison (Time-Aware Evaluation, 20 Runs/Policy) | `scripts/time_aware_cv.ps1` + `scripts/time_aware_cv_aggregate.py` | `results/time_aware_cv/cv_summary.csv` (rows: `INDIA, *`) |
| **Table 3** | Forecast-Guided Experiment Results (Separate 3-Seed Setup) | `com.ecocloud.EcoCloudForecastGuidedExperiment` (`.\ecocloud.ps1 sim`) | `results/forecast_guided_summary.csv` |
| **Figure 1** | Daily Mean Carbon Intensity on Common UTC Window | `scripts/plot_paper_trace_figures.py` | `figures/paper_daily_mean_ci_utc.pdf` / `.png` |
| **Figure 2** | Mean Diurnal Carbon-Intensity Profiles (Local Clocks) | `scripts/plot_paper_trace_figures.py` | `figures/paper_diurnal_local_clock.pdf` / `.png` |
| **Figure 3** | Hourly Carbon-Intensity Distribution Boxplot | `scripts/plot_paper_trace_figures.py` | `figures/paper_ci_distribution_utc.pdf` / `.png` |
| **Figure 4** | Forecast-Guided Carbon Savings Comparison | `scripts/plot_forecast_guided_comparison.py` | `figures/forecast_guided_savings_comparison.png` |
| **Figure 5** | Emissions & Service Trade-Off Across Schedulers | `scripts/plot_forecast_guided_comparison.py` | `figures/forecast_guided_emissions_violations.png` |

---

## 8. Directory Structure

```text
ecomain/
├── .gitignore
├── pom.xml
├── README.md                                 # Canonical project guide (this document)
├── requirements.txt                          # Python dependencies
├── ecocloud.cmd                              # Windows launcher
├── ecocloud.ps1                              # PowerShell root launcher
├── main.tex                                  # LaTeX research paper manuscript
├── refs.bib                                  # Bibliography
├── data/
│   ├── uk/
│   │   ├── uk_carbon_hourly.csv              # Raw UK NESO data
│   │   └── uk_carbon_hourly_clean.csv        # Cleaned UTC hourly UK trace
│   └── india/
│       ├── india_carbon_hourly_..._FINAL_CEA_v22.csv # Raw Indian CEA generation data (IST)
│       ├── india_carbon_hourly_..._gapfilled_linear.csv # Gap-filled continuous IST trace
│       └── india_carbon_hourly_utc_aligned.csv # Canonical UTC-aligned Indian trace (690 h)
├── src/main/java/com/ecocloud/
│   ├── AuditValidationTest.java              # 20-point verification test suite
│   ├── EcoCloudForecastGuidedExperiment.java # Forecast-guided experiment runner (Table 3)
│   ├── EcoCloudReview3MultiSeed.java         # Canonical matched & CV runner (Tables 1 & 2)
│   ├── TestCarbonScheduler.java              # Unit verification for CarbonAwareScheduler
│   ├── TestCarbonTrace.java                  # Unit verification for CarbonTrace
│   ├── audit/
│   │   └── EcoCloudLpRoundingAudit.java      # LP relaxation & rounding audit tool
│   ├── carbon/
│   │   ├── CarbonTrace.java                  # Trace parser and simulation indexer
│   │   ├── DiurnalForecastTrace.java         # Rolling-origin forecast trace
│   │   ├── SeasonalNaiveForecaster.java      # Seasonal-naive forecaster
│   │   └── ShiftedCarbonTrace.java           # Temporal fold trace shifter
│   └── scheduler/
│       ├── BahreiniSrrApxScheduler.java      # Shared Bahreini SRR-APX LP scheduler
│       ├── CapacityAwareDiurnalScheduler.java# CADS scheduler
│       ├── CarbonAwareScheduler.java         # Point-in-time scheduler
│       ├── WiesnerAdHocScheduler.java        # Wiesner 2021 temporal shifter
│       └── WiesnerTemporalScheduler.java     # Hourly variant
├── scripts/
│   ├── ecocloud.ps1                          # Canonical master execution script
│   ├── process_india_data.py                 # Deterministic IST->UTC India preprocessing
│   ├── process_uk_data.py                    # UK NESO trace cleaner
│   ├── test_india_preprocessing.py           # 8-point automated test suite for preprocessing
│   ├── time_aware_cv.ps1                     # 5-fold blocked time-aware CV runner
│   ├── time_aware_cv_aggregate.py            # CV result aggregator with strict design validation
│   ├── plot_paper_trace_figures.py           # Trace figure generator (Figures 1-3)
│   ├── plot_forecast_guided_comparison.py    # Forecast comparison plotter (Figures 4-5)
│   ├── download_dependencies.py              # Maven dependency fetcher
│   └── download_uk_data.py                   # UK API fetcher
├── audit_output/                             # Output from EcoCloudLpRoundingAudit
└── results/
    ├── forecast_guided_raw_results.csv
    ├── forecast_guided_summary.csv           # Supports Table 3
    ├── matched_60job/                        # Matched 60-job multi-seed comparison
    └── time_aware_cv/                        # 5-fold temporal CV (Supports Tables 1 & 2)
        ├── cv_summary.csv                    # Authoritative cross-region & policy summary
        ├── fold_summary.csv                  # Per-fold breakdown
        ├── paired_runs_with_savings.csv      # Full paired run data (280 rows)
        └── fold_01 .. fold_05/
```
