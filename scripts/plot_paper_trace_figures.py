"""Generate trace-only paper figures from the repository carbon CSVs."""
from pathlib import Path
import pandas as pd
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[1]
FIG = ROOT / "figures"
DATA = ROOT / "data"
FIG.mkdir(exist_ok=True)

gb = pd.read_csv(DATA / "uk" / "uk_carbon_hourly_clean.csv")
gb["timestamp"] = pd.to_datetime(gb["timestamp"], utc=True)
gb = gb.set_index("timestamp").rename(columns={"carbon_intensity": "gb_ci"})

india_utc = pd.read_csv(DATA / "india" / "india_carbon_hourly_utc_aligned.csv")
india_utc["timestamp"] = pd.to_datetime(india_utc["timestamp"], utc=True)
india_utc = india_utc.set_index("timestamp").rename(columns={"carbon_intensity": "india_ci"})
common = gb[["gb_ci"]].join(india_utc[["india_ci"]], how="inner")

# Figure 1: daily means on identical UTC dates.
daily = common.resample("D").mean()
fig, ax = plt.subplots(figsize=(7.0, 3.3))
ax.plot(daily.index, daily["gb_ci"], marker="o", markersize=2.5, label="Great Britain")
ax.plot(daily.index, daily["india_ci"], marker="o", markersize=2.5, label="India")
ax.set_ylabel("Mean carbon intensity (gCO$_2$e/kWh)")
ax.set_xlabel("Date (UTC)")
ax.set_title("Daily Mean Carbon Intensity on the Common UTC Window")
ax.grid(True, alpha=0.25)
ax.legend(frameon=False, ncol=2)
fig.tight_layout()
fig.savefig(FIG / "paper_daily_mean_ci_utc.pdf", bbox_inches="tight")
fig.savefig(FIG / "paper_daily_mean_ci_utc.png", dpi=300, bbox_inches="tight")
plt.close(fig)

# Figure 2: source-local clock profiles.
# Use the same aligned traces as the scheduling experiment.
# GB local clock is UTC; India local clock is IST (UTC+05:30).

india_local = india_utc.copy()
india_local["local_hour"] = (
    india_local.index.tz_convert("Asia/Kolkata").hour
)

gb_local = gb.copy()
gb_local["local_hour"] = gb_local.index.hour

fig, ax = plt.subplots(figsize=(6.8, 3.3))

ax.plot(
    range(24),
    gb_local.groupby("local_hour")["gb_ci"]
    .mean().reindex(range(24)),
    marker="o",
    markersize=3,
    label="GB clock (UTC)"
)

ax.plot(
    range(24),
    india_local.groupby("local_hour")["india_ci"]
    .mean().reindex(range(24)),
    marker="o",
    markersize=3,
    label="India clock (IST)"
)

ax.set_xticks(range(0, 24, 2))
ax.set_xlabel("Hour of local clock")
ax.set_ylabel("Mean carbon intensity (gCO$_2$e/kWh)")
ax.set_title("Mean Diurnal Carbon-Intensity Profiles")
ax.grid(True, alpha=0.25)
ax.legend(frameon=False)

fig.tight_layout()
fig.savefig(FIG / "paper_diurnal_local_clock.pdf", bbox_inches="tight")
fig.savefig(
    FIG / "paper_diurnal_local_clock.png",
    dpi=300,
    bbox_inches="tight"
)
plt.close(fig)

# Figure 3: distribution over the shared UTC window.
fig, ax = plt.subplots(figsize=(5.8, 3.3))
ax.boxplot([common["gb_ci"].dropna(), common["india_ci"].dropna()],
           labels=["Great Britain", "India"], showmeans=True)
ax.set_ylabel("Carbon intensity (gCO$_2$e/kWh)")
ax.set_title("Hourly Carbon-Intensity Distribution (Common UTC Window)")
ax.grid(True, axis="y", alpha=0.25)
fig.tight_layout()
fig.savefig(FIG / "paper_ci_distribution_utc.pdf", bbox_inches="tight")
fig.savefig(FIG / "paper_ci_distribution_utc.png", dpi=300, bbox_inches="tight")
plt.close(fig)

print(f"Saved trace figures under {FIG}")
