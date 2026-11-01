"""
EcoCloud - Forecast-Guided Bahreini SRR-APX Plotting Script

Generates comparison figures from results/forecast_guided_summary.csv:
  1. figures/forecast_guided_savings_comparison.png
  2. figures/forecast_guided_emissions_violations.png
"""

from pathlib import Path
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

SUMMARY_FILE = Path("results/forecast_guided_summary.csv")
OUT_DIR = Path("figures")
OUT_DIR.mkdir(parents=True, exist_ok=True)

if not SUMMARY_FILE.exists():
    print(f"Error: {SUMMARY_FILE} not found. Run simulation first.")
    exit(1)

df = pd.read_csv(SUMMARY_FILE)

# Sort order
order = [
    "FCFS",
    "Wiesner_2021_AdHoc",
    "Bahreini_Original_Baseline",
    "Bahreini_Theta_0.0_Verification",
    "Forecast_Guided_Theta_0.5",
    "Forecast_Guided_Theta_1.0",
    "Oracle_Theta_1.0_Diagnostic_Upper_Bound"
]

labels = {
    "FCFS": "FCFS",
    "Wiesner_2021_AdHoc": "Wiesner (2021)",
    "Bahreini_Original_Baseline": "Bahreini Base",
    "Bahreini_Theta_0.0_Verification": "Bahreini (θ=0)",
    "Forecast_Guided_Theta_0.5": "Forecast (θ=0.5)",
    "Forecast_Guided_Theta_1.0": "Forecast (θ=1.0)",
    "Oracle_Theta_1.0_Diagnostic_Upper_Bound": "Oracle (Bound)"
}

colors = ["#7f7f7f", "#2ca02c", "#1f77b4", "#17becf", "#ff7f0e", "#d62728", "#9467bd"]

present_algs = [a for a in order if a in df["algorithm"].values]
sub = df.set_index("algorithm").reindex(present_algs).reset_index()

x = np.arange(len(present_algs))
width = 0.55

# 1. Carbon Savings vs FCFS (%)
fig, ax = plt.subplots(figsize=(10, 5))
bars = ax.bar(
    x,
    sub["mean_carbon_saving_pct"],
    yerr=sub["std_carbon_saving_pct"],
    width=width,
    color=[colors[order.index(a)] for a in present_algs],
    capsize=5,
    alpha=0.9,
    edgecolor="black"
)

ax.set_ylabel("Carbon Savings vs FCFS (%)", fontsize=12)
ax.set_title("EcoCloud: Carbon Savings under India Grid Trace (Causal 24h Horizon)", fontsize=13, fontweight="bold")
ax.set_xticks(x)
ax.set_xticklabels([labels.get(a, a) for a in present_algs], rotation=20, ha="right", fontsize=10)
ax.grid(axis="y", linestyle="--", alpha=0.5)

# Value labels on top of bars
for bar in bars:
    yval = bar.get_height()
    ax.text(
        bar.get_x() + bar.get_width() / 2.0,
        yval + 0.3,
        f"{yval:.2f}%",
        ha="center",
        va="bottom",
        fontsize=9,
        fontweight="bold"
    )

plt.tight_layout()
fig.savefig(OUT_DIR / "forecast_guided_savings_comparison.png", dpi=300)
plt.close(fig)

# 2. Emissions (gCO2e) and Deadline Violations Dual-Axis Chart
fig, ax1 = plt.subplots(figsize=(10, 5))
ax2 = ax1.twinx()

bars_co2 = ax1.bar(
    x - width/4,
    sub["mean_co2e_g"],
    yerr=sub["std_co2e_g"],
    width=width/2,
    color="#1f77b4",
    capsize=4,
    alpha=0.85,
    label="Emissions (gCO2e)"
)

bars_viol = ax2.bar(
    x + width/4,
    sub["mean_violations"],
    yerr=sub["std_violations"],
    width=width/2,
    color="#d62728",
    capsize=4,
    alpha=0.85,
    label="Deadline Violations"
)

ax1.set_ylabel("Total Emissions (gCO2e)", color="#1f77b4", fontsize=12)
ax2.set_ylabel("Deadline Violations (Count)", color="#d62728", fontsize=12)
ax1.set_xticks(x)
ax1.set_xticklabels([labels.get(a, a) for a in present_algs], rotation=20, ha="right", fontsize=10)
ax1.set_title("EcoCloud: Emissions & Service Trade-Off Across Schedulers", fontsize=13, fontweight="bold")
ax1.grid(axis="y", linestyle="--", alpha=0.3)

plt.tight_layout()
fig.savefig(OUT_DIR / "forecast_guided_emissions_violations.png", dpi=300)
plt.close(fig)

print("Generated forecast-guided comparison plots in figures/")
