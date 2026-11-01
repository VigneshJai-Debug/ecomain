package com.ecocloud.scheduler;

import com.ecocloud.carbon.CarbonTrace;

/**
 * Wiesner et al. temporal workload shifting strategy.
 *
 * Non-interruptible ad-hoc workload:
 *
 * 1. Job arrives.
 * 2. Consider future execution opportunities.
 * 3. For every possible execution window, calculate
 *    the mean carbon intensity.
 * 4. Select the window with minimum mean carbon intensity.
 *
 * This class uses a 30-minute scheduling resolution,
 * matching the temporal resolution used by the original
 * Wiesner strategy, while the available project carbon
 * traces are hourly.
 *
 * The 6-hour maximum deferral is an experiment constraint.
 */
public class WiesnerAdHocScheduler {

    private final CarbonTrace carbonTrace;

    /**
     * Maximum amount of time a job may be deferred.
     */
    private final long maxDeferralSeconds;

    /**
     * Scheduling interval.
     *
     * Wiesner's original simulation uses 30-minute
     * temporal intervals.
     */
    private static final long SCHEDULING_INTERVAL_SECONDS =
            30 * 60L;


    /**
     * Constructor.
     *
     * @param carbonTrace carbon-intensity trace
     * @param maxDeferralSeconds maximum allowed deferral
     */
    public WiesnerAdHocScheduler(
            CarbonTrace carbonTrace,
            long maxDeferralSeconds) {

        if (carbonTrace == null) {
            throw new IllegalArgumentException(
                    "carbonTrace cannot be null"
            );
        }

        if (maxDeferralSeconds < 0) {
            throw new IllegalArgumentException(
                    "maxDeferralSeconds cannot be negative"
            );
        }

        this.carbonTrace =
                carbonTrace;

        this.maxDeferralSeconds =
                maxDeferralSeconds;
    }


    /**
     * Select the minimum-carbon execution start time.
     *
     * IMPORTANT:
     *
     * Both arguments are long so the experiment and scheduler
     * have one unambiguous interface.
     *
     * @param arrivalSeconds job arrival time
     * @param durationSeconds job execution duration
     *
     * @return selected absolute simulation start time in seconds
     */
    public long selectStartTime(
            long arrivalSeconds,
            long durationSeconds) {

        if (arrivalSeconds < 0) {
            arrivalSeconds = 0;
        }

        if (durationSeconds <= 0) {
            return arrivalSeconds;
        }


        /*
         * The job is allowed to execute immediately.
         *
         * Therefore the earliest possible start is the
         * arrival time rounded UP to the scheduling interval.
         */
        long earliestStart =
                roundUpToInterval(
                        arrivalSeconds
                );


        /*
         * The latest start must still allow the job to
         * finish within the 6-hour deferral window.
         */
        long latestStart =
                arrivalSeconds
                        + maxDeferralSeconds
                        - durationSeconds;


        /*
         * If the job itself is longer than the allowed
         * deferral window, execute at arrival.
         */
        if (latestStart < earliestStart) {
            return arrivalSeconds;
        }


        double bestMeanCarbon =
                Double.POSITIVE_INFINITY;

        long bestStart =
                arrivalSeconds;


        /*
         * Search every 30-minute execution opportunity.
         */
        for (
                long candidateStart = earliestStart;
                candidateStart <= latestStart;
                candidateStart += SCHEDULING_INTERVAL_SECONDS
        ) {

            double meanCarbon =
                    calculateWindowMeanCarbon(
                            candidateStart,
                            durationSeconds
                    );


            /*
             * Missing carbon data means that this
             * candidate cannot be evaluated safely.
             */
            if (Double.isNaN(meanCarbon)) {
                continue;
            }


            /*
             * Strictly lower carbon wins.
             *
             * If carbon is identical, the earlier
             * execution opportunity is retained.
             */
            if (meanCarbon < bestMeanCarbon) {

                bestMeanCarbon =
                        meanCarbon;

                bestStart =
                        candidateStart;
            }
        }


        /*
         * If no future candidate has valid carbon data,
         * fall back to the arrival time.
         */
        if (Double.isInfinite(bestMeanCarbon)) {
            return arrivalSeconds;
        }


        return bestStart;
    }


    /**
     * Calculate the mean carbon intensity over the complete
     * execution interval.
     *
     * The carbon trace is hourly, therefore the carbon
     * intensity of the hour containing a point in the
     * execution interval is used.
     *
     * For intervals crossing multiple hours, the values
     * are weighted according to the amount of execution
     * time spent in each hour.
     */
    private double calculateWindowMeanCarbon(
            long startSeconds,
            long durationSeconds) {

        long endSeconds =
                startSeconds + durationSeconds;


        if (durationSeconds <= 0) {
            return Double.NaN;
        }


        double weightedCarbon =
                0.0;

        long coveredSeconds =
                0;


        /*
         * Evaluate at one-hour boundaries.
         *
         * CarbonTrace maps simulation seconds to the
         * corresponding hourly carbon record.
         */
        long current =
                startSeconds;


        while (current < endSeconds) {

            long hourStart =
                    (current / 3600L) * 3600L;

            long hourEnd =
                    hourStart + 3600L;


            long segmentEnd =
                    Math.min(
                            hourEnd,
                            endSeconds
                    );


            long segmentSeconds =
                    segmentEnd - current;


            if (!carbonTrace.hasData(current)) {
                return Double.NaN;
            }


            double carbonIntensity =
                    carbonTrace.getCarbonIntensity(
                            current
                    );


            if (Double.isNaN(carbonIntensity) ||
                    carbonIntensity < 0) {

                return Double.NaN;
            }


            weightedCarbon +=
                    carbonIntensity
                            * segmentSeconds;


            coveredSeconds +=
                    segmentSeconds;


            current =
                    segmentEnd;
        }


        if (coveredSeconds == 0) {
            return Double.NaN;
        }


        return weightedCarbon
                / coveredSeconds;
    }


    /**
     * Round a timestamp UP to the next 30-minute
     * scheduling boundary.
     */
    private long roundUpToInterval(
            long seconds) {

        long remainder =
                seconds
                        % SCHEDULING_INTERVAL_SECONDS;


        if (remainder == 0) {
            return seconds;
        }


        return seconds
                + (
                    SCHEDULING_INTERVAL_SECONDS
                            - remainder
                );
    }
}