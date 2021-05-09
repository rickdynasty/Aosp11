/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.postprocessor;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.metrics.proto.MetricMeasurement.Measurements;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.math.Quantiles;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A metric aggregator that gives the min, max, mean, variance, standard deviation, total, count and
 * optionally percentiles for numeric metrics collected during multiple-iteration test runs,
 * treating them as doubles. Non-numeric metrics are ignored. Metrics that have a mix of numeric and
 * non-numeric values will also be ignored.
 *
 * <p>Note that count will only be present if a metric has all-numeric values; otherwise, it will be
 * absent along with all the other stats.
 *
 * <p>It parses metrics from single string as currently metrics are passed this way.
 */
@OptionClass(alias = "aggregate-post-processor")
public class AggregatePostProcessor extends BasePostProcessor {
    @Option(
            name = "report-percentiles",
            description =
                    "Additional percentiles of each metric to report, in integers in the 0 - 100 "
                            + "range. Can be repeated.")
    private Set<Integer> mPercentiles = new HashSet<>();

    private static final String STATS_KEY_MIN = "min";
    private static final String STATS_KEY_MAX = "max";
    private static final String STATS_KEY_MEAN = "mean";
    private static final String STATS_KEY_VAR = "var";
    private static final String STATS_KEY_STDEV = "stdev";
    private static final String STATS_KEY_MEDIAN = "median";
    private static final String STATS_KEY_TOTAL = "total";
    private static final String STATS_KEY_COUNT = "metric-count";
    private static final String STATS_KEY_PERCENTILE_PREFIX = "p";
    // Separator for final upload
    private static final String STATS_KEY_SEPARATOR = "-";

    // Actual percentiles to calculate, which includes the 50th percentile for median. Guava's
    // definition of percentiles agrees with the general interpretation of median. Initialized
    // lazily.
    private Set<Integer> mActualPercentiles = new HashSet<>();

    // Stores the test metrics for aggregation by test description.
    // TODO(b/118708851): Remove this workaround once AnTS is ready.
    private HashMap<String, ArrayListMultimap<String, Metric>> mStoredTestMetrics =
            new HashMap<String, ArrayListMultimap<String, Metric>>();

    @Override
    public Map<String, Metric.Builder> processTestMetricsAndLogs(
            TestDescription testDescription,
            HashMap<String, Metric> testMetrics,
            Map<String, LogFile> testLogs) {
        // TODO(b/118708851): Move this processing elsewhere once AnTS is ready.
        // Use the string representation of the test description to key the tests.
        String fullTestName = testDescription.toString();
        // Store result from the current test.
        if (!mStoredTestMetrics.containsKey(fullTestName)) {
            mStoredTestMetrics.put(fullTestName, ArrayListMultimap.create());
        }
        ArrayListMultimap<String, Metric> storedMetricsForThisTest =
                mStoredTestMetrics.get(fullTestName);
        for (Map.Entry<String, Metric> entry : testMetrics.entrySet()) {
            storedMetricsForThisTest.put(entry.getKey(), entry.getValue());
        }
        // Aggregate all data in iterations of this test.
        Map<String, Metric.Builder> aggregateMetrics = new HashMap<String, Metric.Builder>();
        for (String metricKey : storedMetricsForThisTest.keySet()) {
            List<Metric> metrics = storedMetricsForThisTest.get(metricKey);
            List<Measurements> measures =
                    metrics.stream().map(Metric::getMeasurements).collect(Collectors.toList());
            // Parse metrics into a list of SingleString values, concating lists in the process
            List<String> rawValues =
                    measures.stream()
                            .map(Measurements::getSingleString)
                            .map(
                                    m -> {
                                        // Split results; also deals with the case of empty results
                                        // in a certain run
                                        List<String> splitVals = Arrays.asList(m.split(",", 0));
                                        if (splitVals.size() == 1 && splitVals.get(0).isEmpty()) {
                                            return Collections.<String>emptyList();
                                        }
                                        return splitVals;
                                    })
                            .flatMap(Collection::stream)
                            .map(String::trim)
                            .collect(Collectors.toList());
            // Do not report empty metrics
            if (rawValues.isEmpty()) {
                continue;
            }
            if (isAllDoubleValues(rawValues)) {
                buildStats(metricKey, rawValues, aggregateMetrics);
            }
        }
        return aggregateMetrics;
    }

    @Override
    public Map<String, Metric.Builder> processRunMetricsAndLogs(
            HashMap<String, Metric> rawMetrics, Map<String, LogFile> runLogs) {
        // Aggregate the test run metrics which has comma separated values which can be
        // parsed to double values.
        Map<String, Metric.Builder> aggregateMetrics = new HashMap<String, Metric.Builder>();
        for (Map.Entry<String, Metric> entry : rawMetrics.entrySet()) {
            String values = entry.getValue().getMeasurements().getSingleString();
            List<String> splitVals = Arrays.asList(values.split(",", 0));
            // Build stats for keys with any values, even only one.
            if (isAllDoubleValues(splitVals)) {
                buildStats(entry.getKey(), splitVals, aggregateMetrics);
            }
        }
        return aggregateMetrics;
    }

    /**
     * Return true is all the values can be parsed to double value.
     * Otherwise return false.
     * @param rawValues list whose values are validated.
     * @return
     */
    private boolean isAllDoubleValues(List<String> rawValues) {
        return rawValues
                .stream()
                .allMatch(
                        val -> {
                            try {
                                Double.parseDouble(val);
                                return true;
                            } catch (NumberFormatException e) {
                                return false;
                            }
                        });
    }

    /**
     * Build stats for the given set of values and build the metrics using the metric key
     * and stats name and update the results in aggregated metrics.
     *
     * @param metricKey key to which the values correspond to.
     * @param values list of raw values.
     * @param aggregateMetrics where final metrics will be stored.
     */
    private void buildStats(String metricKey, List<String> values,
            Map<String, Metric.Builder> aggregateMetrics) {
        List<Double> doubleValues =
                values.stream().map(Double::parseDouble).collect(Collectors.toList());
        HashMap<String, Double> stats = getStats(doubleValues);
        for (String statKey : stats.keySet()) {
            Metric.Builder metricBuilder = Metric.newBuilder();
            metricBuilder
                    .getMeasurementsBuilder()
                    .setSingleString(String.format("%2.2f", stats.get(statKey)));
            aggregateMetrics.put(
                    String.join(STATS_KEY_SEPARATOR, metricKey, statKey),
                    metricBuilder);
        }
    }

    private HashMap<String, Double> getStats(Collection<Double> values) {
        HashMap<String, Double> stats = new HashMap<>();
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double count = (double) values.size();
        // The orElse situation should never happen.
        double mean =
                values.stream()
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElseThrow(IllegalStateException::new);
        double variance = values.stream().reduce(0.0, (a, b) -> a + Math.pow(b - mean, 2) / count);
        // Calculate percentiles. Median will be calculated as the 50th percentile.
        Map<Integer, Double> percentiles =
                Quantiles.percentiles().indexes(getActualPercentiles()).compute(values);
        double median = percentiles.get(50);

        stats.put(STATS_KEY_MIN, Collections.min(values));
        stats.put(STATS_KEY_MAX, Collections.max(values));
        stats.put(STATS_KEY_MEAN, mean);
        stats.put(STATS_KEY_VAR, variance);
        stats.put(STATS_KEY_STDEV, Math.sqrt(variance));
        stats.put(STATS_KEY_MEDIAN, median);
        stats.put(STATS_KEY_TOTAL, sum);
        stats.put(STATS_KEY_COUNT, count);
        percentiles
                .entrySet()
                .stream()
                .forEach(
                        e -> {
                            // If the percentile is 50, only include it if the user asks for it
                            // explicitly.
                            if (e.getKey() != 50 || mPercentiles.contains(50)) {
                                stats.put(
                                        STATS_KEY_PERCENTILE_PREFIX + e.getKey().toString(),
                                        e.getValue());
                            }
                        });
        return stats;
    }

    private Set<Integer> getActualPercentiles() {
        if (mActualPercentiles.isEmpty()) {
            // Empty means the set has not been initialized, since an initialized set will include
            // at least 50.
            mActualPercentiles.addAll(mPercentiles);
            mActualPercentiles.add(50);
        }
        return mActualPercentiles;
    }
}
