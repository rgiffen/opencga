package org.opencb.opencga.storage.hadoop.variant.stats;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.mapreduce.Job;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.metadata.Aggregation;
import org.opencb.biodata.tools.variant.stats.AggregationUtils;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.opencga.storage.core.metadata.VariantStorageMetadataManager;
import org.opencb.opencga.storage.core.metadata.models.CohortMetadata;
import org.opencb.opencga.storage.core.metadata.models.StudyMetadata;
import org.opencb.opencga.storage.core.variant.VariantStorageEngine;
import org.opencb.opencga.storage.core.variant.stats.VariantStatisticsCalculator;
import org.opencb.opencga.storage.core.variant.stats.VariantStatsWrapper;
import org.opencb.opencga.storage.hadoop.variant.converters.stats.VariantStatsToHBaseConverter;
import org.opencb.opencga.storage.hadoop.variant.mr.VariantTableHelper;
import org.opencb.opencga.storage.hadoop.variant.metadata.HBaseVariantStorageMetadataDBAdaptorFactory;
import org.opencb.opencga.storage.hadoop.variant.mr.VariantMapper;
import org.opencb.opencga.storage.hadoop.variant.mr.VariantsTableMapReduceHelper;
import org.opencb.opencga.storage.hadoop.variant.search.HadoopVariantSearchIndexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Created on 14/11/17.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class VariantStatsMapper extends VariantMapper<ImmutableBytesWritable, Put> {

    public static final String COHORTS = "cohorts";
    public static final String TAGMAP_PREFIX = "tagmap.";

    private String study;
    private VariantStatisticsCalculator calculator;
    private Map<String, Set<String>> samples;
    private VariantTableHelper helper;
    private StudyMetadata studyMetadata;
    private VariantStatsToHBaseConverter converter;
    private final Logger logger = LoggerFactory.getLogger(VariantStatsMapper.class);


    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        helper = new VariantTableHelper(context.getConfiguration());

        try (VariantStorageMetadataManager metadataManager =
                     new VariantStorageMetadataManager(new HBaseVariantStorageMetadataDBAdaptorFactory(helper))) {
            studyMetadata = metadataManager.getStudyMetadata(helper.getStudyId());

            boolean overwrite = context.getConfiguration().getBoolean(VariantStorageEngine.Options.OVERWRITE_STATS.key(), false);
            calculator = new VariantStatisticsCalculator(overwrite);
            Properties tagmap = getAggregationMappingProperties(context.getConfiguration());
            calculator.setAggregationType(getAggregation(studyMetadata, context.getConfiguration()), tagmap);
            study = studyMetadata.getName();

            Collection<Integer> cohorts = getCohorts(context.getConfiguration());
            Map<String, Integer> cohortIds = new HashMap<>(cohorts.size());
            samples = new HashMap<>(cohorts.size());

            cohorts.forEach(cohortId -> {
                CohortMetadata cohortMetadata = metadataManager.getCohortMetadata(studyMetadata.getId(), cohortId);
                String cohort = cohortMetadata.getName();
                cohortIds.put(cohort, cohortId);

                Set<String> samplesInCohort = cohortMetadata.getSamples().stream()
                        .map(s -> metadataManager.getSampleName(studyMetadata.getId(), s))
                        .collect(Collectors.toSet());
                samples.put(cohort, samplesInCohort);
            });

            converter = new VariantStatsToHBaseConverter(helper, studyMetadata, cohortIds);
        }
    }

    @Override
    protected void map(Object key, Variant variant, Context context) throws IOException, InterruptedException {
        try {

            List<VariantStatsWrapper> variantStatsWrappers = calculator.calculateBatch(Collections.singletonList(variant), study, samples);
            if (variantStatsWrappers.isEmpty()) {
                return;
            }
            VariantStatsWrapper stats = variantStatsWrappers.get(0);

            context.getCounter(VariantsTableMapReduceHelper.COUNTER_GROUP_NAME, "variants").increment(1);

            Put put = converter.convert(stats);

            if (put == null) {
                context.getCounter(VariantsTableMapReduceHelper.COUNTER_GROUP_NAME, "stats.put.null").increment(1);
            } else {
                HadoopVariantSearchIndexUtils.addNotSyncStatus(put, helper.getColumnFamily());
                context.getCounter(VariantsTableMapReduceHelper.COUNTER_GROUP_NAME, "stats.put").increment(1);
                context.write(new ImmutableBytesWritable(helper.getVariantsTable()), put);
            }
        } catch (Exception e) {
            logger.error("Problem with variant " + variant, e);
            throw e;
        }
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        super.cleanup(context);
        if (calculator.getSkippedFiles() > 0) {
            logger.warn("Non calculated variant stats: " + calculator.getSkippedFiles());
            context.getCounter(VariantsTableMapReduceHelper.COUNTER_GROUP_NAME, "stats.skipped").increment(calculator.getSkippedFiles());
        }
    }

    public static Collection<Integer> getCohorts(Configuration conf) {
        int[] ints = conf.getInts(COHORTS);
        List<Integer> cohorts = new ArrayList<>(ints.length);
        for (int cohortId : ints) {
            cohorts.add(cohortId);
        }
        if (cohorts.isEmpty()) {
            throw new IllegalArgumentException("Missing cohorts!");
        }
        return cohorts;
    }

    public static void setCohorts(Job job, Collection<Integer> cohorts) {
        if (cohorts.isEmpty()) {
            throw new IllegalArgumentException("Missing cohorts!");
        }
        job.getConfiguration().set(COHORTS, cohorts.stream().map(Object::toString).collect(Collectors.joining(",")));
    }

    private Properties getAggregationMappingProperties(Configuration configuration) {
        Properties tagmap = new Properties();
        configuration.iterator().forEachRemaining(entry -> {
            if (entry.getKey().startsWith(TAGMAP_PREFIX)) {
                tagmap.put(entry.getKey().substring(TAGMAP_PREFIX.length()), entry.getValue());
            }
        });
        if (tagmap.isEmpty()) {
            return null;
        } else {
            return tagmap;
        }
    }

    public static void setAggregationMappingProperties(Configuration conf, Properties tagmap) {
        setAggregationMappingProperties(conf::set, tagmap);
    }

    public static void setAggregationMappingProperties(ObjectMap conf, Properties tagmap) {
        setAggregationMappingProperties(conf::put, tagmap);
    }

    private static void setAggregationMappingProperties(BiConsumer<String, String> f, Properties tagmap) {
        if (tagmap != null) {
            for (Map.Entry<Object, Object> entry : tagmap.entrySet()) {
                f.accept(TAGMAP_PREFIX + entry.getKey(), entry.getValue().toString());
            }
        }
    }

    private Aggregation getAggregation(StudyMetadata studyMetadata, Configuration configuration) {
        return AggregationUtils.valueOf(configuration.get(VariantStorageEngine.Options.AGGREGATED_TYPE.key(),
                studyMetadata.getAggregation().name()));
    }

    private Aggregation getAggregation(Configuration configuration) {
        return AggregationUtils.valueOf(configuration.get(VariantStorageEngine.Options.AGGREGATED_TYPE.key(), Aggregation.NONE.name()));
    }
}
