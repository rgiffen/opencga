/**
 * 
 */
package org.opencb.opencga.storage.hadoop.variant.index;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.opencb.hpg.bigdata.tools.utils.HBaseUtils;
import org.opencb.opencga.storage.hadoop.utils.HBaseManager;
import org.opencb.opencga.storage.hadoop.variant.index.models.protobuf.VariantCallMeta;
import org.opencb.opencga.storage.hadoop.variant.index.models.protobuf.VariantCallProtos.VariantCallMetaProt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

/**
 * @author Matthias Haimel mh719+git@cam.ac.uk
 *
 */
public class VariantTableDriver extends Configured implements Tool {
    private static final Logger LOG = LoggerFactory.getLogger(VariantTableDriver.class);

    public static final String OPENCGA_VARIANT_TRANSFORM_SAMPLE_ARR = "opencga.variant.transform.sample_arr";
    public static final String OPENCGA_VARIANT_TRANSFORM_COLUMNARR = "opencga.variant.transform.column_arr";
    public static final String OPENCGA_VARIANT_TRANSFORM_OUTPUT = "opencga.variant.transform.output";
    public static final String OPENCGA_VARIANT_TRANSFORM_INPUT = "opencga.variant.transform.input";
    public static final String HBASE_MASTER = "hbase.master";

    public VariantTableDriver () { /* nothing */}

    public VariantTableDriver(Configuration conf){
        super(conf);
    }

    @Override
    public int run(String[] args) throws Exception {
        Configuration conf = getConf();
        String in_table = conf.get(OPENCGA_VARIANT_TRANSFORM_INPUT, StringUtils.EMPTY);
        String out_table = conf.get(OPENCGA_VARIANT_TRANSFORM_OUTPUT, StringUtils.EMPTY);
        String[] column_arr = conf.getStrings(OPENCGA_VARIANT_TRANSFORM_COLUMNARR, new String[0]);
        String[] sample_arr = conf.getStrings(OPENCGA_VARIANT_TRANSFORM_COLUMNARR, column_arr);

        /* -------------------------------*/
        // Validate parameters CHECK
        if (StringUtils.isEmpty(in_table)) {
            throw new IllegalArgumentException("No input hbase table specified!!!");
        }
        if (StringUtils.isEmpty(out_table)) {
            throw new IllegalArgumentException("No output hbase table specified!!!");
        }
        if (in_table.equals(out_table)) {
            throw new IllegalArgumentException("Input and Output tables must be different");
        }
        int colCnt = column_arr.length;
        if (colCnt == 0) {
            throw new IllegalArgumentException("No columns specified");
        }
        if (Integer.compare(colCnt, sample_arr.length) != 0)  {
            throw new IllegalArgumentException(
                    String.format("Difference in number of sample names (%s) and column names", colCnt, sample_arr.length));
        }

        VariantTableHelper.setOutputTableName(conf, out_table);
        VariantTableHelper.setInputTableName(conf, in_table);
        VariantTableHelper gh = new VariantTableHelper(conf);


        /* -------------------------------*/
        // Validate input CHECK
        HBaseManager.HBaseTableAdminFunction<Boolean> func = ((Table table, Admin admin) -> HBaseUtils.exist(table.getName(),admin));
        if(!gh.getHBaseManager().act(in_table, func)) {
            throw new IllegalArgumentException(String.format("Input table %s does not exist!!!",in_table));
        }

        /* -------------------------------*/
        // INIT META Data
        VariantCallMeta meta = initMetaData(conf, gh);
        
        Integer nextId = meta.nextId();
        for(int i = 0; i < colCnt; ++i){
            meta.addSample(sample_arr[i], nextId+i, ByteString.copyFrom(column_arr[i].getBytes()));
        }
        gh.storeMeta(meta.build());

        /* -------------------------------*/
        // JOB setup
        Job job = Job.getInstance(conf, "Genome Variant Transform from & to HBase");
        job.setJarByClass(VariantTableMapper.class);    // class that contains mapper
        job.getConfiguration().set("mapreduce.job.user.classpath.first", "true");
        
        Scan scan = new Scan();
        scan.setCaching(500);        // 1 is the default in Scan, which will be bad for MapReduce jobs
        scan.setCacheBlocks(false);  // don't set to true for MR jobs
        
//        scan.addColumn(family, qualifier) //TODO define columns to return
        
        // set other scan attrs
        TableMapReduceUtil.initTableMapperJob(
            in_table,      // input table
            scan,             // Scan instance to control CF and attribute selection
            VariantTableMapper.class,   // mapper class
            null,             // mapper output key
            null,             // mapper output value
            job);
        TableMapReduceUtil.initTableReducerJob(
            out_table,      // output table
            null,             // reducer class
            job);
        job.setNumReduceTasks(0);
        
        boolean b = job.waitForCompletion(true);
        if (!b) {
            LOG.error("error with job!");
        }
        return b?0:1;
    }

    private VariantCallMeta initMetaData(Configuration conf, VariantTableHelper gh) throws IOException {
        boolean out_exist = gh.actOnTable(((Table table, Admin admin) -> HBaseUtils.exist(table.getName(), admin)));

        VariantCallMeta meta = new VariantCallMeta();
        if(!out_exist){
            HBaseUtils.createTableIfNeeded(gh.getOutputTableAsString(), gh.getColumnFamily(), conf);
            LOG.info(String.format("Create table '%s' in hbase!", gh.getOutputTableAsString()));
        } else{
            meta = new VariantCallMeta(gh.loadMeta());
        }
        return meta;
    }

    private void print(VariantCallMetaProt meta) {
//        VariantCallMeta m = new VariantCallMeta(meta);
        int maxField = meta.getSampleIdCount();
        if(maxField == 0){
            System.err.println("No data in Meta file");
            return;
        }
        for(int i = 0; i < maxField; ++i){
            System.err.printf("%s %s %s", meta.getSampleNames(i),meta.getSampleId(i),Bytes.toString(meta.getSampleColumn(i).toByteArray()));
        }
    }

    public static void addHBaseSettings(Configuration conf, String hostPortString) throws URISyntaxException{
        String[] hostPort = hostPortString.split(":");
        String server = hostPort[0];
        String port = hostPort.length > 0 ? hostPort[1] : "60000";
        String master = String.join(":", server, port);
        conf.set(HConstants.ZOOKEEPER_QUORUM, server);
        conf.set(HBASE_MASTER, master);
    }


    /**
     * @param args
     * @throws IOException 
     * @throws Exception 
     */
    public static void main(String[] args) throws Exception {
        // info https://code.google.com/p/temapred/wiki/HbaseWithJava
        Configuration conf = new Configuration();
        VariantTableDriver driver = new VariantTableDriver();
        GenericOptionsParser parser = new GenericOptionsParser(conf, args);
        
        //get the args w/o generic hadoop args
        String[] toolArgs = parser.getRemainingArgs();
        
        if (toolArgs.length != 4) {
            System.err.printf("Usage: %s [generic options] <server> <input-table> <output-table> <column>\n", 
                    VariantTableDriver.class.getSimpleName());
            System.err.println("Found " + Arrays.toString(toolArgs));
            ToolRunner.printGenericCommandUsage(System.err);
            System.exit(-1);
        }

        String[] cols = toolArgs[3].split(",");

        addHBaseSettings(conf, toolArgs[0]);
        conf.set(OPENCGA_VARIANT_TRANSFORM_INPUT, toolArgs[1]);
        conf.set(OPENCGA_VARIANT_TRANSFORM_OUTPUT, toolArgs[2]);
        conf.setStrings(OPENCGA_VARIANT_TRANSFORM_COLUMNARR, cols);

        //set the configuration back, so that Tool can configure itself
        driver.setConf(conf);
        
        /* Alternative to using tool runner */
//      int exitCode = ToolRunner.run(conf,new GenomeVariantDriver(), args);
        int exitCode = driver.run(toolArgs);

        System.exit(exitCode);
    }

}