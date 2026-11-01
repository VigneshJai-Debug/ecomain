package com.ecocloud;

import com.ecocloud.carbon.CarbonTrace;

public class TestCarbonTrace {

    public static void main(String[] args) {

        String gbPath =
                "data/uk/uk_carbon_hourly_clean.csv";

        String indiaPath =
                "data/india/india_carbon_hourly_utc_aligned.csv";

        try {

            System.out.println("==============================================");
            System.out.println("ECOCLOUD - CARBON TRACE TEST");
            System.out.println("==============================================");

            // Load GB trace
            CarbonTrace gb =
                    new CarbonTrace("GB", gbPath);

            // Load India trace
            CarbonTrace india =
                    new CarbonTrace("INDIA", indiaPath);

            System.out.println();

            System.out.println("GB TRACE");
            System.out.println("----------------------------------------------");
            System.out.println("Records : " + gb.size());
            System.out.println("First   : " + gb.getFirstTimestamp());
            System.out.println("Last    : " + gb.getLastTimestamp());

            System.out.println();

            System.out.println("INDIA TRACE");
            System.out.println("----------------------------------------------");
            System.out.println("Records : " + india.size());
            System.out.println("First   : " + india.getFirstTimestamp());
            System.out.println("Last    : " + india.getLastTimestamp());

            // Test selected simulation hours.
            double[] testHours = {
                    0,      // Aug 19 00:00
                    3600,   // Aug 19 01:00
                    7200,   // Aug 19 02:00
                    86400   // Aug 20 00:00
            };

            System.out.println();
            System.out.println("SAMPLE CARBON LOOKUPS");
            System.out.println("----------------------------------------------");

            for (double seconds : testHours) {

                System.out.println(
                        "Simulation time : " +
                        seconds +
                        " seconds"
                );

                System.out.println(
                        "Timestamp        : " +
                        gb.getTimestamp(seconds)
                );

                System.out.printf(
                        "GB CI             : %.2f gCO2e/kWh%n",
                        gb.getCarbonIntensity(seconds)
                );

                System.out.printf(
                        "India CI          : %.2f gCO2/kWh%n",
                        india.getCarbonIntensity(seconds)
                );

                System.out.println();
            }

            // Count common valid hours.
            int common = 0;
            int indiaMissing = 0;

            // 696 hours = 29 days.
            for (int hour = 0; hour < 696; hour++) {

                double seconds =
                        hour * 3600.0;

                boolean gbAvailable =
                        gb.hasData(seconds);

                boolean indiaAvailable =
                        india.hasData(seconds);

                if (gbAvailable && indiaAvailable) {
                    common++;
                }

                if (!indiaAvailable) {
                    indiaMissing++;
                }
            }

            System.out.println("TRACE ALIGNMENT");
            System.out.println("----------------------------------------------");
            System.out.println("Expected hours : 696");
            System.out.println("Common valid   : " + common);
            System.out.println("India missing  : " + indiaMissing);

            System.out.println();

            if (gb.size() == 696 &&
                india.size() == 690 &&
                common == 690) {

                System.out.println(
                        "STATUS: PASS - BOTH CARBON TRACES LOADED"
                );

            } else {

                System.out.println(
                        "STATUS: CHECK - TRACE COUNTS DIFFER"
                );
            }

            System.out.println(
                    "=============================================="
            );

        } catch (Exception e) {

            System.out.println();
            System.out.println(
                    "STATUS: FAIL - COULD NOT LOAD CARBON TRACE"
            );

            e.printStackTrace();
        }
    }
}
