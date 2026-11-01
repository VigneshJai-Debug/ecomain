package com.ecocloud.scheduler;

import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.linear.LinearConstraint;
import org.apache.commons.math3.optim.linear.LinearConstraintSet;
import org.apache.commons.math3.optim.linear.LinearObjectiveFunction;
import org.apache.commons.math3.optim.linear.NoFeasibleSolutionException;
import org.apache.commons.math3.optim.linear.NonNegativeConstraint;
import org.apache.commons.math3.optim.linear.SimplexSolver;
import org.apache.commons.math3.optim.linear.Relationship;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Bahreini et al. (IEEE CLOUD 2023) - SRR-APX core.
 *
 * This class implements the core sample-based randomized-rounding procedure:
 *
 * 1. Solve the LP relaxation of the current-job placement/scheduling problem.
 * 2. For each sample, independently roll a (data-center, start-slot) choice
 *    for every job according to its fractional LP probabilities.
 * 3. Reject a job if adding it would exceed capacity in any slot.
 * 4. Select the sample with the fewest failed jobs; break ties using GRU.
 *
 * Paper-specific detail:
 * - Jobs are non-preemptive.
 * - A job is represented by required CPU (ri), runtime (li), and deadline (di).
 * - x(i,j,t)=1 means job i starts at time t on data center j.
 * - SRR-APX is the sampling improvement over RR-APX.
 *
 * Current EcoCloud integration:
 * - We use gamma=0 (no future-load reservation) because this initial
 *   implementation has no forecast future-load matrix.
 * - Carbon intensity is supplied per site and time slot.
 * - The CloudSim runner maps logical sites to separate VM resource pools.
 *
 * This is a faithful implementation of the SRR-APX core under the
 * stated input-instance restriction; it is not a byte-for-byte port
 * of the authors' Python/CPLEX implementation.
 */
public class BahreiniSrrApxScheduler {

    public static final class Job {
        private final int id;
        private final int requiredCpu;
        private final int runtimeSlots;
        private final int releaseSlot;
        private final int deadlineSlot;

        public Job(
                int id,
                int requiredCpu,
                int runtimeSlots,
                int releaseSlot,
                int deadlineSlot) {

            if (requiredCpu <= 0) {
                throw new IllegalArgumentException("requiredCpu must be > 0");
            }
            if (runtimeSlots <= 0) {
                throw new IllegalArgumentException("runtimeSlots must be > 0");
            }
            if (releaseSlot < 0) {
                throw new IllegalArgumentException("releaseSlot must be >= 0");
            }
            if (deadlineSlot < releaseSlot + runtimeSlots) {
                throw new IllegalArgumentException(
                        "deadlineSlot must allow the full non-preemptive runtime");
            }

            this.id = id;
            this.requiredCpu = requiredCpu;
            this.runtimeSlots = runtimeSlots;
            this.releaseSlot = releaseSlot;
            this.deadlineSlot = deadlineSlot;
        }

        public int id() {
            return id;
        }

        public int requiredCpu() {
            return requiredCpu;
        }

        public int runtimeSlots() {
            return runtimeSlots;
        }

        public int releaseSlot() {
            return releaseSlot;
        }

        public int deadlineSlot() {
            return deadlineSlot;
        }
    }

    public static final class DataCenter {
        private final int id;
        private final int capacity;

        /**
         * Carbon intensity in gCO2e/kWh for every horizon slot.
         */
        private final double[] carbonIntensity;

        public DataCenter(int id, int capacity, double[] carbonIntensity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be > 0");
            }
            if (carbonIntensity == null || carbonIntensity.length == 0) {
                throw new IllegalArgumentException("carbonIntensity must not be empty");
            }

            this.id = id;
            this.capacity = capacity;
            this.carbonIntensity = carbonIntensity.clone();
        }

        public int id() {
            return id;
        }

        public int capacity() {
            return capacity;
        }

        public double carbonIntensity(int slot) {
            if (slot < 0 || slot >= carbonIntensity.length) {
                throw new IllegalArgumentException(
                        "slot outside carbon-intensity horizon: " + slot);
            }

            return carbonIntensity[slot];
        }

        public int horizonSlots() {
            return carbonIntensity.length;
        }
    }

    public static final class Decision {
        public final int jobId;
        public final int dataCenterId;
        public final int startSlot;
        public final int runtimeSlots;
        public final int requiredCpu;

        private Decision(
                int jobId,
                int dataCenterId,
                int startSlot,
                int runtimeSlots,
                int requiredCpu) {

            this.jobId = jobId;
            this.dataCenterId = dataCenterId;
            this.startSlot = startSlot;
            this.runtimeSlots = runtimeSlots;
            this.requiredCpu = requiredCpu;
        }

        public int finishSlotExclusive() {
            return startSlot + runtimeSlots;
        }
    }

    public static final class Result {
        private final List<Decision> decisions;
        private final List<Integer> failedJobIds;
        private final double gru;
        private final int samples;

        private Result(
                List<Decision> decisions,
                List<Integer> failedJobIds,
                double gru,
                int samples) {

            this.decisions = List.copyOf(decisions);
            this.failedJobIds = List.copyOf(failedJobIds);
            this.gru = gru;
            this.samples = samples;
        }

        public List<Decision> decisions() {
            return decisions;
        }

        public List<Integer> failedJobIds() {
            return failedJobIds;
        }

        public double gru() {
            return gru;
        }

        public int samples() {
            return samples;
        }

        public int failedJobs() {
            return failedJobIds.size();
        }
    }

    private record Variable(
            int jobIndex,
            int dataCenterIndex,
            int startSlot) {
    }

    private static final double EPS = 1e-9;

    private final int slotMinutes;
    private final int samples;
    private final double gamma;
    private final Random random;

    public BahreiniSrrApxScheduler(
            int slotMinutes,
            int samples,
            double gamma,
            long randomSeed) {

        if (slotMinutes <= 0) {
            throw new IllegalArgumentException("slotMinutes must be > 0");
        }
        if (samples <= 0) {
            throw new IllegalArgumentException("samples must be > 0");
        }
        if (gamma < 0) {
            throw new IllegalArgumentException("gamma must be >= 0");
        }

        this.slotMinutes = slotMinutes;
        this.samples = samples;
        this.gamma = gamma;
        this.random = new Random(randomSeed);
    }

    public int slotMinutes() {
        return slotMinutes;
    }

    public int samples() {
        return samples;
    }

    public double gamma() {
        return gamma;
    }

    /**
     * Solves LP relaxation and applies standard SRR-APX sampling.
     *
     * The initial EcoCloud instance has no future-load forecast, so gamma
     * has no additional z-variable contribution. Setting gamma=0 is the
     * explicitly supported no-future-reservation variant used in the
     * paper's large-scale experiments.
     */
    public Result schedule(
            List<Job> jobs,
            List<DataCenter> dataCenters) {
        return schedule(jobs, dataCenters, null, 0.0);
    }

    /**
     * Solves LP relaxation with original objectives and applies forecast-guided Gibbs sampling.
     *
     * @param jobs list of workload jobs
     * @param dataCenters list of datacenters with their baseline carbon intensities
     * @param guidanceCarbonIntensity guidance carbon intensities per DC and slot (forecast or oracle)
     * @param theta Gibbs sampling intensity parameter (theta=0 recovers original SRR-APX)
     */
    public Result schedule(
            List<Job> jobs,
            List<DataCenter> dataCenters,
            double[][] guidanceCarbonIntensity,
            double theta) {

        if (jobs == null || jobs.isEmpty()) {
            return new Result(List.of(), List.of(), 0.0, samples);
        }
        if (dataCenters == null || dataCenters.isEmpty()) {
            throw new IllegalArgumentException("At least one data center is required");
        }

        final int horizonSlots = dataCenters.stream()
                .mapToInt(DataCenter::horizonSlots)
                .min()
                .orElseThrow();

        List<Variable> variables = buildVariables(jobs, dataCenters, horizonSlots);

        if (variables.isEmpty()) {
            List<Integer> failed = jobs.stream().map(Job::id).toList();
            return new Result(List.of(), failed, 0.0, samples);
        }

        // LP relaxation is strictly UNCHANGED: solved using baseline dataCenter carbon intensities
        double[] fractional = solveLpRelaxation(
                jobs,
                dataCenters,
                variables,
                horizonSlots
        );

        return sampleSolutions(
                jobs,
                dataCenters,
                variables,
                fractional,
                horizonSlots,
                guidanceCarbonIntensity,
                theta
        );
    }

    private List<Variable> buildVariables(
            List<Job> jobs,
            List<DataCenter> dataCenters,
            int horizonSlots) {

        List<Variable> variables = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);

            int latestStart =
                    Math.min(
                            job.deadlineSlot() - job.runtimeSlots(),
                            horizonSlots - job.runtimeSlots()
                    );

            for (int dcIndex = 0; dcIndex < dataCenters.size(); dcIndex++) {
                if (job.requiredCpu() > dataCenters.get(dcIndex).capacity()) {
                    continue;
                }

                for (int start = job.releaseSlot();
                     start <= latestStart;
                     start++) {

                    variables.add(new Variable(i, dcIndex, start));
                }
            }
        }

        return variables;
    }

    private double[] solveLpRelaxation(
            List<Job> jobs,
            List<DataCenter> dataCenters,
            List<Variable> variables,
            int horizonSlots) {

        int n = variables.size();

        // Commons Math solves minimization, so use negative GRU coefficient.
        double[] objective = new double[n];

        for (int v = 0; v < n; v++) {
            Variable variable = variables.get(v);
            Job job = jobs.get(variable.jobIndex());
            DataCenter dc = dataCenters.get(variable.dataCenterIndex());

            double gruContribution = 0.0;

            for (int slot = variable.startSlot();
                 slot < variable.startSlot() + job.runtimeSlots();
                 slot++) {

                double ci = Math.max(dc.carbonIntensity(slot), 1e-6);
                gruContribution +=
                        job.requiredCpu() / ci;
            }

            objective[v] = -gruContribution;
        }

        List<LinearConstraint> constraints = new ArrayList<>();

        // Each job can be placed at most once.
        for (int i = 0; i < jobs.size(); i++) {
            double[] coefficients = new double[n];

            for (int v = 0; v < n; v++) {
                if (variables.get(v).jobIndex() == i) {
                    coefficients[v] = 1.0;
                }
            }

            constraints.add(
                    new LinearConstraint(
                            coefficients,
                            Relationship.LEQ,
                            1.0
                    )
            );
        }

        // Capacity constraints for every site and slot.
        for (int dcIndex = 0; dcIndex < dataCenters.size(); dcIndex++) {
            DataCenter dc = dataCenters.get(dcIndex);

            for (int slot = 0; slot < horizonSlots; slot++) {
                double[] coefficients = new double[n];

                for (int v = 0; v < n; v++) {
                    Variable variable = variables.get(v);

                    if (variable.dataCenterIndex() != dcIndex) {
                        continue;
                    }

                    Job job = jobs.get(variable.jobIndex());

                    if (slot >= variable.startSlot()
                            && slot < variable.startSlot() + job.runtimeSlots()) {

                        coefficients[v] = job.requiredCpu();
                    }
                }

                constraints.add(
                        new LinearConstraint(
                                coefficients,
                                Relationship.LEQ,
                                dc.capacity()
                        )
                );
            }
        }

        // Explicit upper bound x <= 1 for LP relaxation variables.
        for (int v = 0; v < n; v++) {
            double[] coefficients = new double[n];
            coefficients[v] = 1.0;

            constraints.add(
                    new LinearConstraint(
                            coefficients,
                            Relationship.LEQ,
                            1.0
                    )
            );
        }

        SimplexSolver solver = new SimplexSolver(1e-10, 10, 1e-10);

        try {
            PointValuePair solution =
                    solver.optimize(
                            new MaxIter(10000),
                            new LinearObjectiveFunction(objective, 0.0),
                            new LinearConstraintSet(constraints),
                            GoalType.MINIMIZE,
                            new NonNegativeConstraint(true)
                    );

            if (solution == null || solution.getPoint() == null) {
                throw new IllegalStateException("LP solver returned no solution");
            }

            return solution.getPoint();

        } catch (NoFeasibleSolutionException ex) {
            throw new IllegalStateException(
                    "CJPS LP relaxation was infeasible for the current input instance",
                    ex
            );
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Failed to solve CJPS LP relaxation",
                    ex
            );
        }
    }

    private Result sampleSolutions(
            List<Job> jobs,
            List<DataCenter> dataCenters,
            List<Variable> variables,
            double[] fractional,
            int horizonSlots,
            double[][] guidanceCarbonIntensity,
            double theta) {

        int bestFailures = Integer.MAX_VALUE;
        double bestGru = Double.NEGATIVE_INFINITY;
        List<Decision> bestDecisions = List.of();
        List<Integer> bestFailed = List.of();

        final boolean useGibbs = (guidanceCarbonIntensity != null && theta > 1e-9);
        final double sigmaTrace = 94.67; // Characteristic std dev of Indian carbon trace

        for (int sample = 0; sample < samples; sample++) {

            int[][] allocation = new int[dataCenters.size()][horizonSlots];

            List<Decision> decisions = new ArrayList<>();
            List<Integer> failed = new ArrayList<>();

            for (int jobIndex = 0; jobIndex < jobs.size(); jobIndex++) {
                Job job = jobs.get(jobIndex);

                List<Integer> candidateIndexes = new ArrayList<>();
                double probabilityMass = 0.0;

                for (int v = 0; v < variables.size(); v++) {
                    Variable variable = variables.get(v);

                    if (variable.jobIndex() != jobIndex) {
                        continue;
                    }

                    double p = clampProbability(fractional[v]);

                    if (p > EPS) {
                        candidateIndexes.add(v);
                        probabilityMass += p;
                    }
                }

                if (candidateIndexes.isEmpty() || probabilityMass <= EPS) {
                    failed.add(job.id());
                    continue;
                }

                int selectedVariableIndex;

                if (!useGibbs) {
                    // Exact original sampling implementation for theta = 0 / baseline
                    selectedVariableIndex = rollDice(
                            candidateIndexes,
                            fractional,
                            probabilityMass
                    );
                } else {
                    // Gibbs-tilted sampling: conditionally samples among LP-supported candidates
                    // proportional to Gibbs-tilted carbon weights (no artificial placement hurdle).
                    //
                    // 1. Compute multi-hour execution-window average guidance carbon for each candidate
                    double minMeanC = Double.POSITIVE_INFINITY;
                    double[] candidateMeanC = new double[candidateIndexes.size()];

                    for (int k = 0; k < candidateIndexes.size(); k++) {
                        int v = candidateIndexes.get(k);
                        Variable var = variables.get(v);
                        double sumC = 0.0;
                        int dcIdx = var.dataCenterIndex();
                        for (int slot = var.startSlot(); slot < var.startSlot() + job.runtimeSlots(); slot++) {
                            sumC += guidanceCarbonIntensity[dcIdx][slot];
                        }
                        double meanC = sumC / job.runtimeSlots();
                        candidateMeanC[k] = meanC;
                        if (meanC < minMeanC) {
                            minMeanC = meanC;
                        }
                    }

                    // 3. Gibbs weights: w_v = x*_v * exp( -theta * (meanC_v - minMeanC) / sigmaTrace )
                    double[] weights = new double[candidateIndexes.size()];
                    double totalWeight = 0.0;

                    for (int k = 0; k < candidateIndexes.size(); k++) {
                        int v = candidateIndexes.get(k);
                        double x_v = clampProbability(fractional[v]);
                        double w = x_v * Math.exp(-theta * (candidateMeanC[k] - minMeanC) / sigmaTrace);
                        weights[k] = w;
                        totalWeight += w;
                    }

                    if (totalWeight <= 1e-12) {
                        selectedVariableIndex = rollDice(candidateIndexes, fractional, probabilityMass);
                    } else {
                        double draw = random.nextDouble() * totalWeight;
                        double cumulative = 0.0;
                        selectedVariableIndex = candidateIndexes.get(candidateIndexes.size() - 1);
                        for (int k = 0; k < candidateIndexes.size(); k++) {
                            cumulative += weights[k];
                            if (draw <= cumulative) {
                                selectedVariableIndex = candidateIndexes.get(k);
                                break;
                            }
                        }
                    }
                }

                Variable selected = variables.get(selectedVariableIndex);
                DataCenter dc = dataCenters.get(selected.dataCenterIndex());

                // Note: Restricting candidates to LP support guarantees individual release and deadline
                // validity, but does NOT guarantee collective capacity feasibility. The capacity check below
                // strictly enforces space-shared resource constraints across concurrently allocated jobs.
                boolean violated = false;

                for (int slot = selected.startSlot();
                     slot < selected.startSlot() + job.runtimeSlots();
                     slot++) {

                    if (allocation[selected.dataCenterIndex()][slot]
                            + job.requiredCpu()
                            > dc.capacity()) {

                        violated = true;
                        break;
                    }
                }

                if (violated) {
                    failed.add(job.id());
                    continue;
                }

                for (int slot = selected.startSlot();
                     slot < selected.startSlot() + job.runtimeSlots();
                     slot++) {

                    allocation[selected.dataCenterIndex()][slot]
                            += job.requiredCpu();
                }

                decisions.add(
                        new Decision(
                                job.id(),
                                dc.id(),
                                selected.startSlot(),
                                job.runtimeSlots(),
                                job.requiredCpu()
                        )
                );
            }

            double gru = calculateGru(
                    decisions,
                    jobs,
                    dataCenters
            );

            // SRR-APX selection rule: minimum failures, then maximum GRU (UNCHANGED).
            if (failed.size() < bestFailures
                    || (failed.size() == bestFailures && gru > bestGru)) {

                bestFailures = failed.size();
                bestGru = gru;
                bestDecisions = new ArrayList<>(decisions);
                bestFailed = new ArrayList<>(failed);
            }
        }

        if (bestGru == Double.NEGATIVE_INFINITY) {
            bestGru = 0.0;
        }

        return new Result(
                bestDecisions,
                bestFailed,
                bestGru,
                samples
        );
    }

    private int rollDice(
            List<Integer> candidateIndexes,
            double[] fractional,
            double probabilityMass) {

        double draw = random.nextDouble() * probabilityMass;
        double cumulative = 0.0;

        for (int index : candidateIndexes) {
            cumulative += clampProbability(fractional[index]);

            if (draw <= cumulative) {
                return index;
            }
        }

        return candidateIndexes.get(candidateIndexes.size() - 1);
    }

    private double calculateGru(
            List<Decision> decisions,
            List<Job> jobs,
            List<DataCenter> dataCenters) {

        double gru = 0.0;

        for (Decision decision : decisions) {
            Job job = jobs.stream()
                    .filter(j -> j.id() == decision.jobId)
                    .findFirst()
                    .orElseThrow();

            DataCenter dc = dataCenters.stream()
                    .filter(d -> d.id() == decision.dataCenterId)
                    .findFirst()
                    .orElseThrow();

            for (int slot = decision.startSlot;
                 slot < decision.startSlot + decision.runtimeSlots;
                 slot++) {

                double ci = Math.max(dc.carbonIntensity(slot), 1e-6);
                gru += job.requiredCpu() / ci;
            }
        }

        return gru;
    }

    private static double clampProbability(double value) {
        if (Double.isNaN(value) || value < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }

    public String describe() {
        return String.format(
                Locale.US,
                "Bahreini SRR-APX | slot=%d min | samples=%d | gamma=%.2f",
                slotMinutes,
                samples,
                gamma
        );
    }
}
