package com.ecocloud.carbon;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

public class CarbonTrace {

    /*
     * Common experiment start.
     * Both traces are aligned to this common UTC timestamp; India is resampled from IST onto UTC-hour boundaries.
     */
    private static final Instant EXPERIMENT_START =
            Instant.parse("2026-08-19T00:00:00Z");

    /*
     * Legacy naive timestamp format. Current simulation traces should
     * use explicit UTC timestamps (for example 2026-08-19T00:00:00Z).
     */
    private static final DateTimeFormatter LOCAL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /*
     * UK format:
     * 2026-08-19 00:00:00+00:00
     */
    private static final DateTimeFormatter OFFSET_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

    private final String region;

    private final Map<Long, Double> carbonData =
            new TreeMap<>();


    public CarbonTrace(String region, String csvPath)
            throws IOException {

        this.region = region;

        loadCSV(csvPath);

        System.out.println(
                "Loaded " + region +
                " carbon trace: " +
                carbonData.size() +
                " records"
        );
    }


    /*
     * ---------------------------------------------------------
     * LOAD CSV
     * ---------------------------------------------------------
     */
    private void loadCSV(String csvPath)
            throws IOException {

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader(csvPath))) {

            String header = reader.readLine();

            if (header == null) {
                throw new IOException(
                        "CSV file is empty: " + csvPath
                );
            }

            /*
             * Remove possible UTF-8 BOM.
             */
            header = header.replace("\uFEFF", "");

            String[] headers = header.split(",");

            int timestampIndex = -1;
            int carbonIndex = -1;

            for (int i = 0; i < headers.length; i++) {

                String column =
                        headers[i]
                                .trim()
                                .toLowerCase();

                /*
                 * Timestamp column
                 */
                if (column.equals("timestamp")
                        || column.equals("datetime")
                        || column.equals("date & time")) {

                    timestampIndex = i;
                }

                /*
                 * Carbon intensity column
                 */
                if (column.equals("carbon_intensity")
                        || column.equals(
                                "carbon_intensity_gco2_kwh")
                        || column.equals(
                                "carbon_intensity_gco2_per_kwh")
                        || column.equals(
                                "carbon intensity")) {

                    carbonIndex = i;
                }
            }

            if (timestampIndex == -1) {

                throw new IOException(
                        "Timestamp column not found in: "
                                + csvPath
                );
            }

            if (carbonIndex == -1) {

                throw new IOException(
                        "Carbon-intensity column not found in: "
                                + csvPath
                );
            }


            String line;

            while ((line = reader.readLine()) != null) {

                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] parts = line.split(",");

                if (parts.length <= Math.max(
                        timestampIndex,
                        carbonIndex)) {

                    continue;
                }

                String timestampString =
                        parts[timestampIndex].trim();

                String carbonString =
                        parts[carbonIndex].trim();

                try {

                    Instant timestamp =
                            parseTimestamp(timestampString);

                    double carbonIntensity =
                            Double.parseDouble(
                                    carbonString
                            );

                    /*
                     * Ignore data before experiment start.
                     */
                    if (timestamp.isBefore(
                            EXPERIMENT_START)) {

                        continue;
                    }

                    /*
                     * Convert real timestamp into
                     * simulation-hour index.
                     *
                     * 00:00 -> hour 0
                     * 01:00 -> hour 1
                     * 02:00 -> hour 2
                     */
                    long seconds =
                            timestamp.getEpochSecond()
                                    - EXPERIMENT_START
                                    .getEpochSecond();

                    long hour =
                            seconds / 3600L;

                    carbonData.put(
                            hour,
                            carbonIntensity
                    );

                } catch (Exception e) {

                    System.out.println(
                            "Skipping invalid CSV row: "
                                    + line
                    );
                }
            }
        }
    }


    /*
     * ---------------------------------------------------------
     * TIMESTAMP PARSER
     * ---------------------------------------------------------
     */
    private Instant parseTimestamp(
            String timestampString) {

        /*
         * CASE 1
         *
         * ISO format:
         *
         * 2026-08-19T00:00:00Z
         */
        if (timestampString.contains("T")) {

            return Instant.parse(
                    timestampString
            );
        }


        /*
         * CASE 2
         *
         * UK format:
         *
         * 2026-08-19 00:00:00+00:00
         *
         * IMPORTANT:
         * We explicitly use OFFSET_FORMAT here.
         */
        if (timestampString.contains("+")
                || timestampString.endsWith("Z")) {

            return OffsetDateTime.parse(
                    timestampString,
                    OFFSET_FORMAT
            ).toInstant();
        }


        /*
         * CASE 3
         *
         * India format:
         *
         * 2026-08-19 00:00:00
         *
         * Treat as UTC because the processed
         * experiment trace is aligned to UTC.
         */
        LocalDateTime localDateTime =
                LocalDateTime.parse(
                        timestampString,
                        LOCAL_FORMAT
                );

        // Naive source timestamps from CEA are IST wall-clock times.
        // Do not treat them as UTC. Production experiments use the
        // preprocessed UTC-aligned trace to avoid half-hour indexing errors.
        return localDateTime.atZone(java.time.ZoneId.of("Asia/Kolkata"))
                .toInstant();
    }


    /*
     * ---------------------------------------------------------
     * CARBON LOOKUP
     * ---------------------------------------------------------
     */
    public double getCarbonIntensity(
            double simulationSeconds) {

        long hour =
                (long) (simulationSeconds / 3600.0);

        Double value =
                carbonData.get(hour);

        if (value == null) {
            return -1.0;
        }

        return value;
    }


    /*
     * ---------------------------------------------------------
     * CHECK DATA AVAILABILITY
     * ---------------------------------------------------------
     */
    public boolean hasData(
            double simulationSeconds) {

        long hour =
                (long) (simulationSeconds / 3600.0);

        return carbonData.containsKey(hour);
    }


    /*
     * ---------------------------------------------------------
     * SIMULATION TIME -> REAL TIMESTAMP
     * ---------------------------------------------------------
     */
    public Instant getTimestamp(
            double simulationSeconds) {

        return EXPERIMENT_START.plusSeconds(
                (long) simulationSeconds
        );
    }


    /*
     * ---------------------------------------------------------
     * NUMBER OF RECORDS
     * ---------------------------------------------------------
     */
    public int size() {

        return carbonData.size();
    }


    /*
     * ---------------------------------------------------------
     * FIRST TIMESTAMP
     * ---------------------------------------------------------
     */
    public Instant getFirstTimestamp() {

        if (carbonData.isEmpty()) {
            return null;
        }

        long firstHour =
                carbonData.keySet()
                        .iterator()
                        .next();

        return EXPERIMENT_START.plusSeconds(
                firstHour * 3600L
        );
    }


    /*
     * ---------------------------------------------------------
     * LAST TIMESTAMP
     * ---------------------------------------------------------
     */
    public Instant getLastTimestamp() {

        if (carbonData.isEmpty()) {
            return null;
        }

        long lastHour =
                ((TreeMap<Long, Double>) carbonData)
                        .lastKey();

        return EXPERIMENT_START.plusSeconds(
                lastHour * 3600L
        );
    }


    /*
     * ---------------------------------------------------------
     * REGION
     * ---------------------------------------------------------
     */
    public String getRegion() {

        return region;
    }
}