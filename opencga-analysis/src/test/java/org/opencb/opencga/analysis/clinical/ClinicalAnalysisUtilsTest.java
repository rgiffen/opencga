package org.opencb.opencga.analysis.clinical;

import org.apache.commons.lang3.RandomStringUtils;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.managers.AbstractClinicalManagerTest;
import org.opencb.opencga.catalog.managers.CatalogManagerExternalResource;
import org.opencb.opencga.storage.core.StorageEngineFactory;
import org.opencb.opencga.storage.core.config.StorageConfiguration;
import org.opencb.opencga.storage.core.exceptions.StorageEngineException;
import org.opencb.opencga.storage.core.manager.variant.VariantStorageManager;
import org.opencb.opencga.storage.core.variant.VariantStorageEngine;
import org.opencb.opencga.storage.mongodb.variant.MongoDBVariantStorageEngine;
import org.opencb.opencga.storage.mongodb.variant.MongoDBVariantStorageTest;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class ClinicalAnalysisUtilsTest {

    public static AbstractClinicalManagerTest getClinicalTest(CatalogManagerExternalResource catalogManagerResource,
                                                              MongoDBVariantStorageEngine variantStorageEngine) throws IOException, CatalogException, URISyntaxException, StorageEngineException {

        AbstractClinicalManagerTest clinicalTest = new AbstractClinicalManagerTest();

        clinicalTest.catalogManagerResource = catalogManagerResource;
        clinicalTest.setUp();

        // Copy config files in the OpenCGA home conf folder
        Files.createDirectory(catalogManagerResource.getOpencgaHome().resolve("conf"));
        catalogManagerResource.getConfiguration().serialize(
                new FileOutputStream(catalogManagerResource.getOpencgaHome().resolve("conf").resolve("configuration.yml").toString()));

        InputStream storageConfigurationStream = MongoDBVariantStorageTest.class.getClassLoader()
                .getResourceAsStream("storage-configuration-test.yml");
        Files.copy(storageConfigurationStream, catalogManagerResource.getOpencgaHome().resolve("conf").resolve("storage-configuration.yml"),
                StandardCopyOption.REPLACE_EXISTING);

        ObjectMap storageOptions = new ObjectMap()
                .append(VariantStorageEngine.Options.ANNOTATE.key(), true)
                .append(VariantStorageEngine.Options.CALCULATE_STATS.key(), false);

        StorageConfiguration configuration = variantStorageEngine.getConfiguration();
        configuration.setDefaultStorageEngineId(variantStorageEngine.getStorageEngineId());
        StorageEngineFactory storageEngineFactory = StorageEngineFactory.get(configuration);
        storageEngineFactory.registerVariantStorageEngine(variantStorageEngine);

        VariantStorageManager variantStorageManager = new VariantStorageManager(catalogManagerResource.getCatalogManager(), storageEngineFactory);

        Path outDir = Paths.get("target/test-data").resolve("junit_clinical_analysis_" + RandomStringUtils.randomAlphabetic(10));
        Files.createDirectories(outDir);

        variantStorageManager.index(clinicalTest.studyFqn, "family.vcf", outDir.toString(), storageOptions, clinicalTest.token);

        return clinicalTest;
    }
}
