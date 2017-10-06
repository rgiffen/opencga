package org.opencb.opencga.storage.hadoop.variant.converters.samples;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.phoenix.schema.types.PVarchar;
import org.apache.phoenix.schema.types.PhoenixArray;
import org.junit.Before;
import org.junit.Test;
import org.opencb.biodata.models.variant.StudyEntry;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.opencga.storage.core.metadata.StudyConfiguration;
import org.opencb.opencga.storage.core.metadata.StudyConfigurationManager;
import org.opencb.opencga.storage.core.variant.VariantStorageEngine;
import org.opencb.opencga.storage.core.variant.dummy.DummyStudyConfigurationAdaptor;
import org.opencb.opencga.storage.hadoop.variant.GenomeHelper;
import org.opencb.opencga.storage.hadoop.variant.index.phoenix.VariantPhoenixHelper;
import org.opencb.opencga.storage.hadoop.variant.models.protobuf.OtherSampleData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Created on 06/10/17.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class HBaseToSamplesDataConverterTest {

    private HBaseToSamplesDataConverter converter;
    private StudyConfiguration sc;
    private StudyConfigurationManager scm;

    @Before
    public void setUp() throws Exception {
        scm = new StudyConfigurationManager(new DummyStudyConfigurationAdaptor());
        sc = new StudyConfiguration(1, "S1");
        sc.getIndexedFiles().add(1);
        sc.getIndexedFiles().add(2);
        sc.getSamplesInFiles().put(1, new LinkedHashSet<>(Arrays.asList(1, 2, 3)));
        sc.getSamplesInFiles().put(2, new LinkedHashSet<>(Arrays.asList(4, 5, 6)));
        sc.getSampleIds().put("S1", 1);
        sc.getSampleIds().put("S2", 2);
        sc.getSampleIds().put("S3", 3);
        sc.getSampleIds().put("S4", 4);
        sc.getSampleIds().put("S5", 5);
        sc.getSampleIds().put("S6", 6);
        sc.getSampleIds().put("S7", 7);

        scm.updateStudyConfiguration(sc, null);

        converter = new HBaseToSamplesDataConverter(new GenomeHelper(new Configuration()), scm);

    }

    @Test
    public void testConvertBasic() throws Exception {
        List<Pair<String, Object>> values = new ArrayList<>();
        values.add(Pair.of(new String(VariantPhoenixHelper.buildSampleColumnKey(1, 1)), new PhoenixArray(PVarchar.INSTANCE, new String[]{"0/0", "PASS"})));
        values.add(Pair.of(new String(VariantPhoenixHelper.buildSampleColumnKey(1, 3)), new PhoenixArray(PVarchar.INSTANCE, new String[]{"0/1", "PASS"})));

        StudyEntry s = converter.convert(values.iterator(), new Variant("1:1000:A:C"), 1, null);
        System.out.println("s = " + s);
    }

    @Test
    public void testConvertExtendedFormat() throws Exception {
        sc.getAttributes().put(VariantStorageEngine.Options.EXTRA_GENOTYPE_FIELDS.key(), "AD,DP");
        scm.updateStudyConfiguration(sc, null);

        List<Pair<String, Object>> values = new ArrayList<>();
        values.add(Pair.of(new String(VariantPhoenixHelper.buildSampleColumnKey(1, 1)), new PhoenixArray(PVarchar.INSTANCE, new String[]{"0/0", "1,2", "10"})));
        values.add(Pair.of(new String(VariantPhoenixHelper.buildSampleColumnKey(1, 3)), new PhoenixArray(PVarchar.INSTANCE, new String[]{"0/1", "3,4", "20"})));

        StudyEntry s = converter.convert(values.iterator(), new Variant("1:1000:A:C"), 1, null);
        System.out.println("s = " + s);
    }

    @Test
    public void testConvertExtendedFormatFileEntryData() throws Exception {
        sc.getAttributes().put(VariantStorageEngine.Options.EXTRA_GENOTYPE_FIELDS.key(), "AD,DP");
        scm.updateStudyConfiguration(sc, null);

        List<Pair<String, Object>> values = new ArrayList<>();
        values.add(Pair.of(new String(VariantPhoenixHelper.buildSampleColumnKey(1, 1)), new PhoenixArray(PVarchar.INSTANCE, new String[]{"0/0", "1,2", "10"})));
        values.add(Pair.of(new String(VariantPhoenixHelper.buildOtherSampleDataColumnKey(1, 1)), OtherSampleData.newBuilder()
                .putSampleData("KEY_1", "VALUE_1")
                .putSampleData("KEY_2", "VALUE_2")
                .build().toByteArray()));
        values.add(Pair.of(new String(VariantPhoenixHelper.buildSampleColumnKey(1, 3)), new PhoenixArray(PVarchar.INSTANCE, new String[]{"0/1", "3,4", "20"})));

        StudyEntry s = converter.convert(values.iterator(), new Variant("1:1000:A:C"), 1, null);
        System.out.println("s = " + s);
    }
}