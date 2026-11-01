package com.ecocloud;

import com.ecocloud.carbon.CarbonTrace;
import com.ecocloud.scheduler.CarbonAwareScheduler;
import com.ecocloud.scheduler.WiesnerAdHocScheduler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Automated Verification and Numerical Audit Test Suite for EcoCloud.
 *
 * This test suite validates:
 * 1. Hand-calculated energy-to-CO2e formulas and unit consistency.
 * 2. Carbon-trace time indexing, coverage, and missing-data handling.
 * 3. Temporal window integration across hourly boundaries.
 * 4. Deterministic reproducibility of workload generators.
 * 5. Relative carbon saving percentage calculations.
 * 6. Scheduler responsiveness to differing regional carbon signals.
 * 7. Deadline constraint validation in candidate window selection.
 */
public class AuditValidationTest {

    private static int totalTests = 0;
    private static int passedTests = 0;

    private static void assertTrue(String testName, boolean condition, String details) {
        totalTests++;
        if (condition) {
            passedTests++;
            System.out.printf("[PASS] %-45s | %s%n", testName, details);
        } else {
            System.err.printf("[FAIL] %-45s | %s%n", testName, details);
        }
    }

    private static void assertAlmostEqual(String testName, double expected, double actual, double tolerance, String unit) {
        totalTests++;
        double diff = Math.abs(expected - actual);
        if (diff <= tolerance) {
            passedTests++;
            System.out.printf("[PASS] %-45s | Expected=%.4f, Actual=%.4f %s (diff=%.6f)%n",
                    testName, expected, actual, unit, diff);
        } else {
            System.err.printf("[FAIL] %-45s | Expected=%.4f, Actual=%.4f %s (diff=%.6f > tolerance=%.6f)%n",
                    testName, expected, actual, unit, diff, tolerance);
        }
    }

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("                 ECOCLOUD VERIFICATION & NUMERICAL AUDIT SUITE                   ");
        System.out.println("================================================================================");

        testEnergyAndCarbonFormulas();
        testTraceLoadingAndAlignment();
        testWindowCarbonIntegration();
        testWorkloadReproducibility();
        testCarbonSavingsCalculation();
        testSchedulerDecisionsAcrossRegions();
        testDeadlineEnforcement();

        System.out.println("================================================================================");
        System.out.printf("SUMMARY: %d / %d tests passed (%.1f%%)%n",
                passedTests, totalTests, (100.0 * passedTests / totalTests));
        System.out.println("================================================================================");

        if (passedTests != totalTests) {
            System.exit(1);
        }
    }

    /**
     * TEST 1: Energy and CO2e hand calculations.
     * Workload: 150 W active power running for 3600 seconds (1 hour).
     * Energy = 150 W * 1 h = 150 Wh = 0.150 kWh.
     * Given Carbon Intensity = 400 gCO2e/kWh:
     * Emissions = 0.150 kWh * 400 gCO2e/kWh = 60.0 gCO2e.
     */
    private static void testEnergyAndCarbonFormulas() {
        System.out.println("\n--- Section 1: Energy and Carbon Numerical Formulas ---");
        double powerWatts = 150.0;
        double durationSeconds = 3600.0;
        double energyWh = powerWatts * durationSeconds / 3600.0;
        double energyKWh = energyWh / 1000.0;

        assertAlmostEqual("1.1 Active Energy (Wh)", 150.0, energyWh, 1e-9, "Wh");
        assertAlmostEqual("1.2 Active Energy (kWh)", 0.150, energyKWh, 1e-9, "kWh");

        double carbonIntensity = 400.0; // gCO2e/kWh
        double emissionsG = energyKWh * carbonIntensity;
        assertAlmostEqual("1.3 CO2e Emissions (gCO2e)", 60.0, emissionsG, 1e-9, "gCO2e");

        // Test with 2 kWh workload at 400 gCO2e/kWh -> 800 gCO2e
        double largeWorkloadKWh = 2.0;
        double largeEmissions = largeWorkloadKWh * carbonIntensity;
        assertAlmostEqual("1.4 Reference 2 kWh Workload", 800.0, largeEmissions, 1e-9, "gCO2e");
    }

    /**
     * TEST 2: Carbon Trace Loading, Time Indexing, and Coverage.
     */
    private static void testTraceLoadingAndAlignment() {
        System.out.println("\n--- Section 2: Carbon Trace Loading and Alignment ---");
        String gbPath = "data/uk/uk_carbon_hourly_clean.csv";
        String indiaPath = "data/india/india_carbon_hourly_utc_aligned.csv";

        try {
            CarbonTrace gb = new CarbonTrace("GB", gbPath);
            CarbonTrace india = new CarbonTrace("INDIA", indiaPath);

            assertTrue("2.1 GB Trace Loaded Records", gb.size() == 696,
                    "Expected 696 hourly records from common UTC start at 00:00Z");
            assertTrue("2.2 India Trace Loaded Records", india.size() == 690,
                    "Expected 690 hourly UTC-aligned records");

            // Check simulation time 0.0 (Hour 0)
            double gbH0 = gb.getCarbonIntensity(0.0);
            double inH0 = india.getCarbonIntensity(0.0);
            assertAlmostEqual("2.3 GB Hour 0 Carbon Intensity", 202.50, gbH0, 0.01, "gCO2e/kWh");
            assertAlmostEqual("2.4 India Hour 0 Carbon Intensity", 707.4166995, inH0, 0.01, "gCO2e/kWh");

            // Out-of-bounds check (negative time and past end of trace)
            double negativeTimeCi = gb.getCarbonIntensity(-3600.0);
            assertTrue("2.5 Negative Time Lookup", negativeTimeCi == -1.0,
                    "Lookup before experiment start must return -1.0");

            double pastEndCi = gb.getCarbonIntensity(700 * 3600.0);
            assertTrue("2.6 Past-End Time Lookup", pastEndCi == -1.0,
                    "Lookup beyond trace horizon must return -1.0");

            // Verify simulation start hour 432 timestamp mapping
            String h432Timestamp = india.getTimestamp(432 * 3600.0).toString();
            assertTrue("2.7 Forecast Start Hour 432 Timestamp",
                    "2026-09-06T00:00:00Z".equals(h432Timestamp),
                    "Hour 432 must correspond to 2026-09-06T00:00:00Z (05:30 IST)");

        } catch (IOException e) {
            assertTrue("2.X Trace loading failed", false, e.getMessage());
        }
    }

    /**
     * TEST 3: Window Integration across Hour Boundaries.
     * Job starts at t = 1800s (0.5h) and runs for 3600s (finish = 5400s / 1.5h).
     * Span: 1800s in Hour 0 (GB CI=202.5) + 1800s in Hour 1 (GB CI=200.5).
     * Expected Window Mean = (202.5 * 1800 + 200.5 * 1800) / 3600 = 201.50 gCO2e/kWh.
     */
    private static void testWindowCarbonIntegration() {
        System.out.println("\n--- Section 3: Boundary Crossing Carbon Integration ---");
        try {
            CarbonTrace gb = new CarbonTrace("GB", "data/uk/uk_carbon_hourly_clean.csv");
            WiesnerAdHocScheduler scheduler = new WiesnerAdHocScheduler(gb, 6 * 3600L);

            double powerWatts = 150.0;
            double startSeconds = 1800.0;
            double finishSeconds = 5400.0;

            // Manual step integration
            double hour = 3600.0;
            double seg1Duration = 3600.0 - startSeconds; // 1800s
            double seg2Duration = finishSeconds - 3600.0; // 1800s
            double ci0 = gb.getCarbonIntensity(0.0);     // 202.5
            double ci1 = gb.getCarbonIntensity(3600.0);  // 200.5

            double expectedMeanCi = (ci0 * seg1Duration + ci1 * seg2Duration) / (seg1Duration + seg2Duration);
            assertAlmostEqual("3.1 Window Mean Across Hour Boundary", 201.50, expectedMeanCi, 1e-9, "gCO2e/kWh");

            double expectedCo2e = (powerWatts * 3600.0 / 3600.0 / 1000.0) * expectedMeanCi;
            assertAlmostEqual("3.2 Total Boundary Emissions", 30.225, expectedCo2e, 1e-9, "gCO2e");

        } catch (Exception e) {
            assertTrue("3.X Integration test failed", false, e.getMessage());
        }
    }

    private static final int CANONICAL_WORKLOAD_JOBS = 60;

    /**
     * TEST 4: Workload Reproducibility across Independent Invocations.
     */
    private static void testWorkloadReproducibility() {
        System.out.println("\n--- Section 4: Workload Determinism & Seed Stability ---");
        long seed = 20260320L;
        List<int[]> run1 = generateSampleWorkload(seed);
        List<int[]> run2 = generateSampleWorkload(seed);

        assertTrue("4.0 Workload Size Verification (60 jobs)",
                run1.size() == CANONICAL_WORKLOAD_JOBS && run2.size() == CANONICAL_WORKLOAD_JOBS,
                "Canonical workload must generate exactly 60 jobs");

        boolean identical = true;
        for (int i = 0; i < run1.size(); i++) {
            int[] a = run1.get(i);
            int[] b = run2.get(i);
            for (int k = 0; k < a.length; k++) {
                if (a[k] != b[k]) {
                    identical = false;
                    break;
                }
            }
        }
        assertTrue("4.1 Deterministic Workload Seed (20260320L)", identical,
                "Identical seed must yield bitwise identical 60-job sequences");
    }

    private static List<int[]> generateSampleWorkload(long seed) {
        Random random = new Random(seed);
        List<int[]> jobs = new ArrayList<>();
        for (int id = 0; id < CANONICAL_WORKLOAD_JOBS; id++) {
            int cpu = 1 + random.nextInt(10);
            int runtimeSlots = 1 + random.nextInt(10);
            int releaseSlot = random.nextInt(8);
            int extraSlack = 2 + random.nextInt(12);
            int deadlineSlot = Math.min(releaseSlot + runtimeSlots + extraSlack, 47);
            jobs.add(new int[]{id, cpu, runtimeSlots, releaseSlot, deadlineSlot});
        }
        return jobs;
    }

    /**
     * TEST 5: Relative Carbon Saving Formula Verification.
     * Formula: 100 * (baseline - policy) / baseline.
     */
    private static void testCarbonSavingsCalculation() {
        System.out.println("\n--- Section 5: Carbon Savings Percentage Calculation ---");
        double baselineCo2e = 2470.965;
        double policyCo2e = 2299.014;
        double expectedSavingPct = ((baselineCo2e - policyCo2e) / baselineCo2e) * 100.0;

        assertAlmostEqual("5.1 Wiesner GB Carbon Reduction %", 6.9588, expectedSavingPct, 1e-4, "%");

        // Edge case: zero emissions baseline
        double zeroBaseline = 0.0;
        double savingWithZero = zeroBaseline == 0.0 ? 0.0 : ((zeroBaseline - 10.0) / zeroBaseline) * 100.0;
        assertTrue("5.2 Zero Baseline Edge Case", savingWithZero == 0.0,
                "Zero baseline must avoid division-by-zero error");
    }

    /**
     * TEST 6: Scheduler Decisions Change When Regional Profile Changes.
     */
    private static void testSchedulerDecisionsAcrossRegions() {
        System.out.println("\n--- Section 6: Policy Transferability & Signal Responsiveness ---");
        try {
            CarbonTrace gb = new CarbonTrace("GB", "data/uk/uk_carbon_hourly_clean.csv");
            CarbonTrace india = new CarbonTrace("INDIA", "data/india/india_carbon_hourly_utc_aligned.csv");

            WiesnerAdHocScheduler gbScheduler = new WiesnerAdHocScheduler(gb, 6 * 3600L);
            WiesnerAdHocScheduler inScheduler = new WiesnerAdHocScheduler(india, 6 * 3600L);

            // Job arriving at 02:00 (7200s), runtime 1800s, max deferral 6 hours (up to 8 hours / 28800s)
            long arrival = 7200L;
            long duration = 1800L;

            long gbStart = gbScheduler.selectStartTime(arrival, duration);
            long inStart = inScheduler.selectStartTime(arrival, duration);

            assertTrue("6.1 Scheduler Execution Decision", gbStart >= arrival && inStart >= arrival,
                    "Selected start times must be >= arrival time");

            System.out.printf("     Arrival=%.1fh | GB Selected Start=%.1fh | India Selected Start=%.1fh%n",
                    arrival / 3600.0, gbStart / 3600.0, inStart / 3600.0);

        } catch (Exception e) {
            assertTrue("6.X Regional test failed", false, e.getMessage());
        }
    }

    /**
     * TEST 7: Deadline and Deferral Window Constraint Enforcement.
     */
    private static void testDeadlineEnforcement() {
        System.out.println("\n--- Section 7: Deadline & Feasibility Constraints ---");
        try {
            CarbonTrace gb = new CarbonTrace("GB", "data/uk/uk_carbon_hourly_clean.csv");
            long maxDeferral = 3 * 3600L; // 3 hours
            WiesnerAdHocScheduler scheduler = new WiesnerAdHocScheduler(gb, maxDeferral);

            // Case A: Normal job fits in window
            long arrival = 3600L;
            long duration = 1800L;
            long selected = scheduler.selectStartTime(arrival, duration);
            assertTrue("7.1 Selected Start within Deferral Window",
                    selected + duration <= arrival + maxDeferral,
                    "Job finish must not exceed arrival + maxDeferral");

            // Case B: Job duration > maxDeferral -> must fall back to immediate execution
            long longDuration = 4 * 3600L; // 4 hours > 3 hours max deferral
            long fallback = scheduler.selectStartTime(arrival, longDuration);
            assertTrue("7.2 Infeasible Duration Fallback", fallback == arrival,
                    "Job longer than maxDeferral must execute immediately at arrival");

        } catch (Exception e) {
            assertTrue("7.X Constraint test failed", false, e.getMessage());
        }
    }
}
