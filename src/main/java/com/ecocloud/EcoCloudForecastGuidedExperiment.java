package com.ecocloud;

import com.ecocloud.carbon.CarbonTrace;
import com.ecocloud.carbon.SeasonalNaiveForecaster;
import com.ecocloud.scheduler.BahreiniSrrApxScheduler;
import com.ecocloud.scheduler.BahreiniSrrApxScheduler.DataCenter;
import com.ecocloud.scheduler.BahreiniSrrApxScheduler.Decision;
import com.ecocloud.scheduler.BahreiniSrrApxScheduler.Job;
import com.ecocloud.scheduler.BahreiniSrrApxScheduler.Result;
import com.ecocloud.scheduler.WiesnerAdHocScheduler;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.events.SimEvent;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Controlled India Experiment: Forecast-Guided Bahreini SRR-APX.
 *
 * Evaluates:
 *   1. FCFS (Baseline)
 *   2. Original Bahreini SRR-APX (frozen benchmark, schedule(jobs, sites))
 *   3. Bahreini theta = 0.0 (verification of bitwise identity with original)
 *   4. Forecast-Guided Bahreini theta = 0.5 (causal seasonal-naive Gibbs tilt)
 *   5. Forecast-Guided Bahreini theta = 1.0 (causal seasonal-naive Gibbs tilt)
 *   6. Oracle Bahreini theta = 1.0 (offline diagnostic upper bound using ground-truth future CI)
 *
 * Execution Origin: Simulation Hour 432 (2026-09-06T00:00:00Z / 2026-09-06 05:30:00 IST)
 * Trace: Central Electricity Authority (CEA) of India
 * Seeds: 20260320L, 20260321L, 20260322L
 */
public class EcoCloudForecastGuidedExperiment {

    private static final String INDIA_TRACE =
            "data/india/india_carbon_hourly_utc_aligned.csv";

    // Simulation starts at Hour 432 (2026-09-06T00:00:00Z = 2026-09-06 05:30 IST, Day 19 of trace)
    private static final long START_HOUR = 432L;
    private static final double SIMULATION_START_SECONDS = START_HOUR * 3600.0;

    private static final int SLOT_MINUTES = 30;
    private static final int SLOT_SECONDS = SLOT_MINUTES * 60;
    private static final int HORIZON_SLOTS = 48; // 24 hours
    private static final int NUM_SITES = 2;
    private static final int SITE_CAPACITY = 50;

    private static final int NUM_JOBS = 60;
    private static final long[] SEEDS = {20260320L, 20260321L, 20260322L};

    private static final int MIN_RUNTIME_SLOTS = 1;
    private static final int MAX_RUNTIME_SLOTS = 10;

    private static final int SRR_SAMPLES = 100;
    private static final double GAMMA = 0.0;
    private static final long WIESNER_MAX_DEFERRAL_SECONDS = 6 * 3600L;

    private static final double MIPS = 1000.0;
    private static final double ACTIVE_VM_POWER_WATTS = 150.0;

    private static final int JOB_ARRIVAL_EVENT = 21001;
    private static final int JOB_EXECUTION_EVENT = 21002;

    public record WorkloadJob(
            int id,
            int cpu,
            int runtimeSlots,
            int releaseSlot,
            int deadlineSlot) {

        double arrivalSeconds() {
            return releaseSlot * (double) SLOT_SECONDS;
        }

        double runtimeSeconds() {
            return runtimeSlots * (double) SLOT_SECONDS;
        }

        double deadlineSeconds() {
            return deadlineSlot * (double) SLOT_SECONDS;
        }
    }

    public record RunResult(
            long seed,
            String algorithm,
            int scheduledJobs,
            int failedJobs,
            int completedJobs,
            double meanWaitingS,
            double p95WaitingS,
            double meanTurnaroundS,
            double p95TurnaroundS,
            int deadlineViolations,
            double energyWh,
            double co2eG,
            double carbonSavingPct,
            double makespanS,
            double latencyMs) {
    }

    public static void main(String[] args) throws Exception {
        boolean runAll = false;
        Long targetSeed = null;
        String targetAlgo = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--all".equalsIgnoreCase(arg) || "-all".equalsIgnoreCase(arg)) {
                runAll = true;
            } else if ("--seed".equalsIgnoreCase(arg) || "-seed".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    targetSeed = Long.parseLong(args[++i]);
                }
            } else if ("--algo".equalsIgnoreCase(arg) || "-algo".equalsIgnoreCase(arg) || "--algorithm".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    targetAlgo = args[++i];
                }
            } else if ("--help".equalsIgnoreCase(arg) || "-help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                printUsage();
                return;
            }
        }

        if (!runAll && targetSeed == null && targetAlgo == null) {
            System.out.println("[EcoCloud] No execution mode specified.");
            System.out.println("[EcoCloud] Running single experiment test (Seed: " + SEEDS[0] + ", Algorithm: FCFS).");
            System.out.println("[EcoCloud] To run a specific scheduler: --algo <FCFS|Wiesner|Bahreini|Theta0|Forecast05|Forecast10|Oracle> [--seed <seed>]");
            System.out.println("[EcoCloud] To run the full multi-seed experiment matrix: --all");
            targetSeed = SEEDS[0];
            targetAlgo = "FCFS";
        }

        long[] activeSeeds = runAll ? SEEDS : (targetSeed != null ? new long[]{targetSeed} : new long[]{SEEDS[0]});

        System.out.println("================================================================================");
        System.out.println("     ECOCLOUD - FORECAST-GUIDED BAHREINI SRR-APX EXPERIMENTAL BENCHMARK         ");
        System.out.println("================================================================================");
        System.out.printf(Locale.US, "Start Hour : %d (2026-09-06T00:00:00Z / 05:30 IST)%n", START_HOUR);
        System.out.printf(Locale.US, "Horizon    : %d slots (%d hours)%n", HORIZON_SLOTS, HORIZON_SLOTS * SLOT_MINUTES / 60);
        System.out.printf(Locale.US, "Capacity   : %d sites x %d PEs (SpaceShared)%n", NUM_SITES, SITE_CAPACITY);
        if (runAll) {
            System.out.printf(Locale.US, "Mode       : Full Multi-Seed Matrix (Seeds: %d, %d, %d)%n", SEEDS[0], SEEDS[1], SEEDS[2]);
        } else {
            System.out.printf(Locale.US, "Mode       : Single Experiment (Seed: %d, Algorithm: %s)%n",
                    activeSeeds[0], (targetAlgo != null ? targetAlgo : "ALL"));
        }
        System.out.println("--------------------------------------------------------------------------------");

        CarbonTrace indiaTrace = new CarbonTrace("INDIA", INDIA_TRACE);

        // Build ground-truth baseline slot array for the 48-slot horizon starting at Hour 432
        double[] baselineSlotCi = new double[HORIZON_SLOTS];
        for (int slot = 0; slot < HORIZON_SLOTS; slot++) {
            long sec = (long) (SIMULATION_START_SECONDS + slot * SLOT_SECONDS);
            double val = indiaTrace.getCarbonIntensity(sec);
            baselineSlotCi[slot] = val < 0 ? 1000.0 : val;
        }

        // Causal Seasonal-Naive Forecaster: trained strictly on hours 0-431
        SeasonalNaiveForecaster forecaster = new SeasonalNaiveForecaster(indiaTrace, "INDIA", 431L);
        double[] forecastSlotCi = forecaster.buildForecastSlotArray(HORIZON_SLOTS, SLOT_MINUTES, SIMULATION_START_SECONDS);

        double[][] forecastGuidance = new double[NUM_SITES][HORIZON_SLOTS];
        double[][] oracleGuidance = new double[NUM_SITES][HORIZON_SLOTS];
        for (int s = 0; s < NUM_SITES; s++) {
            forecastGuidance[s] = forecastSlotCi;
            oracleGuidance[s] = baselineSlotCi; // Ground truth future CI
        }

        System.out.printf(Locale.US, "Forecast verification: mean forecast CI=%.2f gCO2/kWh, actual future mean CI=%.2f gCO2/kWh%n",
                calcMean(forecastSlotCi), calcMean(baselineSlotCi));
        System.out.println("--------------------------------------------------------------------------------");

        List<RunResult> allResults = new ArrayList<>();

        for (long seed : activeSeeds) {
            System.out.printf("--- Executing Seed %d ---%n", seed);
            List<WorkloadJob> workload = generateWorkload(seed);

            // 1. FCFS Baseline
            RunResult fcfs = runFcfs(seed, indiaTrace, workload, 0.0);
            if (shouldRunAlgo(targetAlgo, "FCFS")) {
                allResults.add(fcfs);
                System.out.printf(Locale.US, "  [1/7] FCFS                         : CO2e=%.2fg | Energy=%.2fWh | Wait=%.1fs | Violations=%d%n",
                        fcfs.co2eG, fcfs.energyWh, fcfs.meanWaitingS, fcfs.deadlineViolations);
            }

            // 2. Wiesner AdHoc (2021)
            if (shouldRunAlgo(targetAlgo, "Wiesner_2021_AdHoc")) {
                RunResult wiesner = runWiesner(seed, indiaTrace, workload, fcfs.co2eG);
                allResults.add(wiesner);
                System.out.printf(Locale.US, "  [2/7] Wiesner AdHoc (2021)         : CO2e=%.2fg (%+.2f%%) | %d/%d comp | Wait=%.1fs | Viol=%d%n",
                        wiesner.co2eG, wiesner.carbonSavingPct, wiesner.completedJobs, NUM_JOBS,
                        wiesner.meanWaitingS, wiesner.deadlineViolations);
            }

            // 3. Original Bahreini SRR-APX (calls schedule(jobs, sites))
            RunResult origBahreini = null;
            if (shouldRunAlgo(targetAlgo, "Bahreini_Original_Baseline")) {
                origBahreini = runBahreiniVariant(
                        seed, "Bahreini_Original_Baseline", indiaTrace, baselineSlotCi,
                        workload, fcfs.co2eG, null, 0.0, true
                );
                allResults.add(origBahreini);
                System.out.printf(Locale.US, "  [3/7] Bahreini Original Baseline   : CO2e=%.2fg (%+.2f%%) | %d/%d comp | Wait=%.1fs | Viol=%d | Latency=%.1fms%n",
                        origBahreini.co2eG, origBahreini.carbonSavingPct, origBahreini.completedJobs, NUM_JOBS,
                        origBahreini.meanWaitingS, origBahreini.deadlineViolations, origBahreini.latencyMs);
            }

            // 4. Bahreini theta = 0.0 (verification of bitwise baseline identity)
            RunResult thetaZero = null;
            if (shouldRunAlgo(targetAlgo, "Bahreini_Theta_0.0_Verification")) {
                thetaZero = runBahreiniVariant(
                        seed, "Bahreini_Theta_0.0_Verification", indiaTrace, baselineSlotCi,
                        workload, fcfs.co2eG, null, 0.0, false
                );
                allResults.add(thetaZero);
                System.out.printf(Locale.US, "  [4/7] Bahreini Theta=0.0 (Verify)  : CO2e=%.2fg (%+.2f%%) | %d/%d comp | Wait=%.1fs | Viol=%d | Latency=%.1fms%n",
                        thetaZero.co2eG, thetaZero.carbonSavingPct, thetaZero.completedJobs, NUM_JOBS,
                        thetaZero.meanWaitingS, thetaZero.deadlineViolations, thetaZero.latencyMs);
            }

            // Verify bitwise reproducibility at theta=0 if both were run
            if (origBahreini != null && thetaZero != null) {
                boolean identical = (Double.compare(origBahreini.co2eG, thetaZero.co2eG) == 0)
                        && (origBahreini.completedJobs == thetaZero.completedJobs)
                        && (origBahreini.deadlineViolations == thetaZero.deadlineViolations);
                if (!identical) {
                    System.err.printf("  [WARNING] Seed %d: Theta=0.0 did not reproduce exact baseline! Orig=%.4f vs Theta0=%.4f%n",
                            seed, origBahreini.co2eG, thetaZero.co2eG);
                }
            }

            // 5. Forecast-Guided Bahreini theta = 0.5
            if (shouldRunAlgo(targetAlgo, "Forecast_Guided_Theta_0.5")) {
                RunResult forecastTheta05 = runBahreiniVariant(
                        seed, "Forecast_Guided_Theta_0.5", indiaTrace, baselineSlotCi,
                        workload, fcfs.co2eG, forecastGuidance, 0.5, false
                );
                allResults.add(forecastTheta05);
                System.out.printf(Locale.US, "  [5/7] Forecast-Guided Theta=0.5    : CO2e=%.2fg (%+.2f%%) | %d/%d comp | Wait=%.1fs | Viol=%d | Latency=%.1fms%n",
                        forecastTheta05.co2eG, forecastTheta05.carbonSavingPct, forecastTheta05.completedJobs, NUM_JOBS,
                        forecastTheta05.meanWaitingS, forecastTheta05.deadlineViolations, forecastTheta05.latencyMs);
            }

            // 6. Forecast-Guided Bahreini theta = 1.0
            if (shouldRunAlgo(targetAlgo, "Forecast_Guided_Theta_1.0")) {
                RunResult forecastTheta10 = runBahreiniVariant(
                        seed, "Forecast_Guided_Theta_1.0", indiaTrace, baselineSlotCi,
                        workload, fcfs.co2eG, forecastGuidance, 1.0, false
                );
                allResults.add(forecastTheta10);
                System.out.printf(Locale.US, "  [6/7] Forecast-Guided Theta=1.0    : CO2e=%.2fg (%+.2f%%) | %d/%d comp | Wait=%.1fs | Viol=%d | Latency=%.1fms%n",
                        forecastTheta10.co2eG, forecastTheta10.carbonSavingPct, forecastTheta10.completedJobs, NUM_JOBS,
                        forecastTheta10.meanWaitingS, forecastTheta10.deadlineViolations, forecastTheta10.latencyMs);
            }

            // 7. Oracle Bahreini theta = 1.0 (Offline Diagnostic Upper Bound)
            if (shouldRunAlgo(targetAlgo, "Oracle_Theta_1.0_Diagnostic_Upper_Bound")) {
                RunResult oracleTheta10 = runBahreiniVariant(
                        seed, "Oracle_Theta_1.0_Diagnostic_Upper_Bound", indiaTrace, baselineSlotCi,
                        workload, fcfs.co2eG, oracleGuidance, 1.0, false
                );
                allResults.add(oracleTheta10);
                System.out.printf(Locale.US, "  [7/7] Oracle Theta=1.0 (Upper Bound: CO2e=%.2fg (%+.2f%%) | %d/%d comp | Wait=%.1fs | Viol=%d | Latency=%.1fms%n",
                        oracleTheta10.co2eG, oracleTheta10.carbonSavingPct, oracleTheta10.completedJobs, NUM_JOBS,
                        oracleTheta10.meanWaitingS, oracleTheta10.deadlineViolations, oracleTheta10.latencyMs);
            }
            System.out.println();
        }

        if (runAll) {
            writeRawResults(allResults);
            writeSummaryStatistics(allResults);

            System.out.println("================================================================================");
            System.out.println("OUTPUT RAW CSV     : results/forecast_guided_raw_results.csv");
            System.out.println("OUTPUT SUMMARY CSV : results/forecast_guided_summary.csv");
            System.out.println("================================================================================");
        } else {
            System.out.println("================================================================================");
            System.out.printf(Locale.US, "Single experiment complete (%d run(s) executed).%n", allResults.size());
            System.out.println("Note: Multi-seed CSV summaries were not overwritten.");
            System.out.println("To execute the full multi-seed matrix and update summary CSVs, use: --all");
            System.out.println("================================================================================");
        }
    }

    private static boolean shouldRunAlgo(String targetAlgo, String algoName) {
        if (targetAlgo == null || targetAlgo.isBlank() || targetAlgo.equalsIgnoreCase("all")) {
            return true;
        }
        String cleanTarget = targetAlgo.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
        String cleanName = algoName.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (cleanName.contains(cleanTarget) || cleanTarget.contains(cleanName)) {
            return true;
        }
        if (cleanTarget.equals("fcfs") && cleanName.contains("fcfs")) return true;
        if (cleanTarget.equals("wiesner") && cleanName.contains("wiesner")) return true;
        if (cleanTarget.equals("bahreini") && cleanName.equals("bahreinioriginalbaseline")) return true;
        if ((cleanTarget.equals("theta0") || cleanTarget.equals("theta00")) && cleanName.contains("theta00")) return true;
        if ((cleanTarget.equals("forecast05") || cleanTarget.equals("theta05")) && cleanName.contains("theta05")) return true;
        if ((cleanTarget.equals("forecast10") || cleanTarget.equals("theta10")) && cleanName.contains("theta10")) return true;
        if (cleanTarget.equals("oracle") && cleanName.contains("oracle")) return true;
        return false;
    }

    private static void printUsage() {
        System.out.println("Usage: java -cp \"lib/*;bin\" com.ecocloud.EcoCloudForecastGuidedExperiment [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --all                  Run full 3-seed matrix across all 7 algorithms and export CSVs");
        System.out.println("  --seed <seed>          Run single seed (e.g., 20260320, 20260321, 20260322)");
        System.out.println("  --algo <name>          Run specific algorithm: FCFS, Wiesner, Bahreini, Theta0,");
        System.out.println("                         Forecast05, Forecast10, Oracle, or All");
        System.out.println("  --help, -h             Display this help message");
    }

    private static List<WorkloadJob> generateWorkload(long seed) {
        Random random = new Random(seed);
        List<WorkloadJob> jobs = new ArrayList<>();

        for (int id = 0; id < NUM_JOBS; id++) {
            int cpu = 1 + random.nextInt(10);
            int runtimeSlots = MIN_RUNTIME_SLOTS + random.nextInt(
                    MAX_RUNTIME_SLOTS - MIN_RUNTIME_SLOTS + 1);
            int releaseSlot = random.nextInt(14);
            int extraSlack = 2 + random.nextInt(19);
            int deadlineSlot = Math.min(
                    releaseSlot + runtimeSlots + extraSlack,
                    HORIZON_SLOTS - 1
            );
            if (deadlineSlot < releaseSlot + runtimeSlots) {
                deadlineSlot = releaseSlot + runtimeSlots;
            }
            jobs.add(new WorkloadJob(id, cpu, runtimeSlots, releaseSlot, deadlineSlot));
        }

        return jobs;
    }

    private static RunResult runFcfs(
            long seed,
            CarbonTrace trace,
            List<WorkloadJob> original,
            double baselineCo2e) {

        List<WorkloadJob> workload = new ArrayList<>(original);
        CloudSimPlus simulation = new CloudSimPlus();
        createDatacenter(simulation);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        broker.setShutdownWhenIdle(false);
        List<Vm> vms = createVms();
        broker.submitVmList(vms);

        broker.setVmMapper(cloudlet -> vms.get(((int) cloudlet.getId()) % NUM_SITES));

        List<JobRun> runs = new ArrayList<>();
        for (WorkloadJob job : workload) {
            long length = Math.round(job.runtimeSeconds() * MIPS);
            CloudletSimple cloudlet = new CloudletSimple(length, job.cpu());
            cloudlet.setId(job.id());
            cloudlet.setSubmissionDelay(job.arrivalSeconds());
            runs.add(new JobRun(job));
            runs.get(runs.size() - 1).cloudlet = cloudlet;
        }

        List<Cloudlet> cloudlets = new ArrayList<>();
        for (JobRun run : runs) {
            cloudlets.add(run.cloudlet);
        }
        broker.submitCloudletList(cloudlets);

        simulation.start();

        return collectMetrics(
                seed, "FCFS", original, runs,
                broker.getCloudletFinishedList(), trace,
                original.size(), 0, baselineCo2e, 0.0
        );
    }

    private static RunResult runWiesner(
            long seed,
            CarbonTrace trace,
            List<WorkloadJob> workload,
            double baselineCo2e) {

        CloudSimPlus simulation = new CloudSimPlus();
        createDatacenter(simulation);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        broker.setShutdownWhenIdle(false);
        List<Vm> vms = createVms();
        broker.submitVmList(vms);
        broker.setVmMapper(cloudlet -> vms.get(((int) cloudlet.getId()) % NUM_SITES));

        Map<Integer, JobRun> runById = new HashMap<>();
        WiesnerAdHocScheduler scheduler = new WiesnerAdHocScheduler(trace, WIESNER_MAX_DEFERRAL_SECONDS);

        simulation.addOnSimulationStartListener(info -> {
            for (WorkloadJob job : workload) {
                JobRun run = new JobRun(job);
                runById.put(job.id(), run);
                run.cloudlet = new CloudletSimple(
                        Math.round(job.runtimeSeconds() * MIPS),
                        job.cpu());
                run.cloudlet.setId(job.id());
                broker.schedule(job.arrivalSeconds(), JOB_ARRIVAL_EVENT, run);
            }
        });

        simulation.addOnEventProcessingListener((SimEvent event) -> {
            if (!(event.getData() instanceof JobRun run)) return;

            if (event.getTag() == JOB_ARRIVAL_EVENT) {
                double now = simulation.clock();
                double realNow = SIMULATION_START_SECONDS + now;
                double realPlanned = scheduler.selectStartTime(
                        Math.round(realNow),
                        Math.round(run.job.runtimeSeconds())
                );
                double planned = Math.max(now, realPlanned - SIMULATION_START_SECONDS);
                run.plannedStart = planned;
                run.cloudlet.setSubmissionDelay(0);

                double delay = planned - now;
                if (delay <= 1e-9) {
                    broker.submitCloudlet(run.cloudlet);
                } else {
                    broker.schedule(delay, JOB_EXECUTION_EVENT, run);
                }
            } else if (event.getTag() == JOB_EXECUTION_EVENT) {
                broker.submitCloudlet(run.cloudlet);
            }
        });

        simulation.start();

        List<JobRun> runs = new ArrayList<>(runById.values());
        return collectMetrics(
                seed, "Wiesner_2021_AdHoc", workload, runs,
                broker.getCloudletFinishedList(), trace,
                workload.size(), 0, baselineCo2e, 0.0
        );
    }

    private static RunResult runBahreiniVariant(
            long seed,
            String variantName,
            CarbonTrace trace,
            double[] baselineSlotCi,
            List<WorkloadJob> original,
            double baselineCo2e,
            double[][] guidanceCi,
            double theta,
            boolean useOriginalOverload) {

        List<Job> jobs = new ArrayList<>();
        for (WorkloadJob w : original) {
            jobs.add(new Job(w.id(), w.cpu(), w.runtimeSlots(), w.releaseSlot(), w.deadlineSlot()));
        }

        List<DataCenter> sites = new ArrayList<>();
        for (int i = 0; i < NUM_SITES; i++) {
            sites.add(new DataCenter(i, SITE_CAPACITY, baselineSlotCi));
        }

        BahreiniSrrApxScheduler scheduler = new BahreiniSrrApxScheduler(
                SLOT_MINUTES, SRR_SAMPLES, GAMMA, seed);

        long t0 = System.nanoTime();
        Result result;
        if (useOriginalOverload || guidanceCi == null) {
            result = scheduler.schedule(jobs, sites);
        } else {
            result = scheduler.schedule(jobs, sites, guidanceCi, theta);
        }
        long t1 = System.nanoTime();
        double latencyMs = (t1 - t0) / 1_000_000.0;

        Map<Integer, Decision> decisions = new HashMap<>();
        for (Decision d : result.decisions()) decisions.put(d.jobId, d);

        CloudSimPlus simulation = new CloudSimPlus();
        createDatacenter(simulation);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        broker.setShutdownWhenIdle(false);
        List<Vm> vms = createVms();
        broker.submitVmList(vms);
        broker.setVmMapper(cloudlet -> {
            Decision d = decisions.get((int) cloudlet.getId());
            return d == null
                    ? vms.get(((int) cloudlet.getId()) % NUM_SITES)
                    : vms.get(d.dataCenterId);
        });

        List<JobRun> runs = new ArrayList<>();
        List<Cloudlet> cloudlets = new ArrayList<>();

        for (WorkloadJob job : original) {
            Decision d = decisions.get(job.id());
            if (d == null) continue;

            JobRun run = new JobRun(job);
            run.plannedStart = d.startSlot * (double) SLOT_SECONDS;
            run.site = d.dataCenterId;
            run.cloudlet = new CloudletSimple(
                    Math.round(d.runtimeSlots * (double) SLOT_SECONDS * MIPS),
                    d.requiredCpu);
            run.cloudlet.setId(job.id());
            run.cloudlet.setSubmissionDelay(run.plannedStart);
            runs.add(run);
            cloudlets.add(run.cloudlet);
        }

        broker.submitCloudletList(cloudlets);
        simulation.start();

        return collectMetrics(
                seed, variantName, original, runs,
                broker.getCloudletFinishedList(), trace,
                result.decisions().size(), result.failedJobs(), baselineCo2e, latencyMs
        );
    }

    private static final class JobRun {
        final WorkloadJob job;
        CloudletSimple cloudlet;
        double plannedStart = -1;
        int site = 0;

        JobRun(WorkloadJob job) {
            this.job = job;
        }
    }

    private static RunResult collectMetrics(
            long seed,
            String algorithm,
            List<WorkloadJob> workload,
            List<JobRun> runs,
            List<Cloudlet> finished,
            CarbonTrace trace,
            int scheduledJobs,
            int failedJobs,
            double baselineCo2e,
            double latencyMs) {

        Map<Integer, WorkloadJob> jobById = new HashMap<>();
        for (WorkloadJob job : workload) jobById.put(job.id(), job);

        Map<Integer, JobRun> runById = new HashMap<>();
        for (JobRun run : runs) runById.put(run.job.id(), run);

        List<Double> waits = new ArrayList<>();
        List<Double> turnarounds = new ArrayList<>();
        int deadlineViolations = 0;
        double energyWh = 0.0;
        double co2eG = 0.0;
        double makespanS = 0.0;

        for (Cloudlet cloudlet : finished) {
            int id = (int) cloudlet.getId();
            WorkloadJob job = jobById.get(id);
            JobRun run = runById.get(id);
            if (job == null || run == null) continue;

            double start = cloudlet.getStartTime();
            double finish = cloudlet.getFinishTime();
            double waiting = Math.max(0.0, start - job.arrivalSeconds());
            double turnaround = Math.max(0.0, finish - job.arrivalSeconds());

            waits.add(waiting);
            turnarounds.add(turnaround);

            if (finish > job.deadlineSeconds() + 1e-6) deadlineViolations++;
            if (finish > makespanS) makespanS = finish;

            double execution = Math.max(0.0, finish - start);
            double jobEnergyWh = ACTIVE_VM_POWER_WATTS * execution / 3600.0;
            energyWh += jobEnergyWh;

            // Real-world carbon lookup anchored at SIMULATION_START_SECONDS
            double realStart = SIMULATION_START_SECONDS + start;
            double realFinish = SIMULATION_START_SECONDS + finish;
            co2eG += integrateCarbon(trace, realStart, realFinish, ACTIVE_VM_POWER_WATTS);
        }

        double carbonSavingPct = 0.0;
        if (baselineCo2e > 0.0) {
            carbonSavingPct = ((baselineCo2e - co2eG) / baselineCo2e) * 100.0;
        }

        return new RunResult(
                seed,
                algorithm,
                scheduledJobs,
                failedJobs,
                finished.size(),
                mean(waits),
                percentile95(waits),
                mean(turnarounds),
                percentile95(turnarounds),
                deadlineViolations,
                energyWh,
                co2eG,
                carbonSavingPct,
                makespanS,
                latencyMs
        );
    }

    private static double integrateCarbon(
            CarbonTrace trace,
            double startSeconds,
            double finishSeconds,
            double powerWatts) {

        if (finishSeconds <= startSeconds) return 0.0;

        double carbonG = 0.0;
        double cursor = startSeconds;
        final double hour = 3600.0;

        while (cursor < finishSeconds - 1e-9) {
            double nextHour = (Math.floor(cursor / hour) + 1.0) * hour;
            double segmentEnd = Math.min(finishSeconds, nextHour);
            double duration = segmentEnd - cursor;
            double ci = trace.getCarbonIntensity(Math.max(0, (long) Math.floor(cursor)));
            if (ci < 0) ci = 1000.0;
            carbonG += (powerWatts * duration / 3600.0 / 1000.0) * ci;
            cursor = segmentEnd;
        }

        return carbonG;
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double percentile95(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        List<Double> copy = new ArrayList<>(values);
        copy.sort(Comparator.naturalOrder());
        int index = Math.min(
                copy.size() - 1,
                Math.max(0, (int) Math.ceil(0.95 * copy.size()) - 1)
        );
        return copy.get(index);
    }

    private static void writeRawResults(List<RunResult> results) throws IOException {
        File out = new File("results/forecast_guided_raw_results.csv");
        try (FileWriter writer = new FileWriter(out)) {
            writer.write("seed,algorithm,scheduled_jobs,failed_jobs,completed_jobs," +
                    "mean_waiting_s,p95_waiting_s,mean_turnaround_s,p95_turnaround_s," +
                    "deadline_violations,energy_wh,co2e_g,carbon_saving_pct,makespan_s,latency_ms\n");

            for (RunResult r : results) {
                writer.write(String.format(
                        Locale.US,
                        "%d,%s,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%d,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                        r.seed, r.algorithm,
                        r.scheduledJobs, r.failedJobs, r.completedJobs,
                        r.meanWaitingS, r.p95WaitingS, r.meanTurnaroundS, r.p95TurnaroundS,
                        r.deadlineViolations, r.energyWh, r.co2eG, r.carbonSavingPct, r.makespanS, r.latencyMs
                ));
            }
        }
    }

    private static void writeSummaryStatistics(List<RunResult> results) throws IOException {
        File out = new File("results/forecast_guided_summary.csv");

        String[] algorithms = {
                "FCFS",
                "Wiesner_2021_AdHoc",
                "Bahreini_Original_Baseline",
                "Bahreini_Theta_0.0_Verification",
                "Forecast_Guided_Theta_0.5",
                "Forecast_Guided_Theta_1.0",
                "Oracle_Theta_1.0_Diagnostic_Upper_Bound"
        };

        try (FileWriter writer = new FileWriter(out)) {
            writer.write("algorithm,n_seeds,mean_co2e_g,std_co2e_g,mean_carbon_saving_pct,std_carbon_saving_pct," +
                    "mean_waiting_s,std_waiting_s,mean_turnaround_s,std_turnaround_s," +
                    "mean_violations,std_violations,mean_completed,std_completed,mean_energy_wh,std_energy_wh," +
                    "mean_makespan_s,std_makespan_s,mean_latency_ms,std_latency_ms\n");

            for (String alg : algorithms) {
                List<RunResult> subset = new ArrayList<>();
                for (RunResult r : results) {
                    if (r.algorithm.equals(alg)) {
                        subset.add(r);
                    }
                }

                if (subset.isEmpty()) continue;

                double meanCo2 = calcMean(subset.stream().mapToDouble(r -> r.co2eG).toArray());
                double stdCo2 = calcStd(subset.stream().mapToDouble(r -> r.co2eG).toArray());

                double meanSaving = calcMean(subset.stream().mapToDouble(r -> r.carbonSavingPct).toArray());
                double stdSaving = calcStd(subset.stream().mapToDouble(r -> r.carbonSavingPct).toArray());

                double meanWait = calcMean(subset.stream().mapToDouble(r -> r.meanWaitingS).toArray());
                double stdWait = calcStd(subset.stream().mapToDouble(r -> r.meanWaitingS).toArray());

                double meanTurn = calcMean(subset.stream().mapToDouble(r -> r.meanTurnaroundS).toArray());
                double stdTurn = calcStd(subset.stream().mapToDouble(r -> r.meanTurnaroundS).toArray());

                double meanViol = calcMean(subset.stream().mapToDouble(r -> r.deadlineViolations).toArray());
                double stdViol = calcStd(subset.stream().mapToDouble(r -> r.deadlineViolations).toArray());

                double meanComp = calcMean(subset.stream().mapToDouble(r -> r.completedJobs).toArray());
                double stdComp = calcStd(subset.stream().mapToDouble(r -> r.completedJobs).toArray());

                double meanEnergy = calcMean(subset.stream().mapToDouble(r -> r.energyWh).toArray());
                double stdEnergy = calcStd(subset.stream().mapToDouble(r -> r.energyWh).toArray());

                double meanMakespan = calcMean(subset.stream().mapToDouble(r -> r.makespanS).toArray());
                double stdMakespan = calcStd(subset.stream().mapToDouble(r -> r.makespanS).toArray());

                double meanLatency = calcMean(subset.stream().mapToDouble(r -> r.latencyMs).toArray());
                double stdLatency = calcStd(subset.stream().mapToDouble(r -> r.latencyMs).toArray());

                writer.write(String.format(
                        Locale.US,
                        "%s,%d,%.4f,%.4f,%.4f,%.4f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n",
                        alg, subset.size(),
                        meanCo2, stdCo2,
                        meanSaving, stdSaving,
                        meanWait, stdWait,
                        meanTurn, stdTurn,
                        meanViol, stdViol,
                        meanComp, stdComp,
                        meanEnergy, stdEnergy,
                        meanMakespan, stdMakespan,
                        meanLatency, stdLatency
                ));
            }
        }
    }

    private static double calcMean(double[] arr) {
        if (arr.length == 0) return 0.0;
        double sum = 0;
        for (double v : arr) sum += v;
        return sum / arr.length;
    }

    private static double calcStd(double[] arr) {
        if (arr.length <= 1) return 0.0;
        double mean = calcMean(arr);
        double sumSq = 0;
        for (double v : arr) sumSq += Math.pow(v - mean, 2);
        return Math.sqrt(sumSq / (arr.length - 1));
    }

    private static DatacenterSimple createDatacenter(CloudSimPlus simulation) {
        List<Host> hosts = new ArrayList<>();
        for (int h = 0; h < NUM_SITES; h++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < SITE_CAPACITY; p++) {
                peList.add(new PeSimple(MIPS));
            }
            hosts.add(new HostSimple(65536, 100000, 10_000_000, peList));
        }
        DatacenterSimple dc = new DatacenterSimple(simulation, hosts);
        dc.setSchedulingInterval(1);
        return dc;
    }

    private static List<Vm> createVms() {
        List<Vm> vms = new ArrayList<>();
        for (int site = 0; site < NUM_SITES; site++) {
            VmSimple vm = new VmSimple(MIPS, SITE_CAPACITY);
            vm.setRam(8192);
            vm.setBw(10000);
            vm.setSize(100000);
            vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vms.add(vm);
        }
        return vms;
    }
}
