"""
EcoCloud - India Carbon Intensity Preprocessing Pipeline
=========================================================

Source Provenance & Physical Semantics:
---------------------------------------
1. Data Origin:
   - Sourced from Central Electricity Authority (CEA) of India generation mix data.
   - Hourly dispatch reported by generation source: thermal, gas, hydro, nuclear, solar, wind.
   - Grid carbon intensity is calculated in gCO2e/kWh using fuel-specific emission factors.

2. Source Timezone & Timestamp Semantics:
   - Naive timestamps (e.g., '2026-08-19 00:00:00') in CEA reporting represent
     Indian Standard Time (IST, UTC+05:30) wall-clock hours.
   - Physical Evidence: Solar generation in the raw data peaks between 11:00 and 13:00 IST
     (local solar midday). If interpreted as UTC, 12:00 UTC would correspond to 17:30 IST
     (late evening/sunset in India), which is physically impossible for peak solar production.

3. Gap-Filling (Imputation) Policy:
   - The raw 29-day record (Aug 19, 2026 to Sep 16, 2026) contains 681 hourly records
     out of 696 potential hours (15 missing hours).
   - Linear interpolation on the continuous IST operational timeline is performed
     BEFORE timezone conversion to maintain continuity of physical daily grid cycles.
   - The gap-filled dataset is saved to:
     data/india/india_carbon_hourly_2026-08-19_to_2026-09-16_FINAL_CEA_v22_gapfilled_linear.csv

4. IST to UTC Alignment Policy:
   - Timestamps are localized to 'Asia/Kolkata' (IST, UTC+05:30) and converted to UTC instants.
   - Because IST is offset by +05:30, on-the-hour UTC boundaries (e.g., 00:00:00Z) correspond
     to half-hour marks in IST (e.g., 05:30:00 IST).
   - Continuous linear interpolation between adjacent hourly observations
     (e.g., 05:00:00 IST and 06:00:00 IST) yields the exact instantaneous intensity at each
     UTC-hour boundary.
   - Resampled UTC range: 2026-08-19T00:00:00Z to 2026-09-16T17:00:00Z (690 records).
   - Saved to:
     data/india/india_carbon_hourly_utc_aligned.csv
"""

from pathlib import Path
import pandas as pd
import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = REPO_ROOT / "data" / "india"

RAW_INPUT_FILE = DATA_DIR / "india_carbon_hourly_2026-08-19_to_2026-09-16_FINAL_CEA_v22.csv"
GAPFILLED_OUTPUT_FILE = DATA_DIR / "india_carbon_hourly_2026-08-19_to_2026-09-16_FINAL_CEA_v22_gapfilled_linear.csv"
UTC_ALIGNED_OUTPUT_FILE = DATA_DIR / "india_carbon_hourly_utc_aligned.csv"

START_IST = "2026-08-19 00:00:00"
END_IST = "2026-09-16 23:00:00"
START_UTC = "2026-08-19T00:00:00Z"
END_UTC = "2026-09-16T17:00:00Z"


def process_india_data(raw_file: Path = RAW_INPUT_FILE,
                       gapfilled_out: Path = GAPFILLED_OUTPUT_FILE,
                       utc_aligned_out: Path = UTC_ALIGNED_OUTPUT_FILE) -> tuple[pd.DataFrame, pd.DataFrame]:
    print("\n============================================================")
    print("ECOCLOUD - INDIA CEA CARBON TRACE PREPROCESSING")
    print("============================================================")

    if not raw_file.exists():
        raise FileNotFoundError(f"Raw input file not found: {raw_file}")

    # 1. Load Raw CEA Data
    raw_df = pd.read_csv(raw_file)
    print(f"Loaded raw CEA records: {len(raw_df)} rows")
    if "timestamp" not in raw_df.columns or "carbon_intensity_gco2_kwh" not in raw_df.columns:
        raise ValueError(f"Required columns missing in {raw_file}")

    # Verify physical sanity of solar peak in IST
    if "solar_mw" in raw_df.columns:
        temp_dt = pd.to_datetime(raw_df["timestamp"])
        solar_by_hour = raw_df.groupby(temp_dt.dt.hour)["solar_mw"].mean()
        peak_hour_ist = solar_by_hour.idxmax()
        print(f"Physical sanity check: Mean solar generation peaks at hour {peak_hour_ist}:00 in source timestamps.")
        if not (11 <= peak_hour_ist <= 13):
            raise ValueError(f"Unexpected solar peak hour: {peak_hour_ist} (expected 11-13 IST). Check timestamp semantics!")
        print("  -> Confirmed: Source timestamps represent Indian Standard Time (IST, UTC+05:30).")

    # 2. Linear Gap Filling on Complete IST Hourly Grid
    raw_df["timestamp_dt"] = pd.to_datetime(raw_df["timestamp"])
    raw_df = raw_df.sort_values("timestamp_dt").drop_duplicates(subset=["timestamp_dt"])

    full_ist_range = pd.date_range(start=START_IST, end=END_IST, freq="1h")
    indexed_df = raw_df.set_index("timestamp_dt").reindex(full_ist_range)

    # Flag imputed rows
    is_imputed = indexed_df["carbon_intensity_gco2_kwh"].isna().astype(int)
    gapfilled_series = indexed_df["carbon_intensity_gco2_kwh"].interpolate(method="linear")

    gapfilled_df = pd.DataFrame({
        "timestamp": full_ist_range.strftime("%Y-%m-%d %H:%M:%S"),
        "carbon_intensity_gco2_kwh": gapfilled_series.round(6),
        "imputed": is_imputed.values
    })

    print(f"Gap filling completed: {len(gapfilled_df)} total IST hours ({is_imputed.sum()} missing hours interpolated).")

    # Write gapfilled CSV
    gapfilled_out.parent.mkdir(parents=True, exist_ok=True)
    gapfilled_df.to_csv(gapfilled_out, index=False)
    print(f"Saved gap-filled IST trace: {gapfilled_out}")

    # 3. Timezone Localization and UTC Alignment
    # Localize naive IST timestamps to Asia/Kolkata
    ist_localized = full_ist_range.tz_localize("Asia/Kolkata")
    # Convert to UTC
    utc_converted = ist_localized.tz_convert("UTC")

    utc_indexed_series = pd.Series(gapfilled_series.values, index=utc_converted)

    # Construct target UTC hourly grid
    target_utc_grid = pd.date_range(start=START_UTC, end=END_UTC, freq="1h")

    # Combine indices and linearly interpolate at UTC hour boundaries
    combined_index = utc_indexed_series.index.union(target_utc_grid).sort_values()
    reindexed_series = utc_indexed_series.reindex(combined_index)
    interpolated_utc = reindexed_series.interpolate(method="time")

    aligned_series = interpolated_utc.loc[target_utc_grid]

    utc_aligned_df = pd.DataFrame({
        "timestamp": target_utc_grid.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "carbon_intensity": aligned_series.values
    })

    print(f"UTC alignment completed: {len(utc_aligned_df)} hourly records from {START_UTC} to {END_UTC}.")

    utc_aligned_out.parent.mkdir(parents=True, exist_ok=True)
    utc_aligned_df.to_csv(utc_aligned_out, index=False)
    print(f"Saved canonical UTC-aligned trace: {utc_aligned_out}")
    print("============================================================\n")

    return gapfilled_df, utc_aligned_df


if __name__ == "__main__":
    process_india_data()
