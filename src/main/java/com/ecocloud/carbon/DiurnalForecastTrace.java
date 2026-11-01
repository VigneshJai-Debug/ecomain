package com.ecocloud.carbon;

import java.io.IOException;
import java.util.Random;

/**
 * Day-ahead carbon forecast with no access to future data.
 *
 * Issued at the start of trace day {@code startDay}: the forecast for any
 * simulation time is the mean of the ACTUAL intensity at the same hour of
 * day over the previous {@code historyDays} days (startDay-historyDays ..
 * startDay-1). Values on or after {@code startDay} are never read, so the
 * forecast cannot leak the realised trace. The same hourly profile is reused
 * for every day of the horizon.
 *
 * Optional robustness setting: {@code noiseSigma > 0} multiplies the forecast
 * for each simulation hour by (1 + noiseSigma * z), z ~ N(0,1) drawn from
 * {@code noiseSeed}, to emulate a less skilful forecaster.
 */
public class DiurnalForecastTrace extends CarbonTrace {

    private static final int NOISE_HOURS = 500;

    private final int startDay;
    private final int historyDays;
    private final double[] noiseFactor;

    public DiurnalForecastTrace(String region, String csvPath, int startDay, int historyDays)
            throws IOException {
        this(region, csvPath, startDay, historyDays, 0.0, 0L);
    }

    public DiurnalForecastTrace(
            String region,
            String csvPath,
            int startDay,
            int historyDays,
            double noiseSigma,
            long noiseSeed) throws IOException {
        super(region, csvPath);
        if (startDay < historyDays) {
            throw new IllegalArgumentException(
                    "startDay must be >= historyDays so the forecast has full history");
        }
        if (noiseSigma < 0) {
            throw new IllegalArgumentException("noiseSigma must be >= 0");
        }
        this.startDay = startDay;
        this.historyDays = historyDays;
        this.noiseFactor = new double[NOISE_HOURS];
        Random random = new Random(noiseSeed);
        for (int h = 0; h < NOISE_HOURS; h++) {
            double z = random.nextGaussian();
            noiseFactor[h] = noiseSigma == 0.0 ? 1.0 : Math.max(0.05, 1.0 + noiseSigma * z);
        }
    }

    @Override
    public double getCarbonIntensity(double simulationSeconds) {
        long hourIndex = (long) Math.floor(simulationSeconds / 3600.0);
        double profile = profileValue(hourIndex % 24);
        if (profile < 0) return -1.0;
        double factor = hourIndex >= 0 && hourIndex < NOISE_HOURS ? noiseFactor[(int) hourIndex] : 1.0;
        return profile * factor;
    }

    @Override
    public boolean hasData(double simulationSeconds) {
        return getCarbonIntensity(simulationSeconds) >= 0;
    }

    /**
     * Mean absolute percentage deviation of the history days from their own
     * hourly mean profile. Uses only days before {@code startDay}; it is a
     * leakage-free proxy for how wrong this kind of forecast tends to be.
     */
    public double historicalMape() {
        double sum = 0.0;
        int count = 0;
        for (int hour = 0; hour < 24; hour++) {
            double profile = profileValue(hour);
            if (profile < 0) continue;
            for (int k = 1; k <= historyDays; k++) {
                double actual = actualValue(startDay - k, hour);
                if (actual > 0) {
                    sum += Math.abs(profile - actual) / actual;
                    count++;
                }
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private double profileValue(long hourOfDay) {
        double sum = 0.0;
        int count = 0;
        for (int k = 1; k <= historyDays; k++) {
            double v = actualValue(startDay - k, hourOfDay);
            if (v >= 0) {
                sum += v;
                count++;
            }
        }
        return count == 0 ? -1.0 : sum / count;
    }

    private double actualValue(long day, long hourOfDay) {
        double t = (day * 24L + hourOfDay) * 3600.0;
        if (!super.hasData(t)) return -1.0;
        return super.getCarbonIntensity(t);
    }
}
