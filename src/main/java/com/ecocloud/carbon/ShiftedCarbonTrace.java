package com.ecocloud.carbon;

import java.io.IOException;

/**
 * Actual carbon trace viewed from the start of trace day {@code startDay}.
 *
 * Simulation time 0 maps to trace hour {@code startDay * 24}, so repeated
 * runs can start on different calendar days of the same trace.
 */
public class ShiftedCarbonTrace extends CarbonTrace {

    private final double offsetSeconds;

    public ShiftedCarbonTrace(String region, String csvPath, int startDay)
            throws IOException {
        super(region, csvPath);
        this.offsetSeconds = startDay * 86400.0;
    }

    @Override
    public double getCarbonIntensity(double simulationSeconds) {
        return super.getCarbonIntensity(simulationSeconds + offsetSeconds);
    }

    @Override
    public boolean hasData(double simulationSeconds) {
        return super.hasData(simulationSeconds + offsetSeconds);
    }
}
