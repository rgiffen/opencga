/**
 *
 */
package org.opencb.opencga.storage.hadoop.variant.index;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.opencb.datastore.core.QueryOptions;
import org.opencb.opencga.storage.hadoop.variant.HBaseStudyConfigurationManager;
import org.opencb.opencga.storage.hadoop.variant.exceptions.StorageHadoopException;
import org.opencb.opencga.storage.hadoop.variant.metadata.BatchFileOperation;
import org.opencb.opencga.storage.hadoop.variant.metadata.HBaseVariantStudyConfiguration;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;

/**
 * @author Matthias Haimel mh719+git@cam.ac.uk
 */
public class VariantTableDriver extends AbstractVariantTableDriver {

    private HBaseVariantStudyConfiguration studyConfiguration;

    public VariantTableDriver() { /* nothing */ }

    public VariantTableDriver(Configuration conf) {
        super(conf);
    }

    @SuppressWarnings ("rawtypes")
    @Override
    protected Class<? extends TableMapper> getMapperClass() {
        return VariantTableMapper.class;
    }

    @Override
    protected void check(List<Integer> fileIds, Configuration conf) throws StorageHadoopException, IOException {

        HBaseStudyConfigurationManager scm = getStudyConfigurationManager();
        studyConfiguration = scm.toHBaseStudyConfiguration(loadStudyConfiguration());

        List<BatchFileOperation> batches = studyConfiguration.getBatches();
        BatchFileOperation batchFileOperation;
        if (!batches.isEmpty()) {
            batchFileOperation = batches.get(batches.size() - 1);
            BatchFileOperation.Status currentStatus = batchFileOperation.currentStatus();
            if (currentStatus != null) {
                switch (currentStatus) {
                    case RUNNING:
                        throw new StorageHadoopException("Unable to load a new batch. Already loading batch: "
                                + batchFileOperation);
                    case READY:
                        batchFileOperation = new BatchFileOperation(fileIds, batchFileOperation.getTimestamp() + 1);
                        break;
                    case ERROR:
                        if (batchFileOperation.getFileIds().equals(fileIds)) {
                            LOG.info("Resuming Last batch loading due to error.");
                        } else {
                            throw new StorageHadoopException("Unable to resume last batch loading. Must load the same "
                                    + "files from the previous batch: " + batchFileOperation);
                        }
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown Status " + currentStatus);
                }
            }
        } else {
            batchFileOperation = new BatchFileOperation(fileIds, 1);
        }
        batchFileOperation.addStatus(Calendar.getInstance().getTime(), BatchFileOperation.Status.RUNNING);
        batches.add(batchFileOperation);

        scm.updateStudyConfiguration(studyConfiguration, new QueryOptions());

    }

    @Override
    protected void onError() {
        super.onError();
        try {
            HBaseStudyConfigurationManager scm = getStudyConfigurationManager();
            BatchFileOperation batchFileOperation = studyConfiguration.getBatches().get(studyConfiguration.getBatches().size() - 1);
            batchFileOperation.addStatus(Calendar.getInstance().getTime(), BatchFileOperation.Status.ERROR);
            scm.updateStudyConfiguration(studyConfiguration, new QueryOptions());
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    protected void onSuccess() {
        super.onSuccess();
        try {
            HBaseStudyConfigurationManager scm = getStudyConfigurationManager();
            BatchFileOperation batchFileOperation = studyConfiguration.getBatches().get(studyConfiguration.getBatches().size() - 1);
            batchFileOperation.addStatus(Calendar.getInstance().getTime(), BatchFileOperation.Status.READY);
            scm.updateStudyConfiguration(studyConfiguration, new QueryOptions());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        System.exit(privateMain(args, null));
    }

    public static int privateMain(String[] args, Configuration conf) throws Exception {
        // info https://code.google.com/p/temapred/wiki/HbaseWithJava
        if (conf == null) {
            conf = new Configuration();
        }
        VariantTableDriver driver = new VariantTableDriver();
        String[] toolArgs = configure(args, conf);
        if (null == toolArgs) {
            return -1;
        }

        //set the configuration back, so that Tool can configure itself
        driver.setConf(conf);

        /* Alternative to using tool runner */
//      int exitCode = ToolRunner.run(conf,new GenomeVariantDriver(), args);
        int exitCode = driver.run(toolArgs);
        return exitCode;
    }

}
