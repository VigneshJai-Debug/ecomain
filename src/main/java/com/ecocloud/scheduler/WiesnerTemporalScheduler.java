package com.ecocloud.scheduler;

import com.ecocloud.carbon.CarbonTrace;

/**
 * Temporal carbon-aware workload shifting strategy
 * inspired by Wiesner et al. (2021):
 *
 * "Let's Wait Awhile: How Temporal Workload Shifting
 * Can Reduce Carbon Emissions in the Cloud"
 *
 * Strategy:
 * - Latency-sensitive jobs execute immediately.
 * - Deferrable jobs may be shifted within their deadline window.
 * - Select the lowest-carbon valid hourly execution opportunity.
 *
 * This is the temporal-shifting component only.
 * It does not perform geographic workload migration.
 */
public class WiesnerTemporalScheduler {

    private final CarbonTrace carbonTrace;

    public WiesnerTemporalScheduler(
            CarbonTrace carbonTrace) {

        this.carbonTrace = carbonTrace;
    }

    /**
     * Finds the lowest-carbon hourly execution opportunity
     * between the current time and the job deadline.
     *
     * @param arrivalTimeSeconds job arrival time
     * @param deadlineSeconds absolute job deadline
     * @return selected execution time in simulation seconds
     */
    public double findExecutionTime(
            double arrivalTimeSeconds,
            double deadlineSeconds) {

        if (deadlineSeconds < arrivalTimeSeconds) {
            throw new IllegalArgumentException(
                    "Deadline cannot be before arrival time."
            );
        }

        /*
         * Start by considering execution immediately.
         */
        double bestTime =
                arrivalTimeSeconds;

        double bestCarbon =
                getCarbonSafely(
                        arrivalTimeSeconds
                );

        /*
         * Examine hourly opportunities.
         *
         * We only consider actual carbon-trace timestamps.
         */
        double firstHour =
                Math.ceil(
                        arrivalTimeSeconds / 3600.0
                ) * 3600.0;

        for (
                double time = firstHour;
                time <= deadlineSeconds;
                time += 3600.0
        ) {

            /*
             * Missing India observations are skipped.
             */
            if (!carbonTrace.hasData(time)) {
                continue;
            }

            double carbon =
                    carbonTrace.getCarbonIntensity(
                            time
                    );

            /*
             * Lower carbon is preferred.
             */
            if (carbon < bestCarbon) {

                bestCarbon =
                        carbon;

                bestTime =
                        time;
            }
        }

        return bestTime;
    }

    /**
     * Returns carbon intensity while handling missing data.
     */
    private double getCarbonSafely(
            double timeSeconds) {

        if (!carbonTrace.hasData(timeSeconds)) {

            /*
             * If the exact arrival hour is missing,
             * search forward for the first valid hour
             * within the trace.
             */
            double firstHour =
                    Math.ceil(
                            timeSeconds / 3600.0
                    ) * 3600.0;

            for (
                    double time = firstHour;
                    time <= timeSeconds + 24 * 3600.0;
                    time += 3600.0
            ) {

                if (carbonTrace.hasData(time)) {

                    return carbonTrace
                            .getCarbonIntensity(time);
                }
            }

            return Double.MAX_VALUE;
        }

        return carbonTrace
                .getCarbonIntensity(
                        timeSeconds
                );
    }

    /**
     * Returns the carbon intensity at the selected time.
     */
    public double getCarbonIntensity(
            double timeSeconds) {

        return carbonTrace
                .getCarbonIntensity(
                        timeSeconds
                );
    }

    /**
     * Calculates the percentage carbon reduction
     * between immediate execution and the selected
     * lower-carbon execution opportunity.
     */
    public double calculateCarbonSaving(
            double immediateTimeSeconds,
            double selectedTimeSeconds) {

        if (!carbonTrace.hasData(
                immediateTimeSeconds)) {

            return 0.0;
        }

        if (!carbonTrace.hasData(
                selectedTimeSeconds)) {

            return 0.0;
        }

        double immediateCarbon =
                carbonTrace.getCarbonIntensity(
                        immediateTimeSeconds
                );

        double selectedCarbon =
                carbonTrace.getCarbonIntensity(
                        selectedTimeSeconds
                );

        if (immediateCarbon <= 0) {
            return 0.0;
        }

        return (
                (immediateCarbon - selectedCarbon)
                        / immediateCarbon
        ) * 100.0;
    }
}