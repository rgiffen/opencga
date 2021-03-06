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

package org.opencb.opencga.storage.hadoop.variant.executors;

import org.apache.hadoop.util.StopWatch;
import org.apache.hadoop.util.Tool;
import org.apache.tools.ant.types.Commandline;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.opencga.storage.core.exceptions.StorageEngineException;
import org.opencb.opencga.storage.hadoop.variant.HadoopVariantStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created on 18/01/16 .
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public abstract class MRExecutor {

    public static final String HADOOP_BIN = "opencga.hadoop.bin";
    public static final String HADOOP_ENV = "opencga.hadoop.env";

    private ObjectMap options;
    private List<String> env;

    public MRExecutor init(ObjectMap options) {
        this.options = options;
        env = options.getAsStringList(HADOOP_ENV);
        return this;
    }

    public static String getJarWithDependencies(ObjectMap options) throws StorageEngineException {
        String jar = options.getString(HadoopVariantStorageEngine.OPENCGA_STORAGE_HADOOP_JAR_WITH_DEPENDENCIES, null);
        if (jar == null) {
            throw new StorageEngineException("Missing option " + HadoopVariantStorageEngine.OPENCGA_STORAGE_HADOOP_JAR_WITH_DEPENDENCIES);
        }
        if (!Paths.get(jar).isAbsolute()) {
            jar = getOpencgaHome() + "/" + jar;
        }
        return jar;
    }

    protected static String getOpencgaHome() {
        return System.getProperty("app.home", "");
    }

    public <T extends Tool> void run(Class<T> execClass, String args, ObjectMap options, String taskDescription)
            throws StorageEngineException {
        run(execClass, Commandline.translateCommandline(args), options, taskDescription);
    }

    public <T extends Tool> void run(Class<T> execClass, String[] args, ObjectMap options, String taskDescription)
            throws StorageEngineException {
        Logger logger = LoggerFactory.getLogger(MRExecutor.class);

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        logger.info("------------------------------------------------------");
        logger.info(taskDescription);
        logger.info("------------------------------------------------------");
        int exitValue = run(execClass, args, options);
        logger.info("------------------------------------------------------");
        logger.info("Exit value: {}", exitValue);
        logger.info("Total time: {}s", (stopWatch.now(TimeUnit.MILLISECONDS)) / 1000.0);

        if (exitValue != 0) {
            throw new StorageEngineException("Error executing MapReduce for : \"" + taskDescription + "\"");
        }
    }

    public <T extends Tool> int run(Class<T> execClass, String[] args, ObjectMap options) throws StorageEngineException {
        String hadoopRoute = options.getString(HADOOP_BIN, "hadoop");
        String jar = getJarWithDependencies(options);
        String executable = hadoopRoute + " jar " + jar + ' ' + execClass.getName();
        Logger logger = LoggerFactory.getLogger(MRExecutor.class);
        if (logger.isDebugEnabled()) {
            logger.debug(executable + ' ' + Arrays.toString(args));
        }

        return run(executable, Commandline.toString(args));
    }

    public abstract int run(String executable, String args);

    protected ObjectMap getOptions() {
        return options;
    }

    protected List<String> getEnv() {
        return env;
    }


}
