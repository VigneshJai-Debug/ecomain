import requests
import csv
from datetime import datetime, timezone
from pathlib import Path

API_URL = "https://api.carbonintensity.org.uk/intensity"

# UK reference dataset: August 2026
START_DATE = datetime(2026, 8, 19, 0, 0, tzinfo=timezone.utc)
END_DATE = datetime(2026, 9, 16, 23, 59, tzinfo=timezone.utc)

OUTPUT_FILE = "data/uk/uk_carbon_hourly.csv"


def fetch_data(start, end):
    url = f"{API_URL}/{start.isoformat()}/{end.isoformat()}"

    print("Requesting:")
    print(url)

    response = requests.get(url, timeout=30)
    response.raise_for_status()

    return response.json()


def main():
    Path(OUTPUT_FILE).parent.mkdir(parents=True, exist_ok=True)

    data = fetch_data(START_DATE, END_DATE)
    records = data["data"]

    print(f"Records received: {len(records)}")

    rows = []

    for record in records:
        timestamp = record["from"]

        # Prefer actual carbon intensity.
        # Fall back to forecast only if actual is unavailable.
        intensity = record["intensity"]["actual"]

        if intensity is None:
            intensity = record["intensity"]["forecast"]

        if intensity is not None:
            rows.append({
                "timestamp": timestamp,
                "carbon_intensity": intensity
            })

    with open(OUTPUT_FILE, "w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(
            file,
            fieldnames=["timestamp", "carbon_intensity"]
        )

        writer.writeheader()
        writer.writerows(rows)

    print()
    print("UK dataset created successfully.")
    print(f"Output: {OUTPUT_FILE}")
    print(f"Rows: {len(rows)}")


if __name__ == "__main__":
    main()
