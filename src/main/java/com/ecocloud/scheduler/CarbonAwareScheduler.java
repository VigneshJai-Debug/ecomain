package com.ecocloud.scheduler;

import com.ecocloud.carbon.CarbonTrace;

public class CarbonAwareScheduler {

    private final CarbonTrace carbonTrace;

    public CarbonAwareScheduler(CarbonTrace carbonTrace) {
        this.carbonTrace = carbonTrace;
    }

    /**
     * Finds the lowest-carbon execution hour between
     * the current simulation time and the job deadline.
     *
     * @param currentTimeSeconds current simulation time
     * @param deadlineSeconds job deadline in simulation seconds
     * @return selected execution time in simulation seconds
     */
    public double findLowestCarbonTime(
            double currentTimeSeconds,
            double deadlineSeconds) {

        if (deadlineSeconds < currentTimeSeconds) {
            throw new IllegalArgumentException(
                    "Deadline cannot be before current time."
            );
        }

        double bestTime = currentTimeSeconds;
        double bestCarbon = Double.MAX_VALUE;

        // Check every hour between now and deadline.
        double firstHour =
                Math.ceil(currentTimeSeconds / 3600.0) * 3600.0;

        for (double time = firstHour;
             time <= deadlineSeconds;
             time += 3600.0) {

            if (!carbonTrace.hasData(time)) {
                continue;
            }

            double carbon =
                    carbonTrace.getCarbonIntensity(time);

            if (carbon < bestCarbon) {
                bestCarbon = carbon;
                bestTime = time;
            }
        }

        return bestTime;
    }

    /**
     * Returns the carbon intensity at a given simulation time.
     */
    public double getCarbonIntensity(double timeSeconds) {
        return carbonTrace.getCarbonIntensity(timeSeconds);
    }

    /**
     * Returns the carbon savings percentage obtained
     * by moving from the current time to the selected time.
     */
    public double calculateCarbonSaving(
            double currentTimeSeconds,
            double selectedTimeSeconds) {

        double currentCarbon =
                carbonTrace.getCarbonIntensity(currentTimeSeconds);

        double selectedCarbon =
                carbonTrace.getCarbonIntensity(selectedTimeSeconds);

        if (currentCarbon <= 0 || selectedCarbon < 0) {
            return 0.0;
        }

        return ((currentCarbon - selectedCarbon)
                / currentCarbon) * 100.0;
    }
}