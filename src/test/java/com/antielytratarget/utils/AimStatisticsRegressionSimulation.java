package com.antielytratarget.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.lang.management.ManagementFactory;

public final class AimStatisticsRegressionSimulation {

    private AimStatisticsRegressionSimulation() {
    }

    public static void main(String[] args) {
        vanillaMouseQuantizationMustNotBeEvidence();
        oneSidedOutliersMustNotBeEvidence();
        sustainedDetectorInputMustRemainAvailable();
        deltaCalculationMustMatchLegacyBehavior();
        System.out.println("AimStatistics regression simulation passed.");
        if (Arrays.asList(args).contains("--allocation-benchmark")) compareAllocatedBytes();
    }

    private static void vanillaMouseQuantizationMustNotBeEvidence() {
        List<Float> legalYawDeltas = Arrays.asList(
                4.9500003f, 4.7999997f, 5.1000004f, 5.550001f, 5.25f,
                4.950001f, 4.5f, 4.200001f, 4.049999f, 3.5999985f,
                3.75f, 3.75f, 3.2999992f, 3.0f, 3.0f,
                2.8499985f, 2.4000015f, 2.4000015f, 2.25f, 2.699997f,
                2.550003f, 2.4000015f, 2.550003f, 2.25f, 2.25f
        );

        int legacyPatternValue = legacyScientificDuplicatePairCount(
                AimStatistics.getJiffDelta(legalYawDeltas, 5));
        require(legacyPatternValue == 20,
                "The captured vanilla lattice regression must reproduce legacy value=20");

        AimStatistics.BidirectionalOutlierEvidence evidence =
                AimStatistics.getBidirectionalOutlierEvidence(
                        legalYawDeltas, 2.0, 10.0, 55.0);
        require(!evidence.suspicious(),
                "Vanilla quantized mouse movement must not be suspicious evidence");
    }

    private static void oneSidedOutliersMustNotBeEvidence() {
        List<Float> samples = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            samples.add(1.0f + (i % 3) * 0.05f);
        }
        samples.add(20.0f);
        samples.add(22.0f);

        AimStatistics.BidirectionalOutlierEvidence evidence =
                AimStatistics.getBidirectionalOutlierEvidence(samples, 2.0, 10.0, 55.0);
        require(!evidence.suspicious(),
                "One-direction manual flicks must not satisfy bidirectional evidence");
    }

    private static void sustainedDetectorInputMustRemainAvailable() {
        List<Float> samples = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            samples.add((i & 1) == 0 ? 1.0f : -1.0f);
        }
        samples.add(20.0f);
        samples.add(-20.0f);

        AimStatistics.BidirectionalOutlierEvidence evidence =
                AimStatistics.getBidirectionalOutlierEvidence(samples, 2.0, 10.0, 55.0);
        require(evidence.suspicious(),
                "Strong opposite-direction statistical outliers should remain detectable");
        require(Math.abs(evidence.maxAbsoluteOutlier() - 20.0) < 1.0e-9,
                "Unexpected maximum outlier value");
    }

    private static int legacyScientificDuplicatePairCount(List<Float> values) {
        int pairs = 0;
        for (int i = 0; i < values.size(); i++) {
            float value = values.get(i);
            if (value == 0.0f || Math.abs(value) < 1.0e-6f) continue;
            if (!String.valueOf(value).contains("E")) continue;

            for (int other = 0; other < values.size(); other++) {
                if (other != i && value == values.get(other)) {
                    pairs++;
                }
            }
        }
        return pairs;
    }

    private static void deltaCalculationMustMatchLegacyBehavior() {
        List<List<? extends Number>> edgeCases = List.of(
                List.of(), List.of(1), List.of(1, -2, 3, -4),
                List.of(Float.MIN_VALUE, 1.0f, Float.MIN_VALUE, -0.0f),
                List.of(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.0f),
                List.of(Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE));
        for (List<? extends Number> data : edgeCases) {
            for (int depth = -2; depth <= 10; depth++) assertSameDeltas(data, depth);
        }
        Random random = new Random(20261029L);
        for (int iteration = 0; iteration < 2_000; iteration++) {
            List<Float> data = new ArrayList<>();
            for (int n = random.nextInt(50); n > 0; n--) {
                data.add(Float.intBitsToFloat(random.nextInt()));
            }
            assertSameDeltas(data, random.nextInt(12) - 2);
        }
        List<Float> original = new ArrayList<>(List.of(1.0f, -2.0f, 4.0f));
        List<Float> result = AimStatistics.getJiffDelta(original, 0);
        result.clear();
        require(original.equals(List.of(1.0f, -2.0f, 4.0f)),
                "Returned deltas must remain independent from caller-owned input");
        require(AimStatistics.getJiffDelta(List.of(), Integer.MAX_VALUE).isEmpty(),
                "An empty derivative must stop without iterating the remaining depth");
    }

    private static void assertSameDeltas(List<? extends Number> data, int depth) {
        List<Float> expected = legacyJiffDelta(data, depth);
        List<Float> actual = AimStatistics.getJiffDelta(data, depth);
        require(actual.size() == expected.size(), "Delta output length changed");
        for (int i = 0; i < expected.size(); i++) {
            require(Float.floatToIntBits(expected.get(i)) == Float.floatToIntBits(actual.get(i)),
                    "Delta output changed at depth=" + depth + ", index=" + i);
        }
    }

    private static List<Float> legacyJiffDelta(List<? extends Number> data, int depth) {
        List<Float> result = new ArrayList<>();
        for (Number n : data) result.add(n.floatValue());
        for (int i = 0; i < depth; i++) {
            List<Float> calculate = new ArrayList<>();
            float old = Float.MIN_VALUE;
            for (float n : result) {
                if (old == Float.MIN_VALUE) {
                    old = n;
                    continue;
                }
                calculate.add(Math.abs(Math.abs(n) - Math.abs(old)));
                old = n;
            }
            result = new ArrayList<>(calculate);
        }
        return result;
    }

    private static volatile Object allocationSink;

    private static void compareAllocatedBytes() {
        var platformBean = ManagementFactory.getThreadMXBean();
        if (!(platformBean instanceof com.sun.management.ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            System.out.println("Thread allocation measurement is unavailable on this JVM.");
            return;
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        List<Float> data = new ArrayList<>();
        for (int i = 0; i < 25; i++) data.add((float) Math.sin(i * 0.31) * 8.0f);
        for (int i = 0; i < 20_000; i++) {
            allocationSink = legacyJiffDelta(data, 5);
            allocationSink = AimStatistics.getJiffDelta(data, 5);
        }
        int iterations = 100_000;
        long thread = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < iterations; i++) allocationSink = legacyJiffDelta(data, 5);
        long legacyBytes = bean.getThreadAllocatedBytes(thread) - before;
        before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < iterations; i++) allocationSink = AimStatistics.getJiffDelta(data, 5);
        long currentBytes = bean.getThreadAllocatedBytes(thread) - before;
        System.out.printf(java.util.Locale.ROOT,
                "Delta allocation benchmark: legacy=%.1f B/op, current=%.1f B/op, samples=%d, depth=5, iterations=%d%n",
                legacyBytes / (double) iterations, currentBytes / (double) iterations, data.size(), iterations);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
