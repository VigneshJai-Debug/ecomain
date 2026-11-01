package com.ecocloud.scheduler;

import com.ecocloud.carbon.CarbonTrace;

/**
 * CADS - Capacity-Aware Diurnal Shifting (EcoCloud O4 proposal; not a
 * published algorithm).
 *
 * Motivation (from the O1 characterisation of the India trace):
 *   1. India's low-carbon period is a regular, solar-driven midday trough,
 *      so a day-ahead forecast built from the previous week's hourly profile
 *      is close to the realised trace. CADS is therefore designed to run on
 *      such a forecast rather than on perfect future knowledge.
 *   2. Because the trough is in the same hours every day, independent
 *      carbon-aware decisions converge on the same slots and queue. CADS
 *      keeps a per-site, per-slot CPU reservation ledger and only plans a job
 *      into slots that still have capacity.
 *   3. India's intraday swing is small relative to its mean, so some
 *      deferrals buy little carbon for a lot of waiting. An optional
 *      threshold theta runs a job at its earliest feasible start unless
 *      deferring is expected to save at least theta (relative).
 *
 * Decision rule for a job arriving at time now:
 *   candidates = (site, start) with start on the 30-min grid in
 *                [now, deadline - runtime] and enough reserved-free CPU in
 *                every slot of the execution window;
 *   best       = candidate with the lowest mean forecast intensity over its
 *                window (ties: earlier start, then lower peak load, then lower
 *                site index);
 *   early      = earliest-start candidate (ties: lower peak load, site);
 *   choose early if (ci(early) - ci(best)) / ci(early) < theta, else best.
 * If no candidate fits before the deadline, the job takes the earliest slot
 * with capacity after it (a known deadline miss, logged); if none exists in
 * the ledger, it starts at once on the least-loaded site (over-commit, logged).
 */
public class CapacityAwareDiurnalScheduler {

    public record Decision(
            long startSeconds,
            int site,
            String reason,
            double ciEarliest,
            double ciChosen) {
    }

    private final CarbonTrace signal;
    private final int capacityPerSite;
    private final long slotSeconds;
    private final double theta;
    private final int[][] reserved;

    public CapacityAwareDiurnalScheduler(
            CarbonTrace signal,
            int sites,
            int capacityPerSite,
            long slotSeconds,
            int ledgerSlots,
            double theta) {

        if (signal == null) throw new IllegalArgumentException("signal cannot be null");
        if (theta < 0) throw new IllegalArgumentException("theta must be >= 0");
        this.signal = signal;
        this.capacityPerSite = capacityPerSite;
        this.slotSeconds = slotSeconds;
        this.theta = theta;
        this.reserved = new int[sites][ledgerSlots];
    }

    public Decision decide(double nowSeconds, long durationSeconds, double deadlineSeconds, int cpu) {
        long earliest = (long) Math.ceil(nowSeconds / slotSeconds) * slotSeconds;
        long latest = Math.max(earliest, Math.round(deadlineSeconds) - durationSeconds);
        int slots = (int) Math.ceil(durationSeconds / (double) slotSeconds);

        long bestStart = -1, earlyStart = -1;
        int bestSite = -1, earlySite = -1, bestPeak = 0, earlyPeak = 0;
        double bestCi = Double.POSITIVE_INFINITY, earlyCi = Double.NaN;

        for (long start = earliest; start <= latest; start += slotSeconds) {
            for (int site = 0; site < reserved.length; site++) {
                int peak = peakLoad(site, start, slots);
                if (peak < 0 || peak + cpu > capacityPerSite) continue;
                double ci = meanSignal(start, durationSeconds);

                if (earlyStart < 0 || (start == earlyStart && peak < earlyPeak)) {
                    earlyStart = start;
                    earlySite = site;
                    earlyPeak = peak;
                    earlyCi = ci;
                }
                if (Double.isNaN(ci)) continue;
                boolean better = ci < bestCi - 1e-9
                        || (Math.abs(ci - bestCi) <= 1e-9
                            && (start < bestStart || (start == bestStart && peak < bestPeak)));
                if (better) {
                    bestCi = ci;
                    bestStart = start;
                    bestSite = site;
                    bestPeak = peak;
                }
            }
        }

        if (earlyStart < 0) {
            return overflow(earliest, latest, durationSeconds, slots, cpu);
        }

        long start;
        int site;
        String reason;
        if (bestStart < 0 || bestStart == earlyStart && bestSite == earlySite) {
            start = earlyStart;
            site = earlySite;
            reason = start == earliest ? "run-now: arrival slot is lowest-carbon feasible"
                                       : "run-earliest-with-capacity";
        } else {
            double saving = Double.isNaN(earlyCi) ? Double.POSITIVE_INFINITY
                                                  : (earlyCi - bestCi) / earlyCi;
            if (saving < theta) {
                start = earlyStart;
                site = earlySite;
                reason = String.format(java.util.Locale.US,
                        "run-early: expected saving %.1f%% < theta", saving * 100);
            } else {
                start = bestStart;
                site = bestSite;
                reason = String.format(java.util.Locale.US,
                        "defer: expected saving %.1f%%", saving * 100);
            }
        }

        reserve(site, start, slots, cpu);
        double chosenCi = start == bestStart && site == bestSite ? bestCi : earlyCi;
        return new Decision(start, site, reason, earlyCi, chosenCi);
    }

    private Decision overflow(long earliest, long latest, long durationSeconds, int slots, int cpu) {
        double earliestCi = meanSignal(earliest, durationSeconds);
        for (long start = latest + slotSeconds; ; start += slotSeconds) {
            boolean anyInLedger = false;
            for (int site = 0; site < reserved.length; site++) {
                int peak = peakLoad(site, start, slots);
                if (peak < 0) continue;
                anyInLedger = true;
                if (peak + cpu <= capacityPerSite) {
                    reserve(site, start, slots, cpu);
                    return new Decision(start, site,
                            "capacity-overflow: no capacity before deadline",
                            earliestCi, meanSignal(start, durationSeconds));
                }
            }
            if (!anyInLedger) break;
        }
        int site = 0;
        for (int s = 1; s < reserved.length; s++) {
            if (peakLoad(s, earliest, slots) < peakLoad(site, earliest, slots)) site = s;
        }
        reserve(site, earliest, slots, cpu);
        return new Decision(earliest, site, "over-commit: ledger exhausted",
                earliestCi, earliestCi);
    }

    /** Highest reserved CPU over the window, or -1 if the window leaves the ledger. */
    private int peakLoad(int site, long start, int slots) {
        int first = (int) (start / slotSeconds);
        if (first < 0 || first + slots > reserved[site].length) return -1;
        int peak = 0;
        for (int k = first; k < first + slots; k++) peak = Math.max(peak, reserved[site][k]);
        return peak;
    }

    private void reserve(int site, long start, int slots, int cpu) {
        int first = (int) (start / slotSeconds);
        for (int k = first; k < first + slots && k < reserved[site].length; k++) {
            if (k >= 0) reserved[site][k] += cpu;
        }
    }

    /** Duration-weighted mean signal over [start, start+duration); NaN if any hour is missing. */
    private double meanSignal(long start, long duration) {
        long end = start + duration;
        double weighted = 0.0;
        long cursor = start;
        while (cursor < end) {
            long segmentEnd = Math.min((cursor / 3600L + 1L) * 3600L, end);
            if (!signal.hasData(cursor)) return Double.NaN;
            double ci = signal.getCarbonIntensity(cursor);
            if (ci < 0) return Double.NaN;
            weighted += ci * (segmentEnd - cursor);
            cursor = segmentEnd;
        }
        return weighted / duration;
    }
}
