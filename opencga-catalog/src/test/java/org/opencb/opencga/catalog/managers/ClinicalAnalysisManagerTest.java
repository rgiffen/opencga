package org.opencb.opencga.catalog.managers;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.opencb.biodata.models.clinical.interpretation.Comment;
import org.opencb.biodata.models.commons.Analyst;
import org.opencb.biodata.models.commons.Phenotype;
import org.opencb.biodata.models.commons.Software;
import org.opencb.biodata.models.pedigree.Multiples;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.commons.test.GenericTest;
import org.opencb.opencga.catalog.db.api.ClinicalAnalysisDBAdaptor;
import org.opencb.opencga.catalog.db.api.InterpretationDBAdaptor;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.core.models.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class ClinicalAnalysisManagerTest extends GenericTest {

    public final static String PASSWORD = "asdf";
    public final static String STUDY = "user@1000G:phase1";
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Rule
    public CatalogManagerExternalResource catalogManagerResource = new CatalogManagerExternalResource();

    protected CatalogManager catalogManager;
    private FamilyManager familyManager;
    protected String sessionIdUser;

    @Before
    public void setUp() throws IOException, CatalogException {
        catalogManager = catalogManagerResource.getCatalogManager();
        familyManager = catalogManager.getFamilyManager();
        setUpCatalogManager(catalogManager);
    }

    public void setUpCatalogManager(CatalogManager catalogManager) throws IOException, CatalogException {

        catalogManager.getUserManager().create("user", "User Name", "mail@ebi.ac.uk", PASSWORD, "", null, Account.Type.FULL, null, null);
        sessionIdUser = catalogManager.getUserManager().login("user", PASSWORD);

        String projectId = catalogManager.getProjectManager().create("1000G", "Project about some genomes", "", "ACME", "Homo sapiens",
                null, null, "GRCh38", new QueryOptions(), sessionIdUser).first().getId();
        catalogManager.getStudyManager().create(projectId, "phase1", null, "Phase 1", Study.Type.TRIO, null, "Done", null, null, null, null, null, null, null, null, sessionIdUser);

    }

    @After
    public void tearDown() throws Exception {
    }

    private QueryResult<Family> createDummyFamily() throws CatalogException {
        Phenotype disease1 = new Phenotype("dis1", "Disease 1", "HPO");
        Phenotype disease2 = new Phenotype("dis2", "Disease 2", "HPO");

        Individual father = new Individual().setId("father").setPhenotypes(Arrays.asList(new Phenotype("dis1", "dis1", "OT")));
        Individual mother = new Individual().setId("mother").setPhenotypes(Arrays.asList(new Phenotype("dis2", "dis2", "OT")));

        // We create a new father and mother with the same information to mimic the behaviour of the webservices. Otherwise, we would be
        // ingesting references to exactly the same object and this test would not work exactly the same way.
        Individual relFather = new Individual().setId("father").setPhenotypes(Arrays.asList(new Phenotype("dis1", "dis1", "OT")));
        Individual relMother = new Individual().setId("mother").setPhenotypes(Arrays.asList(new Phenotype("dis2", "dis2", "OT")));

        Individual relChild1 = new Individual().setId("child1")
                .setPhenotypes(Arrays.asList(new Phenotype("dis1", "dis1", "OT"), new Phenotype("dis2", "dis2", "OT")))
                .setFather(father)
                .setMother(mother)
                .setSamples(Arrays.asList(
                        new Sample().setId("sample1"),
                        new Sample().setId("sample2"),
                        new Sample().setId("sample3"),
                        new Sample().setId("sample4")
                ))
                .setMultiples(new Multiples("multiples", Arrays.asList("child2", "child3")))
                .setParentalConsanguinity(true);
        Individual relChild2 = new Individual().setId("child2")
                .setPhenotypes(Arrays.asList(new Phenotype("dis1", "dis1", "OT")))
                .setFather(father)
                .setMother(mother)
                .setMultiples(new Multiples("multiples", Arrays.asList("child1", "child3")))
                .setParentalConsanguinity(true);
        Individual relChild3 = new Individual().setId("child3")
                .setPhenotypes(Arrays.asList(new Phenotype("dis1", "dis1", "OT")))
                .setFather(father)
                .setMother(mother)
                .setMultiples(new Multiples("multiples", Arrays.asList("child1", "child2")))
                .setParentalConsanguinity(true);

        Family family = new Family("family", "family", Arrays.asList(disease1, disease2), null,
                Arrays.asList(relChild1, relChild2, relChild3, relFather, relMother), "", -1,
                Collections.emptyList(), Collections.emptyMap());

        return familyManager.create(STUDY, family, QueryOptions.empty(), sessionIdUser);
    }

    private QueryResult<ClinicalAnalysis> createDummyEnvironment(boolean createFamily) throws CatalogException {

        createDummyFamily();
        ClinicalAnalysis clinicalAnalysis = new ClinicalAnalysis()
                .setId("analysis").setDescription("My description").setType(ClinicalAnalysis.Type.FAMILY)
                .setDueDate("20180510100000")
                .setProband(new Individual().setId("child1").setSamples(Arrays.asList(new Sample().setId("sample2"))));

        if (createFamily) {
            clinicalAnalysis.setFamily(new Family().setId("family")
                    .setMembers(Arrays.asList(new Individual().setId("child1").setSamples(Arrays.asList(new Sample().setId("sample2"))))));
        }

        return catalogManager.getClinicalAnalysisManager().create(STUDY, clinicalAnalysis, QueryOptions.empty(), sessionIdUser);
    }

    @Test
    public void createClinicalAnalysisTest() throws CatalogException {
        QueryResult<ClinicalAnalysis> dummyEnvironment = createDummyEnvironment(true);

        assertEquals(1, dummyEnvironment.getNumResults());
        assertEquals(0, dummyEnvironment.first().getInterpretations().size());

        assertEquals("family", dummyEnvironment.first().getFamily().getId());
        assertEquals(1, dummyEnvironment.first().getFamily().getMembers().size());

        assertEquals("child1", dummyEnvironment.first().getFamily().getMembers().get(0).getId());
        assertEquals(1, dummyEnvironment.first().getFamily().getMembers().get(0).getSamples().size());
        assertEquals("sample2", dummyEnvironment.first().getFamily().getMembers().get(0).getSamples().get(0).getId());

        assertNotNull(dummyEnvironment.first().getProband());
        assertEquals("child1", dummyEnvironment.first().getProband().getId());

        assertEquals(1, dummyEnvironment.first().getProband().getSamples().size());
        assertEquals("sample2", dummyEnvironment.first().getProband().getSamples().get(0).getId());

        assertEquals(catalogManager.getSampleManager().get(STUDY, "sample2", SampleManager.INCLUDE_SAMPLE_IDS, sessionIdUser)
                .first().getUid(), dummyEnvironment.first().getProband().getSamples().get(0).getUid());
    }

    @Test
    public void checkFamilyMembersOrder() throws CatalogException {
        QueryResult<Family> dummyFamily = createDummyFamily();

        // Remove all samples from the dummy family to avoid errors
        for (Individual member : dummyFamily.first().getMembers()) {
            member.setSamples(null);
        }

        ClinicalAnalysis clinicalAnalysis = new ClinicalAnalysis()
                .setId("analysis").setDescription("My description").setType(ClinicalAnalysis.Type.FAMILY)
                .setDueDate("20180510100000")
                .setProband(new Individual().setId("child1"));
        clinicalAnalysis.setFamily(dummyFamily.first());
        QueryResult<ClinicalAnalysis> clinicalAnalysisQueryResult = catalogManager.getClinicalAnalysisManager().create(STUDY,
                clinicalAnalysis, QueryOptions.empty(), sessionIdUser);

        assertEquals("child1", clinicalAnalysisQueryResult.first().getFamily().getMembers().get(0).getId());
        assertEquals("father", clinicalAnalysisQueryResult.first().getFamily().getMembers().get(1).getId());
        assertEquals("mother", clinicalAnalysisQueryResult.first().getFamily().getMembers().get(2).getId());
        assertEquals("child2", clinicalAnalysisQueryResult.first().getFamily().getMembers().get(3).getId());
        assertEquals("child3", clinicalAnalysisQueryResult.first().getFamily().getMembers().get(4).getId());
    }

    @Test
    public void createInterpretationTest() throws CatalogException {
        QueryResult<ClinicalAnalysis> dummyEnvironment = createDummyEnvironment(true);

        Interpretation i = new Interpretation()
                .setId("interpretationId")
                .setDescription("description")
                .setClinicalAnalysisId(dummyEnvironment.first().getId())
                .setSoftware(new Software("name", "version", "repo", "commit", "web", Collections.emptyMap()))
                .setAnalyst(new Analyst("user2", "mail@mail.com", "company"))
                .setComments(Collections.singletonList(new Comment("author", "type", "comment 1", "date")))
                .setPrimaryFindings(Collections.emptyList());

        QueryResult<Interpretation> interpretationQueryResult = catalogManager.getInterpretationManager()
                .create(STUDY, dummyEnvironment.first().getId(), i, QueryOptions.empty(), sessionIdUser);
        System.out.println(interpretationQueryResult.first());

        QueryResult<ClinicalAnalysis> clinicalAnalysisQueryResult = catalogManager.getClinicalAnalysisManager().get(STUDY,
                dummyEnvironment.first().getId(), QueryOptions.empty(), sessionIdUser);
        assertEquals(1, clinicalAnalysisQueryResult.first().getInterpretations().size());
        assertEquals("interpretationId", clinicalAnalysisQueryResult.first().getInterpretations().get(0).getId());
        assertEquals("description", clinicalAnalysisQueryResult.first().getInterpretations().get(0).getDescription());

        clinicalAnalysisQueryResult = catalogManager.getClinicalAnalysisManager().get(STUDY,
                dummyEnvironment.first().getId(), new QueryOptions(QueryOptions.INCLUDE, "interpretations.id"), sessionIdUser);
        assertEquals(1, clinicalAnalysisQueryResult.first().getInterpretations().size());
        assertEquals("interpretationId", clinicalAnalysisQueryResult.first().getInterpretations().get(0).getId());
        assertEquals(null, clinicalAnalysisQueryResult.first().getInterpretations().get(0).getDescription());
    }

    @Test
    public void createClinicalAnalysisNoFamilyTest() throws CatalogException {
        QueryResult<ClinicalAnalysis> dummyEnvironment = createDummyEnvironment(false);

        assertEquals(1, dummyEnvironment.getNumResults());
        assertEquals(0, dummyEnvironment.first().getInterpretations().size());

        assertEquals(catalogManager.getIndividualManager().get(STUDY, "child1", IndividualManager.INCLUDE_INDIVIDUAL_IDS, sessionIdUser)
                .first().getUid(), dummyEnvironment.first().getProband().getUid());
        assertEquals(1, dummyEnvironment.first().getProband().getSamples().size());
        assertEquals(catalogManager.getSampleManager().get(STUDY, "sample2", SampleManager.INCLUDE_SAMPLE_IDS, sessionIdUser)
                .first().getUid(), dummyEnvironment.first().getProband().getSamples().get(0).getUid());
    }

    @Test
    public void updateSubjectsNoFamilyTest() throws CatalogException {
        createDummyEnvironment(false);

        ObjectMap params = new ObjectMap(ClinicalAnalysisDBAdaptor.QueryParams.PROBAND.key(),
                new Individual().setId("child1").setSamples(Arrays.asList(new Sample().setId("sample2"))));
        QueryResult<ClinicalAnalysis> updateResult = catalogManager.getClinicalAnalysisManager().update(STUDY, "analysis", params,
                QueryOptions.empty(), sessionIdUser);

        assertEquals(1, updateResult.getNumResults());
        assertEquals(0, updateResult.first().getInterpretations().size());

        assertEquals(catalogManager.getIndividualManager().get(STUDY, "child1", IndividualManager.INCLUDE_INDIVIDUAL_IDS, sessionIdUser)
                .first().getUid(), updateResult.first().getProband().getUid());
        assertEquals(1, updateResult.first().getProband().getSamples().size());
        assertEquals(catalogManager.getSampleManager().get(STUDY, "sample2", SampleManager.INCLUDE_SAMPLE_IDS, sessionIdUser)
                .first().getUid(), updateResult.first().getProband().getSamples().get(0).getUid());
    }

    @Test
    public void updateSubjectsAndFamilyTest() throws CatalogException {
        createDummyEnvironment(false);

        ObjectMap params = new ObjectMap()
                .append(ClinicalAnalysisDBAdaptor.QueryParams.PROBAND.key(),
                        new Individual().setId("child1").setSamples(Arrays.asList(new Sample().setId("sample2"))))
                .append(ClinicalAnalysisDBAdaptor.QueryParams.FAMILY.key(), new Family().setId("family")
                        .setMembers(Arrays.asList(new Individual().setId("child1").setSamples(Arrays.asList(new Sample().setId("sample2"))))));
        QueryResult<ClinicalAnalysis> updateResult = catalogManager.getClinicalAnalysisManager().update(STUDY, "analysis", params,
                QueryOptions.empty(), sessionIdUser);

        assertEquals(1, updateResult.getNumResults());
        assertEquals(0, updateResult.first().getInterpretations().size());

        assertEquals(catalogManager.getFamilyManager().get(STUDY, "family", FamilyManager.INCLUDE_FAMILY_IDS, sessionIdUser)
                .first().getUid(), updateResult.first().getFamily().getUid());
        assertEquals(catalogManager.getIndividualManager().get(STUDY, "child1", IndividualManager.INCLUDE_INDIVIDUAL_IDS, sessionIdUser)
                .first().getUid(), updateResult.first().getProband().getUid());
        assertEquals(1, updateResult.first().getProband().getSamples().size());
        assertEquals(catalogManager.getSampleManager().get(STUDY, "sample2", SampleManager.INCLUDE_SAMPLE_IDS, sessionIdUser)
                .first().getUid(), updateResult.first().getProband().getSamples().get(0).getUid());
    }

}
