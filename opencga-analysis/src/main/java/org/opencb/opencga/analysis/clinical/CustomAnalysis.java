package org.opencb.opencga.analysis.clinical;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.opencb.biodata.models.clinical.interpretation.*;
import org.opencb.biodata.models.clinical.interpretation.ClinicalProperty.RoleInCancer;
import org.opencb.biodata.models.commons.Analyst;
import org.opencb.biodata.models.commons.Disorder;
import org.opencb.biodata.models.commons.Software;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.annotation.ConsequenceTypeMappings;
import org.opencb.biodata.tools.clinical.DefaultReportedVariantCreator;
import org.opencb.cellbase.client.rest.CellBaseClient;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.commons.utils.ListUtils;
import org.opencb.opencga.analysis.exceptions.AnalysisException;
import org.opencb.opencga.catalog.db.api.UserDBAdaptor;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.core.common.TimeUtils;
import org.opencb.opencga.core.models.ClinicalAnalysis;
import org.opencb.opencga.core.models.File;
import org.opencb.opencga.core.models.Panel;
import org.opencb.opencga.core.models.User;
import org.opencb.opencga.core.results.VariantQueryResult;
import org.opencb.opencga.storage.core.StorageEngineFactory;
import org.opencb.opencga.storage.core.manager.AlignmentStorageManager;
import org.opencb.opencga.storage.core.manager.variant.VariantCatalogQueryUtils;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam;

import java.util.*;
import java.util.stream.Collectors;

public class CustomAnalysis extends FamilyAnalysis<Interpretation> {

    private Query query;

    private CellBaseClient cellBaseClient;
    private AlignmentStorageManager alignmentStorageManager;

    private final static String CUSTOM_ANALYSIS_NAME = "Custom";

    public CustomAnalysis(String clinicalAnalysisId, Query query, String studyStr, Map<String, RoleInCancer> roleInCancer,
                          Map<String, List<String>> actionableVariants, ClinicalProperty.Penetrance penetrance, ObjectMap options,
                          String opencgaHome, String token) {
        super(clinicalAnalysisId, null, roleInCancer, actionableVariants, penetrance, options, studyStr, opencgaHome, token);

        this.query = query;

        this.cellBaseClient = new CellBaseClient(storageConfiguration.getCellbase().toClientConfiguration());
        this.alignmentStorageManager = new AlignmentStorageManager(catalogManager, StorageEngineFactory.get(storageConfiguration));
    }

    @Override
    public InterpretationResult execute() throws Exception {
        StopWatch watcher = StopWatch.createStarted();

        ClinicalAnalysis clinicalAnalysis = null;
        String probandSampleId = null;
        Disorder disorder = null;
        ClinicalProperty.ModeOfInheritance moi = null;

        List<String> biotypes = null;
        List<String> soNames = null;

        Map<String, List<File>> files = null;

        // We allow query to be empty, it is likely that we will add some filters from CA
        if (query == null) {
            query = new Query(VariantQueryParam.STUDY.key(), studyStr);
        }

        String segregation = this.query.getString(VariantCatalogQueryUtils.FAMILY_SEGREGATION.key());

        if (!query.containsKey(VariantQueryParam.STUDY.key())) {
            query.put(VariantQueryParam.STUDY.key(), studyStr);
        }

        // Check clinical analysis (only when sample proband ID is not provided)
        if (clinicalAnalysisId != null) {
            QueryResult<ClinicalAnalysis> clinicalAnalysisQueryResult = catalogManager.getClinicalAnalysisManager().get(studyStr,
                    clinicalAnalysisId, QueryOptions.empty(), token);
            if (clinicalAnalysisQueryResult.getNumResults() != 1) {
                throw new AnalysisException("Clinical analysis " + clinicalAnalysisId + " not found in study " + studyStr);
            }

            clinicalAnalysis = clinicalAnalysisQueryResult.first();

            // Proband ID
            if (clinicalAnalysis.getProband() != null) {
                probandSampleId = clinicalAnalysis.getProband().getId();
            }

            // Family parameter
            if (clinicalAnalysis.getFamily() != null) {
                // Query contains a different family than ClinicAnalysis
                if (query.containsKey(VariantCatalogQueryUtils.FAMILY.key())
                        && !clinicalAnalysis.getFamily().getId().equals(query.get(VariantCatalogQueryUtils.FAMILY.key()))) {
                    logger.warn("Two families passed");
                } else {
                    query.put(VariantCatalogQueryUtils.FAMILY.key(), clinicalAnalysis.getFamily().getId());
                }
            } else {
                // Individual parameter
                if (clinicalAnalysis.getProband() != null) {
                    // Query contains a different sample than ClinicAnalysis
                    if (query.containsKey(VariantQueryParam.SAMPLE.key())
                            && !clinicalAnalysis.getProband().getId().equals(query.get(VariantQueryParam.SAMPLE.key()))) {
                        logger.warn("Two samples passed");
                    } else {
                        query.put(VariantQueryParam.SAMPLE.key(), clinicalAnalysis.getProband().getId());
                    }
                }
            }

            if (clinicalAnalysis.getFiles() != null && clinicalAnalysis.getFiles().size() > 0) {
                files = clinicalAnalysis.getFiles();
            }

            if (clinicalAnalysis.getDisorder() != null) {
                disorder = clinicalAnalysis.getDisorder();
                if (StringUtils.isNotEmpty(segregation) && disorder != null) {
                    query.put(VariantCatalogQueryUtils.FAMILY_DISORDER.key(), disorder.getId());
                    query.put(VariantCatalogQueryUtils.FAMILY_SEGREGATION.key(), segregation);
                }
            }
        }

        // Check Query looks fine for Interpretation
        if (!query.containsKey(VariantQueryParam.GENOTYPE.key()) && !query.containsKey(VariantQueryParam.SAMPLE.key())) {
            // TODO check query is correct
        }

        // Get and check panels
        List<DiseasePanel> diseasePanels = new ArrayList<>();
        if (query.get(VariantCatalogQueryUtils.PANEL.key()) != null) {
            List<String> diseasePanelIds = Arrays.asList(query.getString(VariantCatalogQueryUtils.PANEL.key()).split(","));
            List<QueryResult<Panel>> queryResults = catalogManager.getPanelManager()
                    .get(studyStr, diseasePanelIds, QueryOptions.empty(), token);

            if (queryResults.size() != diseasePanelIds.size()) {
                throw new AnalysisException("The number of disease panels retrieved doesn't match the number of " +
                        "disease panels queried");
            }

            for (QueryResult<Panel> queryResult : queryResults) {
                if (queryResult.getNumResults() != 1) {
                    throw new AnalysisException("The number of disease panels retrieved doesn't match the number of " +
                            "disease panels queried");
                }
                diseasePanels.add(queryResult.first());
            }
        }

        QueryOptions queryOptions = new QueryOptions(config);
//        queryOptions.add(QueryOptions.LIMIT, 20);

        List<Variant> variants = new ArrayList<>();
        boolean skipDiagnosticVariants = config.getBoolean(SKIP_DIAGNOSTIC_VARIANTS_PARAM, false);
        boolean skipUntieredVariants = config.getBoolean(SKIP_UNTIERED_VARIANTS_PARAM, false);


        // Diagnostic variants ?
        if (!skipDiagnosticVariants) {
            List<DiseasePanel.VariantPanel> diagnosticVariants = new ArrayList<>();
            for (DiseasePanel diseasePanel : diseasePanels) {
                if (diseasePanel != null && CollectionUtils.isNotEmpty(diseasePanel.getVariants())) {
                    diagnosticVariants.addAll(diseasePanel.getVariants());
                }
            }

            query.put(VariantQueryParam.ID.key(), StringUtils.join(diagnosticVariants.stream()
                    .map(DiseasePanel.VariantPanel::getId).collect(Collectors.toList()), ","));
        }

        int dbTime = -1;
        long numTotalResult = -1;

        if (StringUtils.isNotEmpty(segregation) && (segregation.equalsIgnoreCase(ClinicalProperty.ModeOfInheritance.DE_NOVO.toString())
                || segregation.equalsIgnoreCase(ClinicalProperty.ModeOfInheritance.COMPOUND_HETEROZYGOUS.toString()))) {
            if (segregation.equalsIgnoreCase(ClinicalProperty.ModeOfInheritance.DE_NOVO.toString())) {
                StopWatch watcher2 = StopWatch.createStarted();
                moi = ClinicalProperty.ModeOfInheritance.DE_NOVO;
                DeNovoAnalysis deNovoAnalysis = new DeNovoAnalysis(clinicalAnalysisId, diseasePanelIds, query, roleInCancer,
                        actionableVariants, config, studyStr, opencgaHome, token);
                variants = deNovoAnalysis.execute().getResult();
                dbTime = Math.toIntExact(watcher2.getTime());
            } else {
                moi = ClinicalProperty.ModeOfInheritance.COMPOUND_HETEROZYGOUS;
            }
        } else {
            if (StringUtils.isNotEmpty(segregation)) {
                try {
                    moi = ClinicalProperty.ModeOfInheritance.valueOf(segregation);
                } catch (IllegalArgumentException e) {
                    moi = null;
                }
            }

            // Execute query
            VariantQueryResult<Variant> variantQueryResult = variantStorageManager.get(query, queryOptions, token);
            dbTime = variantQueryResult.getDbTime();
            numTotalResult = variantQueryResult.getNumTotalResults();

        if (CollectionUtils.isNotEmpty(variantQueryResult.getResult())) {
                variants.addAll(variantQueryResult.getResult());
            }

            if (CollectionUtils.isNotEmpty(variants)) {
                // Get biotypes and SO names
                if (query.containsKey(VariantQueryParam.ANNOT_BIOTYPE.key())
                        && StringUtils.isNotEmpty(query.getString(VariantQueryParam.ANNOT_BIOTYPE.key()))) {
                    biotypes = Arrays.asList(query.getString(VariantQueryParam.ANNOT_BIOTYPE.key()).split(","));
                }
                if (query.containsKey(VariantQueryParam.ANNOT_CONSEQUENCE_TYPE.key())
                        && StringUtils.isNotEmpty(query.getString(VariantQueryParam.ANNOT_CONSEQUENCE_TYPE.key()))) {
                    soNames = new ArrayList<>();
                    for (String soName : query.getString(VariantQueryParam.ANNOT_CONSEQUENCE_TYPE.key()).split(",")) {
                        if (soName.startsWith("SO:")) {
                            try {
                                int soAcc = Integer.valueOf(soName.replace("SO:", ""));
                                soNames.add(ConsequenceTypeMappings.accessionToTerm.get(soAcc));
                            } catch (NumberFormatException e) {
                                logger.warn("Unknown SO term: " + soName);
                            }
                        } else {
                            soNames.add(soName);
                        }
                    }
                }
            }
        }

        // Primary findings and creator
        List<ReportedVariant> primaryFindings;
        DefaultReportedVariantCreator creator;

        creator = new DefaultReportedVariantCreator(roleInCancer, actionableVariants, disorder, moi,
                ClinicalProperty.Penetrance.COMPLETE, diseasePanels, biotypes, soNames, !skipUntieredVariants);

        if (moi == ClinicalProperty.ModeOfInheritance.COMPOUND_HETEROZYGOUS) {
            // Add compound heterozyous variants
            StopWatch watcher2 = StopWatch.createStarted();
            CompoundHeterozygousAnalysis compoundAnalysis = new CompoundHeterozygousAnalysis(clinicalAnalysisId, diseasePanelIds, query,
                    roleInCancer, actionableVariants, config, studyStr, opencgaHome, token);
            primaryFindings = getCompoundHeterozygousReportedVariants(compoundAnalysis.execute().getResult(), creator);
            dbTime = Math.toIntExact(watcher2.getTime());
        } else {
            // Other mode of inheritance
            primaryFindings = creator.create(variants);
        }

        // Secondary findings, if clinical consent is TRUE
        List<ReportedVariant> secondaryFindings = null;
        if (clinicalAnalysis != null) {
            secondaryFindings = getSecondaryFindings(clinicalAnalysis, primaryFindings,
                    query.getAsStringList(VariantQueryParam.SAMPLE.key()), creator);
        }

        // Low coverage support
        List<ReportedLowCoverage> reportedLowCoverages = new ArrayList<>();
        calculateLowCoverageRegions(probandSampleId, files, diseasePanels, reportedLowCoverages);

        Interpretation interpretation = generateInterpretation(primaryFindings, secondaryFindings, diseasePanels,
                reportedLowCoverages);

        int numberOfResults = primaryFindings != null ? primaryFindings.size() : 0;


        // Return interpretation result
        return new InterpretationResult(
                interpretation,
                Math.toIntExact(watcher.getTime()),
                new HashMap<>(),
                dbTime,
                numberOfResults,
                numTotalResult,
                "",
                "");
    }

    Interpretation generateInterpretation(List<ReportedVariant> primaryFindings, List<ReportedVariant> secondaryFindings,
                                          List<DiseasePanel> biodataDiseasePanels, List<ReportedLowCoverage> reportedLowCoverages)
            throws CatalogException {
        String userId = catalogManager.getUserManager().getUserId(token);
        QueryResult<User> userQueryResult = catalogManager.getUserManager().get(userId, new QueryOptions(QueryOptions.INCLUDE,
                Arrays.asList(UserDBAdaptor.QueryParams.EMAIL.key(), UserDBAdaptor.QueryParams.ORGANIZATION.key())), token);

        // Create Interpretation
        return new Interpretation()
                .setId(CUSTOM_ANALYSIS_NAME + SEPARATOR + TimeUtils.getTimeMillis())
                .setPrimaryFindings(primaryFindings)
                .setSecondaryFindings(secondaryFindings)
                .setLowCoverageRegions(reportedLowCoverages)
                .setAnalyst(new Analyst(userId, userQueryResult.first().getEmail(), userQueryResult.first().getOrganization()))
                .setClinicalAnalysisId(clinicalAnalysisId)
                .setCreationDate(TimeUtils.getTime())
                .setPanels(biodataDiseasePanels)
                .setFilters(query)
                .setSoftware(new Software().setName(CUSTOM_ANALYSIS_NAME));
    }

    void calculateLowCoverageRegions(String probandSampleId, Map<String, List<File>> files, List<DiseasePanel> diseasePanels,
                                     List<ReportedLowCoverage> reportedLowCoverages) {
        if (config.getBoolean(INCLUDE_LOW_COVERAGE_PARAM, false)) {
            String bamFileId = null;
            if (files != null) {
                for (String sampleId : files.keySet()) {
                    if (sampleId.equals(probandSampleId)) {
                        for (File file : files.get(sampleId)) {
                            if (File.Format.BAM.equals(file.getFormat())) {
                                bamFileId = file.getUuid();
                            }
                        }
                    }
                }
            }

            if (bamFileId != null) {
                // We need the genes from Query.gene and Query.panel
                Set<String> genes = new HashSet<>();
                if (query.get(VariantQueryParam.GENE.key()) != null) {
                    genes.addAll(Arrays.asList(query.getString(VariantQueryParam.GENE.key()).split(",")));
                }
                for (DiseasePanel diseasePanel : diseasePanels) {
                    for (DiseasePanel.GenePanel genePanel : diseasePanel.getGenes()) {
                        genes.add(genePanel.getId());
                    }
                }

                // Compute low coverage for genes found
                int maxCoverage = config.getInt(MAX_LOW_COVERAGE_PARAM, LOW_COVERAGE_DEFAULT);
                Iterator<String> iterator = genes.iterator();
                while (iterator.hasNext()) {
                    String geneName = iterator.next();
                    List<ReportedLowCoverage> lowCoverages = getReportedLowCoverages(geneName, bamFileId, maxCoverage);
                    if (ListUtils.isNotEmpty(lowCoverages)) {
                        reportedLowCoverages.addAll(lowCoverages);
                    }
                }
            }
        }
    }

    public Query getQuery() {
        return query;
    }

    public CustomAnalysis setQuery(Query query) {
        this.query = query;
        return this;
    }
}
