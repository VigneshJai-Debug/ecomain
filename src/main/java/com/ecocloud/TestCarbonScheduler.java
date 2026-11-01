package com.ecocloud;

import com.ecocloud.carbon.CarbonTrace;
import com.ecocloud.scheduler.CarbonAwareScheduler;

public class TestCarbonScheduler {

    public static void main(String[] args) {

        String gbPath =
                "data/uk/uk_carbon_hourly_clean.csv";

        String indiaPath =
                "data/india/india_carbon_hourly_utc_aligned.csv";

        try {

            System.out.println("==============================================");
            System.out.println("ECOCLOUD - CARBON AWARE SCHEDULER TEST");
            System.out.println("==============================================");

            CarbonTrace gb =
                    new CarbonTrace("GB", gbPath);

            CarbonTrace india =
                    new CarbonTrace("INDIA", indiaPath);

            CarbonAwareScheduler gbScheduler =
                    new CarbonAwareScheduler(gb);

            CarbonAwareScheduler indiaScheduler =
                    new CarbonAwareScheduler(india);

            /*
             * Test:
             *
             * Start = 00:00 on 19 Aug
             * Deadline = 12 hours later
             */

            double currentTime = 0.0;
            double deadline = 12 * 3600.0;

            System.out.println();
            System.out.println("TEST WINDOW");
            System.out.println("----------------------------------------------");
            System.out.println("Start simulation time : "
                    + currentTime);
            System.out.println("Deadline              : "
                    + deadline);

            // GB
            double gbSelected =
                    gbScheduler.findLowestCarbonTime(
                            currentTime,
                            deadline
                    );

            double gbSaving =
                    gbScheduler.calculateCarbonSaving(
                            currentTime,
                            gbSelected
                    );

            System.out.println();
            System.out.println("GB SCHEDULER");
            System.out.println("----------------------------------------------");

            System.out.println("Current timestamp : "
                    + gb.getTimestamp(currentTime));

            System.out.printf(
                    "Current CI       : %.2f gCO2e/kWh%n",
                    gb.getCarbonIntensity(currentTime)
            );

            System.out.println("Selected timestamp: "
                    + gb.getTimestamp(gbSelected));

            System.out.printf(
                    "Selected CI      : %.2f gCO2e/kWh%n",
                    gb.getCarbonIntensity(gbSelected)
            );

            System.out.printf(
                    "Carbon saving     : %.2f%%%n",
                    gbSaving
            );

            // India
            double indiaSelected =
                    indiaScheduler.findLowestCarbonTime(
                            currentTime,
                            deadline
                    );

            double indiaSaving =
                    indiaScheduler.calculateCarbonSaving(
                            currentTime,
                            indiaSelected
                    );

            System.out.println();
            System.out.println("INDIA SCHEDULER");
            System.out.println("----------------------------------------------");

            System.out.println("Current timestamp : "
                    + india.getTimestamp(currentTime));

            System.out.printf(
                    "Current CI       : %.2f gCO2/kWh%n",
                    india.getCarbonIntensity(currentTime)
            );

            System.out.println("Selected timestamp: "
                    + india.getTimestamp(indiaSelected));

            System.out.printf(
                    "Selected CI      : %.2f gCO2/kWh%n",
                    india.getCarbonIntensity(indiaSelected)
            );

            System.out.printf(
                    "Carbon saving     : %.2f%%%n",
                    indiaSaving
            );

            System.out.println();
            System.out.println("==============================================");
            System.out.println(
                    "STATUS: PASS - CARBON AWARE SCHEDULER WORKS"
            );
            System.out.println("==============================================");

        } catch (Exception e) {

            System.out.println();
            System.out.println(
                    "STATUS: FAIL - SCHEDULER TEST FAILED"
            );

            e.printStackTrace();
        }
    }
}