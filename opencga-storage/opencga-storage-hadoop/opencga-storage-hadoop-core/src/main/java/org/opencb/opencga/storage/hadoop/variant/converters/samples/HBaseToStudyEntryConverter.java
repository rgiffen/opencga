/*
 * Copyright 2015-2017 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.storage.hadoop.variant.converters.samples;

import com.google.common.base.Throwables;
import com.google.common.collect.BiMap;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.schema.types.PVarcharArray;
import org.opencb.biodata.models.variant.StudyEntry;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.avro.AlternateCoordinate;
import org.opencb.biodata.models.variant.avro.FileEntry;
import org.opencb.biodata.models.variant.avro.VariantType;
import org.opencb.biodata.tools.variant.merge.VariantMerger;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.storage.core.metadata.StudyConfiguration;
import org.opencb.opencga.storage.core.metadata.StudyConfigurationManager;
import org.opencb.opencga.storage.core.metadata.VariantStudyMetadata;
import org.opencb.opencga.storage.core.variant.VariantStorageEngine;
import org.opencb.opencga.storage.hadoop.variant.GenomeHelper;
import org.opencb.opencga.storage.hadoop.variant.converters.AbstractPhoenixConverter;
import org.opencb.opencga.storage.hadoop.variant.converters.HBaseToVariantConverter;
import org.opencb.opencga.storage.hadoop.variant.index.VariantTableStudyRow;
import org.opencb.opencga.storage.hadoop.variant.index.phoenix.VariantPhoenixHelper;
import org.opencb.opencga.storage.hadoop.variant.index.phoenix.VariantPhoenixKeyFactory;
import org.opencb.opencga.storage.hadoop.variant.models.protobuf.OtherSampleData;
import org.opencb.opencga.storage.hadoop.variant.models.protobuf.SampleList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;


/**
 * Created on 26/05/17.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class HBaseToStudyEntryConverter extends AbstractPhoenixConverter {

    private static final String UNKNOWN_SAMPLE_DATA = ".";
    private final GenomeHelper genomeHelper;
    private final StudyConfigurationManager scm;
    private final QueryOptions scmOptions = new QueryOptions(StudyConfigurationManager.READ_ONLY, true)
            .append(StudyConfigurationManager.CACHED, true);
    private final Map<Integer, LinkedHashMap<String, Integer>> returnedSamplesPositionMap = new HashMap<>();
    private List<String> returnedSamples = null;

    private boolean studyNameAsStudyId = false;
    private boolean simpleGenotypes = false;
    private boolean failOnWrongVariants = false;
    private static final String UNKNOWN_GENOTYPE = "?/?";
    private String unknownGenotype = UNKNOWN_GENOTYPE;
    private boolean mutableSamplesPosition = true;

    protected final Logger logger = LoggerFactory.getLogger(HBaseToStudyEntryConverter.class);

    public HBaseToStudyEntryConverter(GenomeHelper genomeHelper, StudyConfigurationManager scm) {
        super(genomeHelper.getColumnFamily());
        this.genomeHelper = genomeHelper;
        this.scm = scm;
    }

    public List<String> getReturnedSamples() {
        return returnedSamples;
    }

    public HBaseToStudyEntryConverter setReturnedSamples(List<String> returnedSamples) {
        this.returnedSamples = returnedSamples;
        return this;
    }

    public boolean isStudyNameAsStudyId() {
        return studyNameAsStudyId;
    }

    public HBaseToStudyEntryConverter setStudyNameAsStudyId(boolean studyNameAsStudyId) {
        this.studyNameAsStudyId = studyNameAsStudyId;
        return this;
    }

    public boolean isSimpleGenotypes() {
        return simpleGenotypes;
    }

    public HBaseToStudyEntryConverter setSimpleGenotypes(boolean simpleGenotypes) {
        this.simpleGenotypes = simpleGenotypes;
        return this;
    }

    public boolean isFailOnWrongVariants() {
        return failOnWrongVariants;
    }

    public HBaseToStudyEntryConverter setFailOnWrongVariants(boolean failOnWrongVariants) {
        this.failOnWrongVariants = failOnWrongVariants;
        return this;
    }

    public String getUnknownGenotype() {
        return unknownGenotype;
    }

    public HBaseToStudyEntryConverter setUnknownGenotype(String unknownGenotype) {
        if (StringUtils.isEmpty(unknownGenotype)) {
            this.unknownGenotype = UNKNOWN_GENOTYPE;
        } else {
            this.unknownGenotype = unknownGenotype;
        }
        return this;
    }

    public boolean isMutableSamplesPosition() {
        return mutableSamplesPosition;
    }

    public HBaseToStudyEntryConverter setMutableSamplesPosition(boolean mutableSamplesPosition) {
        this.mutableSamplesPosition = mutableSamplesPosition;
        return this;
    }

    protected StudyConfiguration getStudyConfiguration(Integer studyId) {
        QueryResult<StudyConfiguration> queryResult = scm.getStudyConfiguration(studyId, scmOptions);
        if (queryResult.getResult().isEmpty()) {
            throw new IllegalStateException("No study found for study ID: " + studyId);
        }
        return queryResult.first();
    }

    public Map<Integer, StudyEntry> convert(ResultSet resultSet) {
        try {
            ResultSetMetaData metaData = resultSet.getMetaData();

            Map<Integer, List<Integer>> columnsPerStudy = new HashMap<>();

            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                String columnName = metaData.getColumnName(i);
                if (columnName.endsWith(VariantPhoenixHelper.SAMPLE_DATA_SUFIX)
                        || columnName.endsWith(VariantPhoenixHelper.OTHER_SAMPLE_DATA_SUFIX)) {
                    String[] split = columnName.split(VariantPhoenixHelper.COLUMN_KEY_SEPARATOR_STR);
                    Integer studyId = getStudyId(split);
                    columnsPerStudy.computeIfAbsent(studyId, s -> new ArrayList<>()).add(i);
                } else if (columnName.endsWith(VariantTableStudyRow.HOM_REF)) {
                    Integer studyId = VariantTableStudyRow.extractStudyId(columnName, true);
                    columnsPerStudy.computeIfAbsent(studyId, s -> new ArrayList<>()).add(i);
                }
            }

            Variant variant = new Variant(resultSet.getString(VariantPhoenixHelper.VariantColumn.CHROMOSOME.column()),
                    resultSet.getInt(VariantPhoenixHelper.VariantColumn.POSITION.column()),
                    resultSet.getString(VariantPhoenixHelper.VariantColumn.REFERENCE.column()),
                    resultSet.getString(VariantPhoenixHelper.VariantColumn.ALTERNATE.column())
            );

            Map<Integer, VariantTableStudyRow> rows = VariantTableStudyRow.parse(variant, resultSet, genomeHelper)
                    .stream().collect(Collectors.toMap(VariantTableStudyRow::getStudyId, r -> r));

            HashMap<Integer, StudyEntry> map = new HashMap<>();
            columnsPerStudy.forEach((studyId, columnIds) -> {
                map.put(studyId, convert(resultSet, variant, studyId, columnIds, rows.get(studyId)));
            });

            return map;
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    public Map<Integer, StudyEntry> convert(Result result) {
        Map<Integer, List<byte[]>> columnsPerStudy = new HashMap<>();

        for (byte[] qualifier : result.getFamilyMap(columnFamily).keySet()) {

            if (endsWith(qualifier, VariantPhoenixHelper.SAMPLE_DATA_SUFIX_BYTES)
                    || endsWith(qualifier, VariantPhoenixHelper.OTHER_SAMPLE_DATA_SUFIX_BYTES)) {
                String columnName = Bytes.toString(qualifier);
                String[] split = columnName.split(VariantPhoenixHelper.COLUMN_KEY_SEPARATOR_STR);
                Integer studyId = getStudyId(split);
                columnsPerStudy.computeIfAbsent(studyId, s -> new ArrayList<>()).add(qualifier);
            } else if (endsWith(qualifier, VariantTableStudyRow.HOM_REF_BYTES)) {
                String columnName = Bytes.toString(qualifier);
                Integer studyId = VariantTableStudyRow.extractStudyId(columnName, true);
                columnsPerStudy.computeIfAbsent(studyId, s -> new ArrayList<>()).add(qualifier);
            }
        }

        Map<Integer, VariantTableStudyRow> rows = VariantTableStudyRow.parse(result, genomeHelper)
                .stream().collect(Collectors.toMap(VariantTableStudyRow::getStudyId, r -> r));

        HashMap<Integer, StudyEntry> map = new HashMap<>();
        columnsPerStudy.forEach((studyId, columnnIds) -> {
            map.put(studyId, convert(result, studyId, columnnIds, rows.get(studyId)));
        });

        return map;
    }

    protected StudyEntry convert(Iterator<Pair<String, Object>> iterator, Variant variant, Integer studyId, VariantTableStudyRow row) {
        StudyConfiguration studyConfiguration = getStudyConfiguration(studyId);
        List<String> fixedFormat = HBaseToVariantConverter.getFixedFormat(studyConfiguration);
        StudyEntry studyEntry = newStudyEntry(studyConfiguration, fixedFormat);


        Map<String, List<String>> alternateSampleMap = new HashMap<>();

        while (iterator.hasNext()) {
            Pair<String, Object> next = iterator.next();
            String columnName = next.getKey();
            if (columnName.endsWith(VariantPhoenixHelper.SAMPLE_DATA_SUFIX)) {
                Array value = (Array) next.getValue();
                if (value != null) {
                    List<String> sampleData = toModifiableList(value);
                    addMainSampleDataColumn(studyConfiguration, fixedFormat, studyEntry, alternateSampleMap, columnName, sampleData);
                }
            } else if (columnName.endsWith(VariantPhoenixHelper.OTHER_SAMPLE_DATA_SUFIX)) {
                byte[] bytes = (byte[]) next.getValue();
                if (bytes != null) {
                    addOtherSampleDataColumn(studyConfiguration, studyEntry, bytes, columnName);
                }
            }
        }

        addSecondaryAlternates(variant, studyEntry, studyConfiguration.getVariantMetadata(), alternateSampleMap);

        if (row != null) {
            convert(variant, studyEntry, studyConfiguration, row);
        }
        fillEmptySamplesData(studyEntry, studyConfiguration);

        return studyEntry;
    }

    private StudyEntry convert(ResultSet resultSet, Variant variant, Integer studyId, List<Integer> columnIds, VariantTableStudyRow row) {
        StudyConfiguration studyConfiguration = getStudyConfiguration(studyId);
        List<String> fixedFormat = HBaseToVariantConverter.getFixedFormat(studyConfiguration);
        StudyEntry studyEntry = newStudyEntry(studyConfiguration, fixedFormat);


        Map<String, List<String>> alternateSampleMap = new HashMap<>();
        try {
            ResultSetMetaData metaData = resultSet.getMetaData();
            for (Integer i : columnIds) {
                String columnName = metaData.getColumnName(i);
                if (columnName.endsWith(VariantPhoenixHelper.SAMPLE_DATA_SUFIX)) {
                    Array value = resultSet.getArray(i);
                    if (value != null) {
                        List<String> sampleData = toModifiableList(value);
                        addMainSampleDataColumn(studyConfiguration, fixedFormat, studyEntry, alternateSampleMap, columnName, sampleData);
                    }
                } else if (columnName.endsWith(VariantPhoenixHelper.OTHER_SAMPLE_DATA_SUFIX)) {
                    byte[] bytes = resultSet.getBytes(i);
                    if (bytes != null) {
                        addOtherSampleDataColumn(studyConfiguration, studyEntry, bytes, columnName);
                    }
                }
            }
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }

        addSecondaryAlternates(variant, studyEntry, studyConfiguration.getVariantMetadata(), alternateSampleMap);

        if (row != null) {
            convert(variant, studyEntry, studyConfiguration, row);
        }
        fillEmptySamplesData(studyEntry, studyConfiguration);

        return studyEntry;
    }

    private StudyEntry convert(Result result, Integer studyId, List<byte[]> columns, VariantTableStudyRow row) {
        StudyConfiguration studyConfiguration = getStudyConfiguration(studyId);
        List<String> fixedFormat = HBaseToVariantConverter.getFixedFormat(studyConfiguration);
        StudyEntry studyEntry = newStudyEntry(studyConfiguration, fixedFormat);

        Map<String, List<String>> alternateSampleMap = new HashMap<>();

        for (byte[] qualifier : columns) {
            byte[] value = result.getValue(columnFamily, qualifier);
            String columnName = Bytes.toString(qualifier);

            if (endsWith(qualifier, VariantPhoenixHelper.SAMPLE_DATA_SUFIX_BYTES)) {
                if (value != null) {
                    Array array = (Array) PVarcharArray.INSTANCE.toObject(value);
                    List<String> sampleData = toModifiableList(array);
                    addMainSampleDataColumn(studyConfiguration, fixedFormat, studyEntry, alternateSampleMap, columnName, sampleData);
                }
            } else if (endsWith(qualifier, VariantPhoenixHelper.OTHER_SAMPLE_DATA_SUFIX_BYTES)) {
                if (value != null) {
                    addOtherSampleDataColumn(studyConfiguration, studyEntry, value, columnName);
                }
            }
        }

        Variant variant = VariantPhoenixKeyFactory.extractVariantFromVariantRowKey(result.getRow());
        addSecondaryAlternates(variant, studyEntry, studyConfiguration.getVariantMetadata(), alternateSampleMap);

        if (row != null) {
            convert(variant, studyEntry, studyConfiguration, row);
        }
        fillEmptySamplesData(studyEntry, studyConfiguration);

        return studyEntry;
    }

    protected StudyEntry newStudyEntry(StudyConfiguration studyConfiguration, List<String> fixedFormat) {
        StudyEntry studyEntry;
        if (studyNameAsStudyId) {
            studyEntry = new StudyEntry(studyConfiguration.getStudyName());
        } else {
            studyEntry = new StudyEntry(String.valueOf(studyConfiguration.getStudyId()));
        }

        studyEntry.setFormat(new ArrayList<>(fixedFormat));
        LinkedHashMap<String, Integer> returnedSamplesPosition;
        if (mutableSamplesPosition) {
            returnedSamplesPosition = new LinkedHashMap<>(getReturnedSamplesPosition(studyConfiguration));
        } else {
            returnedSamplesPosition = getReturnedSamplesPosition(studyConfiguration);
        }
        studyEntry.setSortedSamplesPosition(returnedSamplesPosition);
//        studyEntry.setSamplesData(new ArrayList<>(samplesPosition.size()));
        return studyEntry;
    }

    protected void addMainSampleDataColumn(StudyConfiguration studyConfiguration, List<String> fixedFormat, StudyEntry studyEntry,
                                           Map<String, List<String>> alternateSampleMap, String columnName, List<String> sampleData) {
        String[] split = columnName.split(VariantPhoenixHelper.COLUMN_KEY_SEPARATOR_STR);
//                        Integer studyId = getStudyId(split);
        Integer sampleId = getSampleId(split);
        String sampleName = studyConfiguration.getSampleIds().inverse().get(sampleId);

        // Remove secondary alternate
        if (sampleData.size() > fixedFormat.size()) {
            // Do not use "remove" over the last element. sampleData may be an unmodifiable list
            String alternate = sampleData.get(sampleData.size() - 1);
            sampleData = sampleData.subList(0, sampleData.size() - 1);

            alternateSampleMap.compute(alternate, (key, list) -> {
                if (list == null) {
                    list = new ArrayList<>(1);
                }
                list.add(sampleName);
                return list;
            });
//          List<Integer> sampleIds = study.alternateSampleIdMap.computeIfAbsent(alternate, key -> new ArrayList<>());
//          sampleIds.add(sampleId);
        }

        studyEntry.addSampleData(sampleName, sampleData);
    }

    private void addOtherSampleDataColumn(StudyConfiguration studyConfiguration, StudyEntry studyEntry, byte[] bytes, String columnName) {
        OtherSampleData otherSampleData;
        try {
            otherSampleData = OtherSampleData.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw Throwables.propagate(e);
        }
        String[] split = columnName.split(VariantPhoenixHelper.COLUMN_KEY_SEPARATOR_STR);
//                        Integer studyId = getStudyId(split);
        Integer sampleId = getSampleId(split);

        otherSampleData.getSampleDataMap().forEach((format, value) -> {

            if (!studyEntry.getFormatPositions().containsKey(format)) {
                studyEntry.addFormat(format);
            }
            String sampleName = studyConfiguration.getSampleIds().inverse().get(sampleId);
            studyEntry.addSampleData(sampleName, format, value, UNKNOWN_SAMPLE_DATA);
//            List<String> data = studyEntry.getSampleData(sampleName);
//            if (idx > data.size()) {
//                do {
//                    data.add("");
//                } while (idx > data.size());
//            } else {
//                data.set(idx, value);
//            }
        });

        if (studyEntry.getFiles().stream().noneMatch(f -> f.getFileId().equalsIgnoreCase(otherSampleData.getFile().getFileId()))) {
            FileEntry fileEntry = new FileEntry(
                    otherSampleData.getFile().getFileId(),
                    otherSampleData.getFile().getCall(),
                    otherSampleData.getFile().getAttributesMap());
            studyEntry.getFiles().add(fileEntry);
        }
    }

    private void fillEmptySamplesData(StudyEntry studyEntry, StudyConfiguration studyConfiguration) {
        List<String> format = studyEntry.getFormat();
        List<String> emptyData = new ArrayList<>(format.size());
        String defaultGenotype = getDefaultGenotype(studyConfiguration);
        for (String formatKey : format) {
            if ("GT".equals(formatKey)) {
                emptyData.add(defaultGenotype);
            } else {
                emptyData.add(UNKNOWN_SAMPLE_DATA);
            }
        }
        // Make unmodifiable. All samples will share this information
        List<String> unmodifiableEmptyData = Collections.unmodifiableList(emptyData);
        studyEntry.getSamplesData().replaceAll(strings -> {
            if (strings == null) {
                return unmodifiableEmptyData;
            } else if (strings.size() < unmodifiableEmptyData.size()) {
                for (int i = strings.size(); i < unmodifiableEmptyData.size(); i++) {
                    strings.add(unmodifiableEmptyData.get(i));
                }
                return strings;
            } else {
                return strings;
            }
        });
    }

    public StudyEntry convert(Variant variant, VariantTableStudyRow row) {

        StudyConfiguration studyConfiguration = getStudyConfiguration(row.getStudyId());
        List<String> fixedFormat = HBaseToVariantConverter.getFixedFormat(studyConfiguration);
        StudyEntry studyEntry = newStudyEntry(studyConfiguration, fixedFormat);

        return convert(variant, studyEntry, studyConfiguration, row);
    }

    public StudyEntry convert(Variant variant, StudyEntry studyEntry, StudyConfiguration studyConfiguration, VariantTableStudyRow row) {
        BiMap<Integer, String> mapSampleIds = studyConfiguration.getSampleIds().inverse();
        LinkedHashMap<String, Integer> returnedSamplesPosition = studyEntry.getSamplesPosition();

        int loadedSamplesSize = StudyConfiguration.getIndexedSamples(studyConfiguration).size();

//        calculatePassCallRates(row, attributesMap, loadedSamplesSize);

        Integer gtIdx = studyEntry.getFormatPositions().get("GT");
        if (gtIdx != null) {
            Set<Integer> sampleWithVariant = new HashSet<>();
            for (String genotype : row.getGenotypes()) {
                sampleWithVariant.addAll(row.getSampleIds(genotype));
                if (genotype.equals(VariantTableStudyRow.OTHER)) {
                    continue; // skip OTHER -> see Complex type
                }
                for (Integer sampleId : row.getSampleIds(genotype)) {
                    String sampleName = mapSampleIds.get(sampleId);
                    Integer sampleIdx = returnedSamplesPosition.get(sampleName);
                    if (sampleIdx == null) {
                        continue;   //Sample may not be required. Ignore this sample.
                    }
//                    List<String> lst = Arrays.asList(genotype, VariantMerger.PASS_VALUE);
//                samplesDataArray[sampleIdx] = lst;
//                    studyEntry.addSampleData(sampleIdx, lst);
                    studyEntry.addSampleData(sampleIdx, gtIdx, genotype, UNKNOWN_SAMPLE_DATA);
                }
            }

            // Load complex genotypes
            for (Map.Entry<Integer, String> entry : row.getComplexVariant().getSampleToGenotypeMap().entrySet()) {
                sampleWithVariant.add(entry.getKey());

                String sampleName = mapSampleIds.get(entry.getKey());
                Integer samplePosition = returnedSamplesPosition.get(sampleName);
                if (samplePosition == null) {
                    continue;   //Sample may not be required. Ignore this sample.
                }
                String genotype = entry.getValue();
                String returnedGenotype;
                // FIXME: Decide what to do with lists of genotypes
                if (simpleGenotypes) {
                    returnedGenotype = getSimpleGenotype(genotype);
                    logger.debug("Return simplified genotype: {} -> {}", genotype, returnedGenotype);
                } else {
                    returnedGenotype = genotype;
                }
//            samplesDataArray[samplePosition] = Arrays.asList(returnedGenotype, VariantMerger.PASS_VALUE);
//            studyEntry.addSampleData(samplePosition, Arrays.asList(returnedGenotype, VariantMerger.PASS_VALUE));
//                studyEntry.addSampleData(sampleName, "GT", returnedGenotype);
                studyEntry.addSampleData(samplePosition, gtIdx, returnedGenotype, UNKNOWN_SAMPLE_DATA);
            }

            // Fill gaps (with HOM_REF)
//            int gapCounter = 0;
//            for (int i = 0; i < studyEntry.getSamplesData().size(); i++) {
//                if (studyEntry.getSamplesData().get(i) == null) {
//                    ++gapCounter;
//                    studyEntry.addSampleData(i, Arrays.asList(VariantTableStudyRow.HOM_REF, VariantMerger.PASS_VALUE));
//                }
//            }
//            for (int i = 0; i < samplesDataArray.length; i++) {
//                if (samplesDataArray[i] == null) {
//                    ++gapCounter;
//                    samplesDataArray[i] = Arrays.asList(VariantTableStudyRow.HOM_REF, VariantMerger.PASS_VALUE);
//                }
//            }

            // Check homRef count
            int homRefCount = loadedSamplesSize;
            homRefCount -= sampleWithVariant.size();
            if (homRefCount != row.getHomRefCount()) {
                StringBuilder message = new StringBuilder()
                        .append("Wrong number of HomRef samples for variant ").append(variant)
                        .append(". Got ").append(homRefCount).append(", expect ").append(row.getHomRefCount())
                        .append(". Samples number: ").append(studyEntry.getSamplesData().size()).append(" , ")
                        .append('\'' + VariantTableStudyRow.HOM_REF + "':").append(row.getHomRefCount()).append(" , ");
                for (String studyColumn : VariantTableStudyRow.GENOTYPE_COLUMNS) {
                    message.append('\'').append(studyColumn).append("':").append(row.getSampleIds(studyColumn)).append(" , ");
                }
                wrongVariant(message.toString());
            }
        }

        Integer ftIdx = studyEntry.getFormatPositions().get("FT");
        if (ftIdx != null) {
            // Set pass field
            int passCount = loadedSamplesSize;
            for (Map.Entry<String, SampleList> entry : row.getComplexFilter().getFilterNonPass().entrySet()) {
                String filterString = entry.getKey();
                passCount -= entry.getValue().getSampleIdsCount();
                for (Integer sampleId : entry.getValue().getSampleIdsList()) {
                    String sampleName = mapSampleIds.get(sampleId);
                    Integer samplePosition = returnedSamplesPosition.get(sampleName);
                    if (samplePosition == null) {
                        continue;   //Sample may not be required. Ignore this sample.
                    }
                    studyEntry.addSampleData(samplePosition, ftIdx, filterString, UNKNOWN_SAMPLE_DATA);
                }
            }

            // Check pass count
            if (passCount != row.getPassCount()) {
                wrongVariant("Error parsing variant " + row.toString() + ". "
                        + "Pass count " + row.getPassCount() + " does not match filter "
                        + "fill count: " + passCount + " using " + loadedSamplesSize + " loaded samples.");
            }
        }

        return studyEntry;
    }

    private String getDefaultGenotype(StudyConfiguration studyConfiguration) {
        String defaultGenotype;
        if (VariantStorageEngine.MergeMode.from(studyConfiguration.getAttributes()).equals(VariantStorageEngine.MergeMode.ADVANCED)) {
            defaultGenotype = "0/0";
        } else {
            defaultGenotype = unknownGenotype;
        }
        return defaultGenotype;
    }

    private String getSimpleGenotype(String genotype) {
        int idx = genotype.indexOf(',');
        if (idx > 0) {
            return genotype.substring(0, idx);
        } else {
            return genotype;
        }
    }

    private void wrongVariant(String message) {
        if (failOnWrongVariants) {
            throw new IllegalStateException(message);
        } else {
            logger.warn(message);
        }
    }

    /**
     * Creates a SORTED MAP with the required samples position.
     *
     * @param studyConfiguration Study Configuration
     * @return Sorted linked hash map
     */
    private LinkedHashMap<String, Integer> getReturnedSamplesPosition(StudyConfiguration studyConfiguration) {
        if (!returnedSamplesPositionMap.containsKey(studyConfiguration.getStudyId())) {
            LinkedHashMap<String, Integer> samplesPosition = StudyConfiguration.getReturnedSamplesPosition(studyConfiguration,
                    returnedSamples == null ? null : new LinkedHashSet<>(returnedSamples), StudyConfiguration::getIndexedSamplesPosition);
            returnedSamplesPositionMap.put(studyConfiguration.getStudyId(), samplesPosition);
        }
        return returnedSamplesPositionMap.get(studyConfiguration.getStudyId());
    }

    /**
     * Add secondary alternates to the StudyEntry. Merge secondar alternates if needed.
     *
     * @param variant           Variant coordinates.
     * @param studyEntry        Study Entry all samples data
     * @param variantMetadata   Variant Metadata from the study, to configure the VariantMerger
     * @param alternateSampleIdMap  Map from SecondaryAlternate to SamplesData
     */
    private void addSecondaryAlternates(Variant variant, StudyEntry studyEntry, VariantStudyMetadata variantMetadata,
                                        Map<String, List<String>> alternateSampleIdMap) {
        final List<AlternateCoordinate> alternateCoordinates;
        if (alternateSampleIdMap.isEmpty()) {
            alternateCoordinates = Collections.emptyList();
        } else if (alternateSampleIdMap.size() == 1) {
            alternateCoordinates = getAlternateCoordinates(alternateSampleIdMap.keySet().iterator().next());
        } else {
            // There are multiple secondary alternates.
            // We need to rearrange the genotypes to match with the secondary alternates order.
            VariantMerger variantMerger = new VariantMerger(false);
            variantMerger.setExpectedFormats(studyEntry.getFormat());
            variantMerger.setStudyId("0");
            for (VariantStudyMetadata.VariantMetadataRecord record : variantMetadata.getFormat().values()) {
                variantMerger.configure(record.getId(), record.getNumberType(), record.getType());
            }
            for (VariantStudyMetadata.VariantMetadataRecord record : variantMetadata.getInfo().values()) {
                variantMerger.configure(record.getId(), record.getNumberType(), record.getType());
            }

            // Create one variant for each alternate with the samples data
            List<Variant> variants = new ArrayList<>(alternateSampleIdMap.size());
            for (Map.Entry<String, List<String>> entry : alternateSampleIdMap.entrySet()) {
                String secondaryAlternates = entry.getKey();

                Variant sampleVariant = new Variant(
                        variant.getChromosome(),
                        variant.getStart(),
                        variant.getReference(),
                        variant.getAlternate());
                StudyEntry se = new StudyEntry("0");
                se.setSecondaryAlternates(getAlternateCoordinates(secondaryAlternates));
                se.setFormat(studyEntry.getFormat());
                for (String sample : entry.getValue()) {
                    se.addSampleData(sample, studyEntry.getSampleData(sample));
                }
                sampleVariant.addStudyEntry(se);
                variants.add(sampleVariant);
            }

            // Merge the variants in the first variant
            Variant newVariant = variantMerger.merge(variants.get(0), variants.subList(1, variants.size()));

            // Update samplesData information
            StudyEntry se = newVariant.getStudies().get(0);
            for (String sample : se.getSamplesName()) {
                studyEntry.addSampleData(sample, se.getSampleData(sample));
            }
//            for (Map.Entry<String, Integer> entry : se.getSamplesPosition().entrySet()) {
//                List<String> data = se.getSamplesData().get(entry.getValue());
//                Integer sampleId = Integer.valueOf(entry.getKey());
//                samplesDataMap.put(sampleId, data);
//            }
            alternateCoordinates = se.getSecondaryAlternates();
        }
        studyEntry.setSecondaryAlternates(alternateCoordinates);
    }

    public List<AlternateCoordinate> getAlternateCoordinates(String s) {
        return Arrays.stream(s.split(","))
                .map(this::getAlternateCoordinate)
                .collect(Collectors.toList());
    }

    public AlternateCoordinate getAlternateCoordinate(String s) {
        String[] split = s.split(":");
        return new AlternateCoordinate(
                split[0],
                Integer.parseInt(split[1]),
                Integer.parseInt(split[2]),
                split[3],
                split[4],
                VariantType.valueOf(split[5])
        );
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> toList(Array value) {
        try {
            return Arrays.asList((T[]) value.getArray());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> toModifiableList(Array value) {
        try {
            T[] array = (T[]) value.getArray();
            ArrayList<T> list = new ArrayList<>(array.length);
            for (T t : array) {
                list.add(t);
            }
            return list;
//            return Arrays.asList((T[]) value.getArray());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Integer getStudyId(String[] split) {
        return Integer.valueOf(split[0]);
    }

    private Integer getSampleId(String[] split) {
        return Integer.valueOf(split[1]);
    }

}
