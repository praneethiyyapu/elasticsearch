/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.aggregatemetric.aggregations.metrics;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.ScoreMode;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.DoubleArray;
import org.elasticsearch.common.util.LongArray;
import org.elasticsearch.index.fielddata.SortedNumericDoubleValues;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.aggregations.metrics.CompensatedSum;
import org.elasticsearch.search.aggregations.metrics.InternalAvg;
import org.elasticsearch.search.aggregations.metrics.NumericMetricsAggregator;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.xpack.aggregatemetric.aggregations.support.AggregateMetricsValuesSource;
import org.elasticsearch.xpack.aggregatemetric.mapper.AggregateDoubleMetricFieldMapper.Metric;

import java.io.IOException;
import java.util.Map;

class AggregateMetricBackedAvgAggregator extends NumericMetricsAggregator.SingleValue {

    final AggregateMetricsValuesSource.AggregateDoubleMetric valuesSource;

    LongArray counts;
    DoubleArray sums;
    DoubleArray compensations;
    DocValueFormat format;

    AggregateMetricBackedAvgAggregator(
        String name,
        AggregateMetricsValuesSource.AggregateDoubleMetric valuesSource,
        DocValueFormat formatter,
        SearchContext context,
        Aggregator parent,
        Map<String, Object> metadata
    ) throws IOException {
        super(name, context, parent, metadata);
        this.valuesSource = valuesSource;
        this.format = formatter;
        if (valuesSource != null) {
            final BigArrays bigArrays = context.bigArrays();
            counts = bigArrays.newLongArray(1, true);
            sums = bigArrays.newDoubleArray(1, true);
            compensations = bigArrays.newDoubleArray(1, true);
        }
    }

    @Override
    public ScoreMode scoreMode() {
        return valuesSource != null && valuesSource.needsScores() ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES;
    }

    @Override
    public LeafBucketCollector getLeafCollector(LeafReaderContext ctx, final LeafBucketCollector sub) throws IOException {
        if (valuesSource == null) {
            return LeafBucketCollector.NO_OP_COLLECTOR;
        }
        final BigArrays bigArrays = context.bigArrays();
        // Retrieve aggregate values for metrics sum and value_count
        final SortedNumericDoubleValues aggregateSums = valuesSource.getAggregateMetricValues(ctx, Metric.sum);
        final SortedNumericDoubleValues aggregateValueCounts = valuesSource.getAggregateMetricValues(ctx, Metric.value_count);
        final CompensatedSum kahanSummation = new CompensatedSum(0, 0);
        return new LeafBucketCollectorBase(sub, sums) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                sums = bigArrays.grow(sums, bucket + 1);
                compensations = bigArrays.grow(compensations, bucket + 1);

                // Read aggregate values for sums
                if (aggregateSums.advanceExact(doc)) {
                    // Compute the sum of double values with Kahan summation algorithm which is more
                    // accurate than naive summation.
                    double sum = sums.get(bucket);
                    double compensation = compensations.get(bucket);

                    kahanSummation.reset(sum, compensation);
                    for (int i = 0; i < aggregateSums.docValueCount(); i++) {
                        double value = aggregateSums.nextValue();
                        kahanSummation.add(value);
                    }

                    sums.set(bucket, kahanSummation.value());
                    compensations.set(bucket, kahanSummation.delta());
                }

                counts = bigArrays.grow(counts, bucket + 1);
                // Read aggregate values for value_count
                if (aggregateValueCounts.advanceExact(doc)) {
                    for (int i = 0; i < aggregateValueCounts.docValueCount(); i++) {
                        double d = aggregateValueCounts.nextValue();
                        long value = Double.valueOf(d).longValue();
                        counts.increment(bucket, value);
                    }
                }
            }
        };
    }

    @Override
    public double metric(long owningBucketOrd) {
        if (valuesSource == null || owningBucketOrd >= sums.size()) {
            return Double.NaN;
        }
        return sums.get(owningBucketOrd) / counts.get(owningBucketOrd);
    }

    @Override
    public InternalAggregation buildAggregation(long bucket) {
        if (valuesSource == null || bucket >= sums.size()) {
            return buildEmptyAggregation();
        }
        return new InternalAvg(name, sums.get(bucket), counts.get(bucket), format, metadata());
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalAvg(name, 0.0, 0L, format, metadata());
    }

    @Override
    public void doClose() {
        Releasables.close(counts, sums, compensations);
    }

}