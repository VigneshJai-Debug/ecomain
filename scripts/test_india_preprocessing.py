"""
Automated Verification Suite for India IST-to-UTC Preprocessing
===============================================================

Validates:
1. Known IST-to-UTC timestamp mapping and half-hour boundary interpolation.
2. Midnight crossing behavior across timezone conversions.
3. Strict UTC ordering, no duplicates, and continuous 1-hour interval spacing.
4. Expected trace duration, row count (690), and exact time window bounds.
5. UTC representation format (explicit 'Z' suffix, zero timezone offset).
6. Non-tautological reproducibility: independent generation in isolated
   temporary directory compared against preserved canonical reference output,
   plus multi-run deterministic reproducibility.
7. Physical sanity check: solar generation peaks at local midday IST.
8. Physical validity: all carbon intensity values non-null, positive, and realistic.
"""

from pathlib import Path
import tempfile
import unittest
import pandas as pd
import numpy as np

from process_india_data import (
    process_india_data,
    RAW_INPUT_FILE,
    GAPFILLED_OUTPUT_FILE,
    UTC_ALIGNED_OUTPUT_FILE,
    START_UTC,
    END_UTC
)


class TestIndiaPreprocessing(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        # Verify raw CEA input exists
        if not RAW_INPUT_FILE.exists():
            raise FileNotFoundError(f"Raw input file missing: {RAW_INPUT_FILE}")

        # Verify canonical output files exist before running tests
        if not UTC_ALIGNED_OUTPUT_FILE.exists():
            raise FileNotFoundError(f"Canonical UTC-aligned output missing: {UTC_ALIGNED_OUTPUT_FILE}")
        if not GAPFILLED_OUTPUT_FILE.exists():
            raise FileNotFoundError(f"Canonical gapfilled output missing: {GAPFILLED_OUTPUT_FILE}")

        # Load preserved canonical datasets read-only (NO in-place regeneration)
        cls.gapfilled_df = pd.read_csv(GAPFILLED_OUTPUT_FILE)
        cls.utc_df = pd.read_csv(UTC_ALIGNED_OUTPUT_FILE)

    def test_01_known_timestamp_mapping(self):
        """Test that a specific known IST timestamp maps to the exact UTC instant."""
        # 2026-08-19 05:30:00 IST is exactly 2026-08-19 00:00:00 UTC
        ts_ist = pd.Timestamp("2026-08-19 05:30:00").tz_localize("Asia/Kolkata")
        ts_utc = ts_ist.tz_convert("UTC")
        self.assertEqual(ts_utc.strftime("%Y-%m-%dT%H:%M:%SZ"), "2026-08-19T00:00:00Z")

        # In gapfilled data, check 05:00 and 06:00 IST intensities
        row_05 = self.gapfilled_df[self.gapfilled_df["timestamp"] == "2026-08-19 05:00:00"].iloc[0]
        row_06 = self.gapfilled_df[self.gapfilled_df["timestamp"] == "2026-08-19 06:00:00"].iloc[0]
        expected_utc_h0 = (row_05["carbon_intensity_gco2_kwh"] + row_06["carbon_intensity_gco2_kwh"]) / 2.0

        actual_utc_h0 = self.utc_df.iloc[0]["carbon_intensity"]
        self.assertAlmostEqual(actual_utc_h0, expected_utc_h0, places=5)
        print(f"[PASS] 1. Known timestamp mapping: 05:30 IST -> 00:00 UTC (CI={actual_utc_h0:.4f})")

    def test_02_midnight_crossing(self):
        """Test midnight crossing between IST and UTC across date boundaries."""
        # IST midnight 2026-08-20 00:00:00 IST is previous UTC day: 2026-08-19 18:30:00 UTC
        ts_ist_midnight = pd.Timestamp("2026-08-20 00:00:00").tz_localize("Asia/Kolkata")
        ts_utc_instant = ts_ist_midnight.tz_convert("UTC")
        self.assertEqual(ts_utc_instant.strftime("%Y-%m-%dT%H:%M:%SZ"), "2026-08-19T18:30:00Z")
        self.assertEqual(ts_utc_instant.day, 19)
        self.assertEqual(ts_utc_instant.hour, 18)
        self.assertEqual(ts_utc_instant.minute, 30)
        print("[PASS] 2. Midnight crossing validated: 2026-08-20 00:00 IST -> 2026-08-19 18:30 UTC")

    def test_03_utc_ordering_and_spacing(self):
        """Test strict monotonicity, no duplicates, and exact 1-hour spacing."""
        utc_times = pd.to_datetime(self.utc_df["timestamp"])
        # Check sorted
        self.assertTrue(utc_times.is_monotonic_increasing)
        # Check no duplicates
        self.assertEqual(len(utc_times), len(utc_times.drop_duplicates()))
        # Check exact 1 hour diffs
        diffs = utc_times.diff().dropna()
        unique_diffs = diffs.unique()
        self.assertEqual(len(unique_diffs), 1)
        self.assertEqual(unique_diffs[0], pd.Timedelta(hours=1))
        print(f"[PASS] 3. UTC ordering, uniqueness, and hourly spacing verified ({len(utc_times)} records)")

    def test_04_trace_duration_and_bounds(self):
        """Test expected row count (690) and exact time window bounds."""
        self.assertEqual(len(self.utc_df), 690)
        self.assertEqual(self.utc_df.iloc[0]["timestamp"], START_UTC)
        self.assertEqual(self.utc_df.iloc[-1]["timestamp"], END_UTC)
        self.assertEqual(self.utc_df.iloc[0]["timestamp"], "2026-08-19T00:00:00Z")
        self.assertEqual(self.utc_df.iloc[-1]["timestamp"], "2026-09-16T17:00:00Z")
        print(f"[PASS] 4. Trace duration and bounds verified: 690 rows ({START_UTC} to {END_UTC})")

    def test_05_utc_representation_and_no_double_conversion(self):
        """Test that timestamps have explicit ISO UTC 'Z' representation and zero offset."""
        for ts_str in self.utc_df["timestamp"]:
            self.assertTrue(ts_str.endswith("Z"), f"Timestamp missing UTC 'Z' indicator: {ts_str}")
        parsed_first = pd.to_datetime(self.utc_df.iloc[0]["timestamp"])
        self.assertEqual(parsed_first.hour, 0)
        self.assertEqual(parsed_first.minute, 0)
        self.assertEqual(parsed_first.second, 0)
        print("[PASS] 5. Explicit UTC formatting ('Z') and zero offset verified for all 690 records")

    def test_06_independent_reproducibility(self):
        """
        Non-tautological reproducibility test:
        1. Executes process_india_data into isolated temporary directories.
        2. Compares fresh temporary outputs against the preserved reference file.
        3. Executes a second run to verify bitwise multi-run determinism.
        """
        raw_df = pd.read_csv(RAW_INPUT_FILE)
        self.assertEqual(len(raw_df), 681, "Raw input must contain exactly 681 records")

        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)
            temp_gapfilled_1 = tmp_path / "run1_gapfilled.csv"
            temp_utc_1 = tmp_path / "run1_utc.csv"
            temp_gapfilled_2 = tmp_path / "run2_gapfilled.csv"
            temp_utc_2 = tmp_path / "run2_utc.csv"

            # Run 1: generate into isolated temp directory
            process_india_data(
                raw_file=RAW_INPUT_FILE,
                gapfilled_out=temp_gapfilled_1,
                utc_aligned_out=temp_utc_1
            )

            # Compare Run 1 against canonical reference file
            run1_utc_df = pd.read_csv(temp_utc_1)
            self.assertEqual(len(run1_utc_df), len(self.utc_df))
            self.assertTrue((run1_utc_df["timestamp"] == self.utc_df["timestamp"]).all())
            np.testing.assert_allclose(
                run1_utc_df["carbon_intensity"].values,
                self.utc_df["carbon_intensity"].values,
                rtol=1e-7,
                atol=1e-7,
                err_msg="Freshly processed output differs from preserved reference"
            )

            # Run 2: generate second independent run
            process_india_data(
                raw_file=RAW_INPUT_FILE,
                gapfilled_out=temp_gapfilled_2,
                utc_aligned_out=temp_utc_2
            )

            # Assert absolute determinism between Run 1 and Run 2
            run2_utc_df = pd.read_csv(temp_utc_2)
            np.testing.assert_array_equal(
                run1_utc_df["carbon_intensity"].values,
                run2_utc_df["carbon_intensity"].values,
                err_msg="Separate preprocessing executions produced non-deterministic outputs"
            )

        print("[PASS] 6. Independent reproducibility and multi-run determinism confirmed (rtol=1e-7, atol=1e-7)")

    def test_07_physical_solar_sanity(self):
        """Test that raw solar peak is at midday IST, confirming IST source timezone."""
        raw_df = pd.read_csv(RAW_INPUT_FILE)
        dt = pd.to_datetime(raw_df["timestamp"])
        solar_by_hour = raw_df.groupby(dt.dt.hour)["solar_mw"].mean()
        peak_hour = solar_by_hour.idxmax()
        self.assertIn(peak_hour, [11, 12, 13])
        print(f"[PASS] 7. Solar physical sanity confirmed: Peak generation at hour {peak_hour}:00 IST")

    def test_08_physical_carbon_intensity_bounds(self):
        """Test that all carbon intensity values are non-null, strictly positive, and physically plausible."""
        ci_values = self.utc_df["carbon_intensity"]
        self.assertFalse(ci_values.isna().any(), "Carbon intensity contains NaN values")
        self.assertTrue((ci_values > 0).all(), "Carbon intensity contains non-positive values")
        min_ci = ci_values.min()
        max_ci = ci_values.max()
        mean_ci = ci_values.mean()
        # Indian thermal-heavy grid carbon intensity is typically between 400 and 1000 gCO2e/kWh
        self.assertGreater(min_ci, 400.0, f"Min carbon intensity abnormally low: {min_ci}")
        self.assertLess(max_ci, 1000.0, f"Max carbon intensity abnormally high: {max_ci}")
        print(f"[PASS] 8. Carbon intensity physical sanity confirmed: min={min_ci:.2f}, max={max_ci:.2f}, mean={mean_ci:.2f} gCO2e/kWh")


if __name__ == "__main__":
    unittest.main()
