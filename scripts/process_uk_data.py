import pandas as pd
from pathlib import Path

# ============================================================
# ECOCLOUD - UK CARBON INTENSITY PROCESSOR
# ============================================================

INPUT_FILE = "data/uk/uk_carbon_hourly.csv"
OUTPUT_FILE = "data/uk/uk_carbon_hourly_clean.csv"

# August to September 2026 experiment period
START_DATE = "2026-08-19 00:00:00+00:00"
END_DATE = "2026-09-17 00:00:00+00:00"


def main():

    print("\n================================================")
    print("ECOCLOUD - UK CARBON DATA PROCESSOR")
    print("================================================")

    if not Path(INPUT_FILE).exists():
        raise FileNotFoundError(
            f"\nERROR: Could not find {INPUT_FILE}\n"
            "Make sure the raw UK CSV is inside data/uk/\n"
        )

    # --------------------------------------------------------
    # READ RAW DATA
    # --------------------------------------------------------

    df = pd.read_csv(INPUT_FILE)

    print(f"\nRaw records loaded: {len(df)}")

    required = ["timestamp", "carbon_intensity"]

    missing = [c for c in required if c not in df.columns]

    if missing:
        raise ValueError(
            f"Missing columns: {missing}\n"
            f"Found columns: {list(df.columns)}"
        )

    # --------------------------------------------------------
    # CLEAN TIMESTAMPS
    # --------------------------------------------------------

    df["timestamp"] = pd.to_datetime(
        df["timestamp"],
        utc=True,
        errors="coerce"
    )

    df = df.dropna(subset=["timestamp"])

    # --------------------------------------------------------
    # CLEAN CARBON VALUES
    # --------------------------------------------------------

    df["carbon_intensity"] = pd.to_numeric(
        df["carbon_intensity"],
        errors="coerce"
    )

    df = df.dropna(subset=["carbon_intensity"])

    # --------------------------------------------------------
    # REMOVE DUPLICATE TIMESTAMPS
    # --------------------------------------------------------

    before = len(df)

    df = df.drop_duplicates(
        subset=["timestamp"],
        keep="first"
    )

    print(f"Duplicate rows removed: {before - len(df)}")

    # --------------------------------------------------------
    # SORT
    # --------------------------------------------------------

    df = df.sort_values("timestamp")
    df = df.set_index("timestamp")

    # --------------------------------------------------------
    # 30-MINUTE -> HOURLY
    # --------------------------------------------------------

    hourly = df["carbon_intensity"].resample("1h").mean()

    result = hourly.reset_index()

    result.columns = [
        "timestamp",
        "carbon_intensity"
    ]

    # --------------------------------------------------------
    # FILTER AUGUST 2026
    # --------------------------------------------------------

    result = result[
        (result["timestamp"] >= START_DATE) &
        (result["timestamp"] < END_DATE)
    ].copy()

    # --------------------------------------------------------
    # REMOVE MISSING HOURS
    # --------------------------------------------------------

    result = result.dropna(
        subset=["carbon_intensity"]
    )

    result = result.sort_values(
        "timestamp"
    ).reset_index(drop=True)

    # --------------------------------------------------------
    # SAVE FINAL DATASET
    # --------------------------------------------------------

    Path(OUTPUT_FILE).parent.mkdir(
        parents=True,
        exist_ok=True
    )

    result.to_csv(
        OUTPUT_FILE,
        index=False
    )

    # --------------------------------------------------------
    # STATISTICS
    # --------------------------------------------------------

    print("\n================================================")
    print("UK CARBON INTENSITY STATISTICS")
    print("================================================")

    print(f"\nPeriod       : {START_DATE} -> {END_DATE}")
    print(f"Hourly rows  : {len(result)}")

    if len(result) > 0:

        print(
            f"Mean         : "
            f"{result['carbon_intensity'].mean():.2f} gCO2e/kWh"
        )

        print(
            f"Minimum      : "
            f"{result['carbon_intensity'].min():.2f} gCO2e/kWh"
        )

        print(
            f"Maximum      : "
            f"{result['carbon_intensity'].max():.2f} gCO2e/kWh"
        )

        print(
            f"Std deviation: "
            f"{result['carbon_intensity'].std():.2f} gCO2e/kWh"
        )

        lowest = result.loc[
            result["carbon_intensity"].idxmin()
        ]

        highest = result.loc[
            result["carbon_intensity"].idxmax()
        ]

        print("\nLowest-carbon hour:")
        print(
            f"{lowest['timestamp']} -> "
            f"{lowest['carbon_intensity']:.2f} gCO2e/kWh"
        )

        print("\nHighest-carbon hour:")
        print(
            f"{highest['timestamp']} -> "
            f"{highest['carbon_intensity']:.2f} gCO2e/kWh"
        )

        print("\nFinal dataset range:")
        print(f"First: {result['timestamp'].iloc[0]}")
        print(f"Last : {result['timestamp'].iloc[-1]}")

    else:
        print("\nERROR: No usable records remain.")

    print("\n================================================")
    print("UK DATASET READY")
    print("================================================")
    print(f"\nSaved to: {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
