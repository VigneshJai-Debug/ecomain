package com.ecocloud;

import com.ecocloud.carbon.CarbonTrace;
import com.ecocloud.carbon.DiurnalForecastTrace;
import com.ecocloud.carbon.ShiftedCarbonTrace;
import com.ecocloud.scheduler.CapacityAwareDiurnalScheduler;
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
 * Canonical matched-comparison and time-aware cross-validation runner.
 *
 * Evaluates:
 *   - FCFS baseline
 *   - Wiesner 2021 ad-hoc non-interruptible temporal shifting
 *   - Bahreini SRR-APX (LP relaxation + Monte Carlo randomized rounding)
 *   - Deadline_Carbon_Shift (earliest-feasible deadline-bounded shifting)
 *   - CADS (Carbon-Adaptive Deadline-Sensitive) parameterized variants
 *
 * Features:
 *   - Canonical 60-job workload per simulation run;
 *   - Command-line arguments for trace file, region label, trace ID, output path, and seeds;
 *   - Supports fixed origin and rotating-origin time-aware cross-validation;
 *   - Separate reporting of fallback carbon and scheduler decision time.
 *
 * Usage (from the project root):
 *   java -cp target/classes;<deps> com.ecocloud.EcoCloudReview3MultiSeed \
 *        <trace.csv> <region-label> <trace-id> <out.csv> <seed> [<seed> ...]
 *
 * Each seed is used for BOTH the workload generator and the SRR-APX
 * sampler, so one seed defines one complete, independent replicate.
 */
public class EcoCloudReview3MultiSeed {

    private static final int SLOT_MINUTES = 30;
    private static final int SLOT_SECONDS = SLOT_MINUTES * 60;
    // Workload-scenario settings (canonical 60-job workload):
    //   -Decocloud.horizonSlots  planning horizon / deadline cap in 30-min slots (48)
    //   -Decocloud.releaseSlots  jobs are released in slots 0..releaseSlots-1     (8)
    //   -Decocloud.slackRange    extra slack is 2..slackRange+1 slots              (12)
    private static final int HORIZON_SLOTS = Integer.getInteger("ecocloud.horizonSlots", 48);
    private static final int RELEASE_SLOTS = Integer.getInteger("ecocloud.releaseSlots", 8);
    private static final int SLACK_RANGE = Integer.getInteger("ecocloud.slackRange", 12);
    private static final int NUM_SITES = 2;
    private static final int SITE_CAPACITY = 50;

    private static final int NUM_JOBS = 60;
    private static final int MIN_RUNTIME_SLOTS = 1;
    private static final int MAX_RUNTIME_SLOTS = 10;

    private static final int SRR_SAMPLES = 100;
    private static final double GAMMA = 0.0;
    private static final long WIESNER_MAX_DEFERRAL_SECONDS = 6 * 3600L;

    private static final double MIPS = 1000.0;
    private static final double ACTIVE_VM_POWER_WATTS = 150.0;
    private static final double FALLBACK_CI = 1000.0;

    private static final int JOB_ARRIVAL_EVENT = 21001;
    private static final int JOB_EXECUTION_EVENT = 21002;

    /** Lateness above which a deadline miss is treated as material (not simulator overhead). */
    private static final double MATERIAL_LATENESS_S = 60.0;

    /** Per-job rows for the companion *_jobs.csv file. */
    private static final List<String> JOB_ROWS = new ArrayList<>();

    /** CADS decision log rows for the companion *_decisions.csv file. */
    private static final List<String> DECISION_ROWS = new ArrayList<>();

    /** CADS_adaptive thresholds for the companion *_adaptive_theta.csv file. */
    private static final List<String> ADAPTIVE_THETA_ROWS = new ArrayList<>();

    /** CADS reservation ledger length: 400 x 30 min = 200 h, well past any deadline. */
    private static final int CADS_LEDGER_SLOTS = 400;

    private record WorkloadJob(
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

    private record Metrics(
            String experimentId,
            String regionLabel,
            String traceId,
            long seed,
            String algorithm,
            int submittedJobs,
            int admittedJobs,
            int scheduledJobs,
            int rejectedJobs,
            int failedJobs,
            int completedJobs,
            double meanWaitingS,
            double p95WaitingS,
            double meanTurnaroundS,
            double p95TurnaroundS,
            int deadlineViolations,
            int deadlineViolationsOver60s,
            double energyWh,
            double co2eG,
            double fallbackCo2eG,
            double meanCarbonDelayS,
            double schedulerRuntimeMs) {
    }

    private static final class JobRun {
        final WorkloadJob job;
        CloudletSimple cloudlet;
        double plannedStart = -1;

        JobRun(WorkloadJob job) {
            this.job = job;
        }
    }

    /** Running totals for carbon charged to one run, in grams. */
    private static final class Accumulator {
        double co2eG;
        double fallbackCo2eG;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: EcoCloudReview3MultiSeed "
                    + "<trace.csv> <region-label> <trace-id> <out.csv> <seed> [<seed> ...]");
            System.exit(2);
        }

        String tracePath = args[0];
        String regionLabel = args[1];
        String traceId = args[2];
        String outPath = args[3];
        List<Long> seeds = new ArrayList<>();
        for (int i = 4; i < args.length; i++) seeds.add(Long.parseLong(args[i]));

        // Optional Review 3 extensions; the defaults reproduce the original runs.
        //   -Decocloud.startDays=fixed|rotate   rotate: seed s starts on trace day 7 + (s-1) mod 21
        //   -Decocloud.info=oracle|forecast<N>d information given to carbon-aware schedulers
        //                                       (forecast<N>d: N-day same-hour mean, e.g. forecast7d)
        //   -Decocloud.forecastNoise=0.1        multiplicative N(0, sigma) noise on the forecast
        //   -Decocloud.cadsThetas=0,0.05,0.1    add CADS rows for each threshold
        //   -Decocloud.cadsAdaptive=true        add CADS_adaptive (theta = historical forecast MAPE)
        String dayMode = System.getProperty("ecocloud.startDays", "fixed");
        String infoMode = System.getProperty("ecocloud.info", "oracle");
        double forecastNoise = Double.parseDouble(System.getProperty("ecocloud.forecastNoise", "0"));
        boolean cadsAdaptive = Boolean.parseBoolean(System.getProperty("ecocloud.cadsAdaptive", "false"));
        List<Double> thetas = new ArrayList<>();
        for (String t : System.getProperty("ecocloud.cadsThetas", "").split(",")) {
            if (!t.isBlank()) thetas.add(Double.parseDouble(t.trim()));
        }
        if (!dayMode.equals("fixed") && !dayMode.equals("rotate")) {
            throw new IllegalArgumentException("ecocloud.startDays must be fixed or rotate");
        }
        java.util.regex.Matcher forecastMatch =
                java.util.regex.Pattern.compile("forecast(\\d+)d").matcher(infoMode);
        boolean forecast = forecastMatch.matches();
        if (!infoMode.equals("oracle") && !forecast) {
            throw new IllegalArgumentException("ecocloud.info must be oracle or forecast<N>d");
        }
        int historyDays = forecast ? Integer.parseInt(forecastMatch.group(1)) : 7;
        if (forecastNoise > 0 && !forecast) {
            throw new IllegalArgumentException("ecocloud.forecastNoise needs a forecast info mode");
        }
        if ((forecast || cadsAdaptive) && dayMode.equals("fixed")) {
            throw new IllegalArgumentException("forecast modes and CADS_adaptive need -Decocloud.startDays=rotate");
        }
        String infoLabel = forecastNoise > 0
                ? String.format(Locale.US, "%s_noise%.2f", infoMode, forecastNoise)
                : infoMode;

        CarbonTrace baseTrace = new CarbonTrace(regionLabel, tracePath);
        boolean defaults = dayMode.equals("fixed") && infoMode.equals("oracle");

        List<Metrics> all = new ArrayList<>();
        List<RowContext> contexts = new ArrayList<>();
        for (long seed : seeds) {
            int startDay = dayMode.equals("rotate") ? 7 + (int) ((seed - 1) % 21) : 0;
            CarbonTrace actual = startDay == 0
                    ? baseTrace
                    : new ShiftedCarbonTrace(regionLabel, tracePath, startDay);
            CarbonTrace info = !forecast
                    ? actual
                    : new DiurnalForecastTrace(regionLabel, tracePath, startDay, historyDays,
                            forecastNoise, seed * 1_000_003L + 17L);

            int missingSlots = 0;
            for (int slot = 0; slot < HORIZON_SLOTS; slot++) {
                if (!actual.hasData((long) slot * SLOT_SECONDS)) missingSlots++;
            }
            RowContext context = new RowContext(startDay, infoLabel, missingSlots);

            List<WorkloadJob> workload = generateWorkload(seed);
            String experimentId = "R3_" + regionLabel + "_" + traceId + "_seed" + seed
                    + (defaults ? "" : "_day" + startDay + "_" + infoLabel);
            double[] slotCi = buildSlotTrace(info);

            List<Metrics> seedRows = new ArrayList<>();
            seedRows.add(runFcfs(experimentId, regionLabel, traceId, seed, actual, workload));
            seedRows.add(runWiesner(experimentId, regionLabel, traceId, seed, actual, info, workload));
            seedRows.add(runBahreini(experimentId, regionLabel, traceId, seed, actual, slotCi, workload));
            seedRows.add(runDeadlineCarbonShift(experimentId, regionLabel, traceId, seed, actual, info, workload));
            for (double theta : thetas) {
                seedRows.add(runCads(experimentId, regionLabel, traceId, seed, actual, info, workload,
                        theta, String.format(Locale.US, "CADS_theta%.0f", theta * 100)));
            }
            if (cadsAdaptive) {
                // Threshold fixed a priori from history only: the typical relative error of a
                // 7-day profile on the 7 days before the start day. No tuning parameter.
                double theta = new DiurnalForecastTrace(regionLabel, tracePath, startDay, 7)
                        .historicalMape();
                ADAPTIVE_THETA_ROWS.add(String.format(Locale.US, "%s,%d,%d,%.6f",
                        experimentId, seed, startDay, theta));
                seedRows.add(runCads(experimentId, regionLabel, traceId, seed, actual, info, workload,
                        theta, "CADS_adaptive"));
            }
            for (Metrics m : seedRows) {
                all.add(m);
                contexts.add(context);
            }
        }

        writeRaw(new File(outPath), all, contexts, tracePath);
        System.out.println("Wrote " + all.size() + " rows to " + outPath
                + " (trace=" + tracePath + ", startDays=" + dayMode + ", info=" + infoLabel
                + ", CADS thetas=" + thetas + ", CADS adaptive=" + cadsAdaptive
                + ", workload horizon/release/slack=" + HORIZON_SLOTS + "/" + RELEASE_SLOTS
                + "/" + SLACK_RANGE + ")");
    }

    /** Per-row run context written alongside the metrics. */
    private record RowContext(int startDay, String infoMode, int missingSlots) {
    }

    private static List<WorkloadJob> generateWorkload(long seed) {
        Random random = new Random(seed);
        List<WorkloadJob> jobs = new ArrayList<>();

        for (int id = 0; id < NUM_JOBS; id++) {
            int cpu = 1 + random.nextInt(10);
            int runtimeSlots = MIN_RUNTIME_SLOTS + random.nextInt(
                    MAX_RUNTIME_SLOTS - MIN_RUNTIME_SLOTS + 1);
            int releaseSlot = random.nextInt(RELEASE_SLOTS);
            int extraSlack = 2 + random.nextInt(SLACK_RANGE);
            int deadlineSlot = Math.min(
                    releaseSlot + runtimeSlots + extraSlack,
                    HORIZON_SLOTS - 1
            );
            if (deadlineSlot < releaseSlot + runtimeSlots) {
                deadlineSlot = releaseSlot + runtimeSlots;
            }
            jobs.add(new WorkloadJob(
                    id, cpu, runtimeSlots, releaseSlot, deadlineSlot));
        }

        return jobs;
    }

    private static double[] buildSlotTrace(CarbonTrace trace) {
        double[] ci = new double[HORIZON_SLOTS];
        for (int slot = 0; slot < HORIZON_SLOTS; slot++) {
            long seconds = (long) slot * SLOT_SECONDS;
            double value = trace.getCarbonIntensity(seconds);
            ci[slot] = value < 0 ? FALLBACK_CI : value;
        }
        return ci;
    }

    private static Metrics runFcfs(
            String experimentId,
            String regionLabel,
            String traceId,
            long seed,
            CarbonTrace trace,
            List<WorkloadJob> workload) {

        CloudSimPlus simulation = new CloudSimPlus();
        createDatacenter(simulation);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        broker.setShutdownWhenIdle(false);
        List<Vm> vms = createVms();
        broker.submitVmList(vms);
        broker.setVmMapper(cloudlet ->
                vms.get(((int) cloudlet.getId()) % NUM_SITES));

        List<JobRun> runs = new ArrayList<>();
        List<Cloudlet> cloudlets = new ArrayList<>();
        for (WorkloadJob job : workload) {
            JobRun run = new JobRun(job);
            run.plannedStart = job.arrivalSeconds();
            run.cloudlet = new CloudletSimple(
                    Math.round(job.runtimeSeconds() * MIPS), job.cpu());
            run.cloudlet.setId(job.id());
            run.cloudlet.setSubmissionDelay(job.arrivalSeconds());
            runs.add(run);
            cloudlets.add(run.cloudlet);
        }

        broker.submitCloudletList(cloudlets);
        simulation.start();

        return collectMetrics(experimentId, regionLabel, traceId, seed,
                "FCFS", workload, runs, broker.getCloudletFinishedList(),
                trace, 0.0, workload.size(), 0, 0.0);
    }

    /** Chooses a start time for a job that arrives at {@code nowSeconds}. */
    private interface StartChooser {
        double choose(WorkloadJob job, double nowSeconds);
    }

    private static Metrics runWiesner(
            String experimentId,
            String regionLabel,
            String traceId,
            long seed,
            CarbonTrace trace,
            CarbonTrace info,
            List<WorkloadJob> workload) {

        WiesnerAdHocScheduler scheduler =
                new WiesnerAdHocScheduler(info, WIESNER_MAX_DEFERRAL_SECONDS);
        return runArrivalTimeShift(experimentId, regionLabel, traceId, seed,
                "Wiesner_2021_AdHoc", trace, workload,
                (job, now) -> scheduler.selectStartTime(
                        Math.round(now), Math.round(job.runtimeSeconds())),
                null);
    }

    /**
     * CADS (O4 proposal): capacity-aware, deadline-bounded diurnal shifting
     * on the information trace (oracle or forecast). Every decision is logged
     * to the companion *_decisions.csv file.
     */
    private static Metrics runCads(
            String experimentId,
            String regionLabel,
            String traceId,
            long seed,
            CarbonTrace trace,
            CarbonTrace info,
            List<WorkloadJob> workload,
            double theta,
            String algorithm) {

        CapacityAwareDiurnalScheduler cads = new CapacityAwareDiurnalScheduler(
                info, NUM_SITES, SITE_CAPACITY, SLOT_SECONDS, CADS_LEDGER_SLOTS, theta);
        Map<Integer, Integer> siteById = new HashMap<>();

        return runArrivalTimeShift(experimentId, regionLabel, traceId, seed,
                algorithm, trace, workload,
                (job, now) -> {
                    CapacityAwareDiurnalScheduler.Decision d = cads.decide(
                            now, Math.round(job.runtimeSeconds()),
                            job.deadlineSeconds(), job.cpu());
                    siteById.put(job.id(), d.site());
                    DECISION_ROWS.add(String.format(Locale.US,
                            "%s,%d,%s,%d,%d,%.0f,%.0f,%d,%d,%.3f,%.3f,\"%s\"",
                            experimentId, seed, algorithm, job.id(), job.cpu(),
                            now, job.deadlineSeconds(), d.startSeconds(), d.site(),
                            d.ciEarliest(), d.ciChosen(), d.reason()));
                    return d.startSeconds();
                },
                siteById);
    }

    /**
     * Candidate O4 scheduler (not a published algorithm): the same
     * 30-minute carbon-window search as Wiesner AdHoc, but the deferral is
     * bounded by each job's own deadline instead of a fixed 6-hour cap.
     * Tie-break: earliest feasible start.
     */
    private static Metrics runDeadlineCarbonShift(
            String experimentId,
            String regionLabel,
            String traceId,
            long seed,
            CarbonTrace trace,
            CarbonTrace info,
            List<WorkloadJob> workload) {

        return runArrivalTimeShift(experimentId, regionLabel, traceId, seed,
                "Deadline_Carbon_Shift", trace, workload,
                (job, now) -> chooseDeadlineBoundedStart(info, job, now),
                null);
    }

    /**
     * Earliest-feasible, lowest-mean-carbon start in [now, deadline - runtime].
     * Falls back to the arrival time when no feasible window has trace data.
     */
    private static double chooseDeadlineBoundedStart(
            CarbonTrace trace,
            WorkloadJob job,
            double nowSeconds) {

        long duration = Math.round(job.runtimeSeconds());
        long earliest = (long) Math.ceil(nowSeconds / SLOT_SECONDS) * SLOT_SECONDS;
        long latest = Math.round(job.deadlineSeconds()) - duration;
        if (latest < earliest) return nowSeconds;

        double bestMean = Double.POSITIVE_INFINITY;
        long bestStart = Math.round(nowSeconds);
        for (long start = earliest; start <= latest; start += SLOT_SECONDS) {
            double mean = meanCarbonOverWindow(trace, start, duration);
            if (Double.isNaN(mean)) continue;
            if (mean < bestMean) {
                bestMean = mean;
                bestStart = start;
            }
        }
        return Double.isInfinite(bestMean) ? nowSeconds : bestStart;
    }

    /** Duration-weighted mean carbon over [start, start+duration); NaN if any hour is missing. */
    private static double meanCarbonOverWindow(
            CarbonTrace trace,
            long start,
            long duration) {

        long end = start + duration;
        double weighted = 0.0;
        long cursor = start;
        while (cursor < end) {
            long hourEnd = (cursor / 3600L + 1L) * 3600L;
            long segmentEnd = Math.min(hourEnd, end);
            if (!trace.hasData(cursor)) return Double.NaN;
            double ci = trace.getCarbonIntensity(cursor);
            if (ci < 0) return Double.NaN;
            weighted += ci * (segmentEnd - cursor);
            cursor = segmentEnd;
        }
        return weighted / duration;
    }

    private static Metrics runArrivalTimeShift(
            String experimentId,
            String regionLabel,
            String traceId,
            long seed,
            String algorithm,
            CarbonTrace trace,
            List<WorkloadJob> workload,
            StartChooser chooser,
            Map<Integer, Integer> siteById) {

        CloudSimPlus simulation = new CloudSimPlus();
        createDatacenter(simulation);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        broker.setShutdownWhenIdle(false);
        List<Vm> vms = createVms();
        broker.submitVmList(vms);
        broker.setVmMapper(cloudlet -> {
            int id = (int) cloudlet.getId();
            int site = siteById == null ? id % NUM_SITES : siteById.getOrDefault(id, id % NUM_SITES);
            return vms.get(site);
        });

        Map<Integer, JobRun> runById = new HashMap<>();
        double[] decisionNanos = {0.0};

        simulation.addOnSimulationStartListener(info -> {
            for (WorkloadJob job : workload) {
                JobRun run = new JobRun(job);
                runById.put(job.id(), run);
                run.cloudlet = new CloudletSimple(
                        Math.round(job.runtimeSeconds() * MIPS), job.cpu());
                run.cloudlet.setId(job.id());
                broker.schedule(job.arrivalSeconds(), JOB_ARRIVAL_EVENT, run);
            }
        });

        simulation.addOnEventProcessingListener((SimEvent event) -> {
            if (!(event.getData() instanceof JobRun run)) return;

            if (event.getTag() == JOB_ARRIVAL_EVENT) {
                double now = simulation.clock();
                long t0 = System.nanoTime();
                double planned = chooser.choose(run.job, now);
                decisionNanos[0] += System.nanoTime() - t0;
                planned = Math.max(planned, now);
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
        double meanDelay = runs.stream()
                .mapToDouble(r -> Math.max(0, r.plannedStart - r.job.arrivalSeconds()))
                .average().orElse(0.0);

        return collectMetrics(experimentId, regionLabel, traceId, seed,
                algorithm, workload, runs,
                broker.getCloudletFinishedList(), trace, meanDelay,
                workload.size(), 0, decisionNanos[0] / 1e6);
    }

    private static Metrics runBahreini(
            String experimentId,
            String regionLabel,
            String traceId,
            long seed,
            CarbonTrace trace,
            double[] slotCi,
            List<WorkloadJob> workload) {

        List<Job> jobs = new ArrayList<>();
        for (WorkloadJob job : workload) {
            jobs.add(new Job(job.id(), job.cpu(), job.runtimeSlots,
                    job.releaseSlot, job.deadlineSlot));
        }

        List<DataCenter> sites = new ArrayList<>();
        for (int site = 0; site < NUM_SITES; site++) {
            sites.add(new DataCenter(site, SITE_CAPACITY, slotCi));
        }

        BahreiniSrrApxScheduler scheduler = new BahreiniSrrApxScheduler(
                SLOT_MINUTES, SRR_SAMPLES, GAMMA, seed);
        long t0 = System.nanoTime();
        Result result = scheduler.schedule(jobs, sites);
        double decisionMs = (System.nanoTime() - t0) / 1e6;

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
        for (WorkloadJob job : workload) {
            Decision d = decisions.get(job.id());
            if (d == null) continue;

            JobRun run = new JobRun(job);
            run.plannedStart = d.startSlot * (double) SLOT_SECONDS;
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

        double meanDelay = runs.stream()
                .mapToDouble(r -> Math.max(0, r.plannedStart - r.job.arrivalSeconds()))
                .average().orElse(0.0);

        return collectMetrics(experimentId, regionLabel, traceId, seed,
                "Bahreini_SRR_APX", workload, runs,
                broker.getCloudletFinishedList(), trace, meanDelay,
                result.decisions().size(), result.failedJobs(), decisionMs);
    }

    private static Metrics collectMetrics(
            String experimentId,
            String regionLabel,
            String traceId,
            long seed,
            String algorithm,
            List<WorkloadJob> workload,
            List<JobRun> runs,
            List<Cloudlet> finished,
            CarbonTrace trace,
            double meanCarbonDelay,
            int scheduledJobs,
            int failedJobs,
            double schedulerRuntimeMs) {

        Map<Integer, WorkloadJob> jobById = new HashMap<>();
        for (WorkloadJob job : workload) jobById.put(job.id(), job);

        Map<Integer, JobRun> runById = new HashMap<>();
        for (JobRun run : runs) runById.put(run.job.id(), run);

        List<Double> waits = new ArrayList<>();
        List<Double> turnarounds = new ArrayList<>();
        int deadlineViolations = 0;
        int deadlineViolationsOver60s = 0;
        double energyWh = 0.0;
        Accumulator carbon = new Accumulator();

        for (Cloudlet cloudlet : finished) {
            int id = (int) cloudlet.getId();
            WorkloadJob job = jobById.get(id);
            JobRun run = runById.get(id);
            if (job == null || run == null) continue;

            double start = cloudlet.getStartTime();
            double finish = cloudlet.getFinishTime();
            waits.add(Math.max(0.0, start - job.arrivalSeconds()));
            turnarounds.add(Math.max(0.0, finish - job.arrivalSeconds()));

            double lateness = finish - job.deadlineSeconds();
            if (lateness > 1e-6) deadlineViolations++;
            if (lateness > MATERIAL_LATENESS_S) deadlineViolationsOver60s++;

            double execution = Math.max(0.0, finish - start);
            energyWh += ACTIVE_VM_POWER_WATTS * execution / 3600.0;
            double before = carbon.co2eG;
            integrateCarbon(trace, start, finish, ACTIVE_VM_POWER_WATTS, carbon);

            JOB_ROWS.add(String.format(Locale.US,
                    "%s,%s,%d,%s,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.6f",
                    experimentId, regionLabel, seed, algorithm, job.id(), job.cpu(),
                    job.runtimeSlots(), job.arrivalSeconds(), job.deadlineSeconds(),
                    run.plannedStart, start, finish, lateness, carbon.co2eG - before));
        }

        return new Metrics(
                experimentId,
                regionLabel,
                traceId,
                seed,
                algorithm,
                workload.size(),
                scheduledJobs,
                scheduledJobs,
                failedJobs,
                failedJobs,
                finished.size(),
                mean(waits),
                percentile95(waits),
                mean(turnarounds),
                percentile95(turnarounds),
                deadlineViolations,
                deadlineViolationsOver60s,
                energyWh,
                carbon.co2eG,
                carbon.fallbackCo2eG,
                meanCarbonDelay,
                schedulerRuntimeMs
        );
    }

    /**
     * Integrates power over hourly carbon intensity. Hours with no trace
     * record are charged FALLBACK_CI (same rule as the Review 2 runner) and
     * the amount is also accumulated into fallbackCo2eG.
     */
    private static void integrateCarbon(
            CarbonTrace trace,
            double startSeconds,
            double finishSeconds,
            double powerWatts,
            Accumulator acc) {

        if (finishSeconds <= startSeconds) return;

        double cursor = startSeconds;
        final double hour = 3600.0;

        while (cursor < finishSeconds - 1e-9) {
            double nextHour = (Math.floor(cursor / hour) + 1.0) * hour;
            double segmentEnd = Math.min(finishSeconds, nextHour);
            double duration = segmentEnd - cursor;
            long lookup = Math.max(0, (long) Math.floor(cursor));
            double ci = trace.getCarbonIntensity(lookup);
            double segmentG = powerWatts * duration / 3600.0 / 1000.0;
            if (ci < 0) {
                ci = FALLBACK_CI;
                acc.fallbackCo2eG += segmentG * ci;
            }
            acc.co2eG += segmentG * ci;
            cursor = segmentEnd;
        }
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
                Math.max(0, (int) Math.ceil(0.95 * copy.size()) - 1));
        return copy.get(index);
    }

    private static void writeRaw(
            File out,
            List<Metrics> metrics,
            List<RowContext> contexts,
            String tracePath) throws IOException {

        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }

        try (FileWriter writer = new FileWriter(out)) {
            writer.write("experiment_id,region,trace_id,trace_file,horizon_slots_missing_in_trace,"
                    + "seed,algorithm,submitted_jobs,admitted_jobs,scheduled_jobs,rejected_jobs,failed_jobs,completed_jobs,"
                    + "mean_waiting_s,p95_waiting_s,mean_turnaround_s,p95_turnaround_s,"
                    + "deadline_violations,energy_wh,co2e_g,fallback_co2e_g,"
                    + "co2e_per_completed_job_g,mean_carbon_delay_s,scheduler_runtime_ms,"
                    + "deadline_violations_over_60s,start_day,info_mode\n");
            for (int i = 0; i < metrics.size(); i++) {
                Metrics m = metrics.get(i);
                RowContext context = contexts.get(i);
                double perJob = m.completedJobs == 0 ? 0.0 : m.co2eG / m.completedJobs;
                writer.write(String.format(
                        Locale.US,
                        "%s,%s,%s,%s,%d,%d,%s,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.3f,%d,%d,%s\n",
                        m.experimentId(),
                        m.regionLabel(),
                        m.traceId(),
                        tracePath.replace('\\', '/'),
                        context.missingSlots(),
                        m.seed(),
                        m.algorithm(),
                        m.submittedJobs(),
                        m.admittedJobs(),
                        m.scheduledJobs(),
                        m.rejectedJobs(),
                        m.failedJobs(),
                        m.completedJobs(),
                        m.meanWaitingS(),
                        m.p95WaitingS(),
                        m.meanTurnaroundS(),
                        m.p95TurnaroundS(),
                        m.deadlineViolations(),
                        m.energyWh(),
                        m.co2eG(),
                        m.fallbackCo2eG(),
                        perJob,
                        m.meanCarbonDelayS(),
                        m.schedulerRuntimeMs(),
                        m.deadlineViolationsOver60s(),
                        context.startDay(),
                        context.infoMode()));
            }
        }

        if (!DECISION_ROWS.isEmpty()) {
            String decisionsPath = out.getPath().replaceFirst("\\.csv$", "") + "_decisions.csv";
            try (FileWriter writer = new FileWriter(decisionsPath)) {
                writer.write("experiment_id,seed,algorithm,job_id,cpu,arrival_s,deadline_s,"
                        + "chosen_start_s,site,expected_ci_earliest,expected_ci_chosen,reason\n");
                for (String row : DECISION_ROWS) writer.write(row + "\n");
            }
        }

        if (!ADAPTIVE_THETA_ROWS.isEmpty()) {
            String thetaPath = out.getPath().replaceFirst("\\.csv$", "") + "_adaptive_theta.csv";
            try (FileWriter writer = new FileWriter(thetaPath)) {
                writer.write("experiment_id,seed,start_day,theta\n");
                for (String row : ADAPTIVE_THETA_ROWS) writer.write(row + "\n");
            }
        }

        String jobsPath = out.getPath().replaceFirst("\\.csv$", "") + "_jobs.csv";
        try (FileWriter writer = new FileWriter(jobsPath)) {
            writer.write("experiment_id,region,seed,algorithm,job_id,cpu,runtime_slots,"
                    + "arrival_s,deadline_s,planned_start_s,start_s,finish_s,"
                    + "lateness_s,co2e_g\n");
            for (String row : JOB_ROWS) writer.write(row + "\n");
        }
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
