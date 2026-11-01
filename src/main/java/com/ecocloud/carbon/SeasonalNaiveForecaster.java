package com.ecocloud.carbon;

import java.util.HashMap;
import java.util.Map;

/**
 * Seasonal-Naive 24-Hour Carbon-Intensity Forecaster with Leak-Free Fallbacks.
 *
 * Forecast logic for target hour H (where H = simulationSeconds / 3600):
 *   Level 1: 24-hour lag: H - 24 (yesterday at same hour), if H - 24 >= 0 and observed.
 *   Level 2: 48-hour lag: H - 48 (two days ago at same hour), if H - 48 >= 0 and observed.
 *   Level 3: Diurnal Climatology: hour-of-day mean across available historical data.
 *   Level 4: Trace mean / penalty fallback.
 *
 * Strictly causal: Never queries any observation at or after the forecast origin.
 */
public class SeasonalNaiveForecaster {

    private final CarbonTrace trace;
    private final String region;
    private final long trainingCutoffHour;
    private final Map<Integer, Double> diurnalClimatology = new HashMap<>();
    private final double overallMean;

    /**
     * Default constructor: uses the pre-split training partition (first 18 days, hours 0 to 431).
     */
    public SeasonalNaiveForecaster(CarbonTrace trace, String region) {
        this(trace, region, 431L);
    }

    /**
     * Strictly causal constructor: computes diurnal climatology and overall mean
     * strictly from observations up to trainingCutoffHour. Never touches observations
     * beyond trainingCutoffHour.
     */
    public SeasonalNaiveForecaster(CarbonTrace trace, String region, long trainingCutoffHour) {
        this.trace = trace;
        this.region = region;
        this.trainingCutoffHour = trainingCutoffHour;

        // Compute diurnal hour-of-day means strictly within the historical training window [0, trainingCutoffHour]
        double totalSum = 0.0;
        int totalCount = 0;
        int[] counts = new int[24];
        double[] sums = new double[24];

        for (long h = 0; h <= trainingCutoffHour; h++) {
            long sec = h * 3600L;
            if (trace.hasData(sec)) {
                double val = trace.getCarbonIntensity(sec);
                if (val > 0) {
                    int hourOfDay = (int) (h % 24);
                    sums[hourOfDay] += val;
                    counts[hourOfDay]++;
                    totalSum += val;
                    totalCount++;
                }
            }
        }

        this.overallMean = totalCount > 0 ? totalSum / totalCount : 650.0;
        for (int hod = 0; hod < 24; hod++) {
            diurnalClimatology.put(hod, counts[hod] > 0 ? sums[hod] / counts[hod] : overallMean);
        }
    }

    /**
     * Generates the 24-hour seasonal naive point forecast for a given target simulation second,
     * conditioned on forecast origin originSeconds.
     */
    public double forecast(double targetSeconds, double originSeconds) {
        if (targetSeconds < originSeconds) {
            // Looking at past: if observed, return it
            return trace.getCarbonIntensity(targetSeconds);
        }

        long targetHour = (long) Math.floor(targetSeconds / 3600.0);
        long originHour = (long) Math.floor(originSeconds / 3600.0);

        // Level 1: 24-hour lag
        long lag24 = targetHour - 24;
        if (lag24 >= 0 && lag24 <= originHour) {
            long lag24Sec = lag24 * 3600L;
            if (trace.hasData(lag24Sec)) {
                double val = trace.getCarbonIntensity(lag24Sec);
                if (val > 0) return val;
            }
        }

        // Level 2: 48-hour lag
        long lag48 = targetHour - 48;
        if (lag48 >= 0 && lag48 <= originHour) {
            long lag48Sec = lag48 * 3600L;
            if (trace.hasData(lag48Sec)) {
                double val = trace.getCarbonIntensity(lag48Sec);
                if (val > 0) return val;
            }
        }

        // Level 3: Diurnal climatology
        int hourOfDay = (int) (Math.floorMod(targetHour, 24L));
        return diurnalClimatology.getOrDefault(hourOfDay, overallMean);
    }

    /**
     * Builds a forecast slot array of size horizonSlots for simulation starting at originSeconds.
     */
    public double[] buildForecastSlotArray(int horizonSlots, int slotMinutes, double originSeconds) {
        double[] forecast = new double[horizonSlots];
        long slotSeconds = slotMinutes * 60L;

        for (int slot = 0; slot < horizonSlots; slot++) {
            double targetSec = originSeconds + (slot * slotSeconds);
            forecast[slot] = forecast(targetSec, originSeconds);
        }
        return forecast;
    }

    /**
     * Calculates the execution-window average forecast carbon intensity.
     */
    public double calculateWindowAverage(double[] forecastSlots, int startSlot, int runtimeSlots) {
        if (runtimeSlots <= 0) return 0.0;
        double sum = 0.0;
        for (int s = startSlot; s < startSlot + runtimeSlots && s < forecastSlots.length; s++) {
            sum += forecastSlots[s];
        }
        return sum / runtimeSlots;
    }

    public double getDiurnalHourMean(int hourOfDay) {
        return diurnalClimatology.getOrDefault(hourOfDay, overallMean);
    }

    public double getOverallMean() {
        return overallMean;
    }
}
