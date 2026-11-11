package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.prd.PrdAcceptanceTestSupport.InMemoryAcceptanceDao;
import com.aliyun.autowonder.prd.PrdAcceptanceTestSupport.InMemoryArtifactDao;
import com.aliyun.autowonder.prd.dto.PrdDeliveryAcceptanceVO;
import com.aliyun.autowonder.prd.dto.RequirementAcceptanceReport;
import com.aliyun.autowonder.prd.dto.RequirementAcceptanceVO;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-requirement acceptance. These are the behaviours the delivery contract calls out as
 * non-negotiable: every confirmed requirement has a result row before the executor reports anything,
 * a row is NOT_VERIFIED until an explicit report moves it, PASSED needs evidence, NOT_APPLICABLE needs
 * a reason, and a report may only name a requirement the user actually confirmed.
 */
class PrdAcceptanceServiceTest {

    private static final long WS = 1L;
    private static final long WORKITEM = 10L;
    private static final long DISPATCH = 77L;
    private static final long VERSION = 5001L;
    private static final long REPORTER = 9L;

    @Test
    void seedingCreatesOneNotVerifiedRowPerActiveRequirement() {
        Fixture f = new Fixture();

        int seeded = f.given(design("REQ-001", "REQ-002"));

        assertEquals(2, seeded);
        assertEquals(2, f.acceptances.size());
        for (PrdRequirementAcceptanceDO row : f.acceptances.listByDispatch(WS, DISPATCH)) {
            assertEquals(AcceptanceVerdict.NOT_VERIFIED, row.getVerdict(), "seeded rows are never passed");
            assertEquals(ImplementationResult.NOT_IMPLEMENTED, row.getImplementation());
            assertFalse(AcceptanceVerdict.isVerified(row.getVerdict()));
        }
    }

    @Test
    void seedingIsIdempotentAndNeverResetsAnExistingReport() {
        Fixture f = new Fixture();
        f.given(design("REQ-001"));
        long artifactId = f.artifactFor(DISPATCH).getId();
        f.service.report(WS, DISPATCH, List.of(passed("REQ-001", artifactId)), REPORTER);

        // Re-packaging the same dispatch re-seeds; the report must survive.
        int seeded = f.service.seed(WS, f.binding(), design("REQ-001"));

        assertEquals(0, seeded, "a duplicate natural key inserts nothing");
        List<RequirementAcceptanceVO> rows = f.service.listForDispatch(WS, DISPATCH);
        assertEquals(1, rows.size());
        assertEquals(AcceptanceVerdict.PASSED, rows.get(0).getVerdict(), "re-seeding did not reset the report");
        assertTrue(rows.get(0).isVerified());
    }

    @Test
    void excludedRequirementIsSeededAsNotApplicableWithADesignReason() {
        Fixture f = new Fixture();
        PrdDesignData data = design("REQ-001");
        PrdDesignData.Requirement excluded = new PrdDesignData.Requirement();
        excluded.setKey("REQ-002");
        excluded.setTitle("本期不做");
        excluded.setExcluded(true);
        data.getRequirements().add(excluded);

        f.given(data);

        Map<String, PrdRequirementAcceptanceDO> byKey = byKey(f.acceptances.listByDispatch(WS, DISPATCH));
        assertEquals(AcceptanceVerdict.NOT_VERIFIED, byKey.get("REQ-001").getVerdict());
        assertEquals(AcceptanceVerdict.NOT_APPLICABLE, byKey.get("REQ-002").getVerdict());
        assertEquals(PrdAcceptanceService.EXCLUDED_BY_DESIGN, byKey.get("REQ-002").getNotApplicableReason());
    }

    @Test
    void passedWithoutEvidenceIsRefused() {
        Fixture f = new Fixture();
        f.given(design("REQ-001"));

        RequirementAcceptanceReport report = new RequirementAcceptanceReport();
        report.setRequirementKey("REQ-001");
        report.setImplementation(ImplementationResult.IMPLEMENTED);
        report.setVerdict(AcceptanceVerdict.PASSED);

        BizException e = assertThrows(BizException.class,
                () -> f.service.report(WS, DISPATCH, List.of(report), REPORTER));

        assertEquals(ErrorCode.PRD_ACCEPTANCE_EVIDENCE_REQUIRED.getCode(), e.getCode());
        assertEquals(AcceptanceVerdict.NOT_VERIFIED,
                byKey(f.acceptances.listByDispatch(WS, DISPATCH)).get("REQ-001").getVerdict(),
                "the row stays unverified, never silently passed");
    }

    @Test
    void notApplicableWithoutAReasonIsRefused() {
        Fixture f = new Fixture();
        f.given(design("REQ-001"));

        RequirementAcceptanceReport report = new RequirementAcceptanceReport();
        report.setRequirementKey("REQ-001");
        report.setImplementation(ImplementationResult.NOT_IMPLEMENTED);
        report.setVerdict(AcceptanceVerdict.NOT_APPLICABLE);

        BizException e = assertThrows(BizException.class,
                () -> f.service.report(WS, DISPATCH, List.of(report), REPORTER));

        assertEquals(ErrorCode.PRD_ACCEPTANCE_REASON_REQUIRED.getCode(), e.getCode());
    }

    @Test
    void aReportMayNotNameARequirementOutsideTheFrozenDesign() {
        Fixture f = new Fixture();
        f.given(design("REQ-001"));

        RequirementAcceptanceReport report = new RequirementAcceptanceReport();
        report.setRequirementKey("REQ-999");
        report.setImplementation(ImplementationResult.IMPLEMENTED);
        report.setVerdict(AcceptanceVerdict.FAILED);

        BizException e = assertThrows(BizException.class,
                () -> f.service.report(WS, DISPATCH, List.of(report), REPORTER));

        assertEquals(ErrorCode.PRD_ACCEPTANCE_UNKNOWN_REQUIREMENT.getCode(), e.getCode());
        assertFalse(byKey(f.acceptances.listByDispatch(WS, DISPATCH)).containsKey("REQ-999"));
    }

    @Test
    void theExecutorMayNotReopenARequirementTheDesignExcluded() {
        Fixture f = new Fixture();
        PrdDesignData data = design("REQ-001");
        PrdDesignData.Requirement excluded = new PrdDesignData.Requirement();
        excluded.setKey("REQ-002");
        excluded.setTitle("本期不做");
        excluded.setExcluded(true);
        data.getRequirements().add(excluded);
        f.given(data);
        long artifactId = f.artifactFor(DISPATCH).getId();

        RequirementAcceptanceReport report = new RequirementAcceptanceReport();
        report.setRequirementKey("REQ-002");
        report.setImplementation(ImplementationResult.IMPLEMENTED);
        report.setVerdict(AcceptanceVerdict.PASSED);
        report.setEvidence(List.of(evidence(AcceptanceEvidence.ARTIFACT, String.valueOf(artifactId))));

        BizException e = assertThrows(BizException.class,
                () -> f.service.report(WS, DISPATCH, List.of(report), REPORTER));

        assertEquals(ErrorCode.PRD_ACCEPTANCE_UNKNOWN_REQUIREMENT.getCode(), e.getCode());
    }

    @Test
    void invalidVerdictAndImplementationAreRefused() {
        Fixture f = new Fixture();
        f.given(design("REQ-001"));

        RequirementAcceptanceReport badVerdict = new RequirementAcceptanceReport();
        badVerdict.setRequirementKey("REQ-001");
        badVerdict.setImplementation(ImplementationResult.IMPLEMENTED);
        badVerdict.setVerdict("PASSEDISH");
        assertEquals(ErrorCode.PRD_ACCEPTANCE_INVALID_VERDICT.getCode(), assertThrows(BizException.class,
                () -> f.service.report(WS, DISPATCH, List.of(badVerdict), REPORTER)).getCode());

        RequirementAcceptanceReport badImpl = new RequirementAcceptanceReport();
        badImpl.setRequirementKey("REQ-001");
        badImpl.setImplementation("DONE");
        badImpl.setVerdict(AcceptanceVerdict.FAILED);
        assertEquals(ErrorCode.PRD_ACCEPTANCE_INVALID_IMPLEMENTATION.getCode(), assertThrows(BizException.class,
                () -> f.service.report(WS, DISPATCH, List.of(badImpl), REPORTER)).getCode());
    }

    @Test
    void artifactEvidenceMustBelongToTheSameDispatch() {
        Fixture f = new Fixture();
        f.given(design("REQ-001"));
        ArtifactDO other = f.artifactFor(999L); // a different dispatch

        RequirementAcceptanceReport report = new RequirementAcceptanceReport();
        report.setRequirementKey("REQ-001");
        report.setImplementation(ImplementationResult.IMPLEMENTED);
        report.setVerdict(AcceptanceVerdict.PASSED);
        report.setEvidence(List.of(evidence(AcceptanceEvidence.ARTIFACT, String.valueOf(other.getId()))));

        assertEquals(ErrorCode.PRD_ACCEPTANCE_INVALID_EVIDENCE.getCode(), assertThrows(BizException.class,
                () -> f.service.report(WS, DISPATCH, List.of(report), REPORTER)).getCode());
    }

    @Test
    void anUnknownEvidenceKindIsRefused() {
        Fixture f = new Fixture();
        f.given(design("REQ-001"));

        RequirementAcceptanceReport report = new RequirementAcceptanceReport();
        report.setRequirementKey("REQ-001");
        report.setImplementation(ImplementationResult.IMPLEMENTED);
        report.setVerdict(AcceptanceVerdict.FAILED);
        report.setEvidence(List.of(evidence("PROTOTYPE", "design/prd.html")));

        assertEquals(ErrorCode.PRD_ACCEPTANCE_INVALID_EVIDENCE.getCode(), assertThrows(BizException.class,
                () -> f.service.report(WS, DISPATCH, List.of(report), REPORTER)).getCode());
    }

    @Test
    void aValidReportMovesTheRowOffNotVerifiedAndKeepsItsEvidence() {
        Fixture f = new Fixture();
        f.given(design("REQ-001", "REQ-002"));
        ArtifactDO artifact = f.artifactFor(DISPATCH);

        int written = f.service.report(WS, DISPATCH, List.of(
                passed("REQ-001", artifact.getId()),
                failed("REQ-002", "上传超过 10MB 时没有拒绝")), REPORTER);

        assertEquals(2, written);
        Map<String, PrdRequirementAcceptanceDO> byKey = byKey(f.acceptances.listByDispatch(WS, DISPATCH));
        assertEquals(AcceptanceVerdict.PASSED, byKey.get("REQ-001").getVerdict());
        assertEquals(AcceptanceVerdict.FAILED, byKey.get("REQ-002").getVerdict());
        assertNotNull(byKey.get("REQ-002").getNote());
        assertNotNull(byKey.get("REQ-001").getReportedAt());
    }

    @Test
    void theViewResolvesArtifactEvidenceToADownloadUrl() {
        Fixture f = new Fixture();
        f.given(design("REQ-001"));
        ArtifactDO artifact = f.artifactFor(DISPATCH);
        f.service.report(WS, DISPATCH, List.of(passed("REQ-001", artifact.getId())), REPORTER);

        List<RequirementAcceptanceVO> rows = f.service.listForDispatch(WS, DISPATCH);

        assertEquals(1, rows.get(0).getEvidence().size());
        RequirementAcceptanceVO.Evidence evidence = rows.get(0).getEvidence().get(0);
        assertEquals(AcceptanceEvidence.ARTIFACT, evidence.getKind());
        assertNotNull(evidence.getUrl(), "artifact evidence carries a presigned url");
    }

    @Test
    void theViewCarriesRequirementTitlesAndExclusionFromTheFrozenDesign() {
        Fixture f = new Fixture();
        PrdDesignData data = design("REQ-001");
        data.getRequirements().get(0).setTitle("上传 CSV");
        PrdDesignData.Requirement excluded = new PrdDesignData.Requirement();
        excluded.setKey("REQ-002");
        excluded.setTitle("本期不做");
        excluded.setExcluded(true);
        data.getRequirements().add(excluded);
        f.given(data);

        Map<String, RequirementAcceptanceVO> byKey = new LinkedHashMap<>();
        for (RequirementAcceptanceVO vo : f.service.listForDispatch(WS, DISPATCH)) {
            byKey.put(vo.getRequirementKey(), vo);
        }

        assertEquals("上传 CSV", byKey.get("REQ-001").getRequirementTitle());
        assertFalse(byKey.get("REQ-001").isExcluded());
        assertTrue(byKey.get("REQ-002").isExcluded());
        assertEquals(AcceptanceVerdict.NOT_APPLICABLE, byKey.get("REQ-002").getVerdict());
    }

    @Test
    void deliveryAcceptanceSummarisesCountsAndCompleteness() {
        Fixture f = new Fixture();
        f.given(design("REQ-001", "REQ-002", "REQ-003"));
        ArtifactDO artifact = f.artifactFor(DISPATCH);
        f.service.report(WS, DISPATCH, List.of(passed("REQ-001", artifact.getId())), REPORTER);

        PrdDeliveryAcceptanceVO view = f.service.deliveryAcceptance(WS, WORKITEM, DISPATCH);

        assertEquals(3, view.getTotal());
        assertEquals(1, view.getPassed());
        assertEquals(2, view.getNotVerified());
        assertEquals(0, view.getFailed());
        assertFalse(view.isComplete(), "two requirements are still unverified, so not accepted");
        assertEquals(VERSION, view.getDesignVersionId());
    }

    @Test
    void aDeliveryIsCompleteOnlyWhenNothingIsLeftUnverified() {
        Fixture f = new Fixture();
        f.given(design("REQ-001", "REQ-002"));
        ArtifactDO artifact = f.artifactFor(DISPATCH);
        f.service.report(WS, DISPATCH, List.of(
                passed("REQ-001", artifact.getId()),
                failed("REQ-002", "未完成")), REPORTER);

        PrdDeliveryAcceptanceVO view = f.service.deliveryAcceptance(WS, WORKITEM, DISPATCH);

        assertTrue(view.isComplete(), "failed is still a verdict, so nothing is left unverified");
        assertEquals(1, view.getPassed());
        assertEquals(1, view.getFailed());
    }

    @Test
    void deliveryAcceptanceRefusesADispatchOfAnotherWorkitem() {
        Fixture f = new Fixture();
        f.given(design("REQ-001"));

        assertThrows(BizException.class, () -> f.service.deliveryAcceptance(WS, 999L, DISPATCH));
    }

    @Test
    void reportingIntoADispatchWithNoFrozenDesignIsRefused() {
        Fixture f = new Fixture();
        // Nothing seeded: there is no acceptance row, hence no binding to report against.
        long artifactId = f.artifactFor(DISPATCH).getId();

        BizException e = assertThrows(BizException.class,
                () -> f.service.report(WS, DISPATCH, List.of(passed("REQ-001", artifactId)), REPORTER));

        assertEquals(ErrorCode.PRD_ACCEPTANCE_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void aReportReseedsAMissingRowFromTheDispatchAnchorWithoutLosingIt() {
        Fixture f = new Fixture();
        f.given(design("REQ-001", "REQ-002"));
        // Simulate partial seeding (e.g. an interrupted packaging): REQ-002's row is gone, REQ-001's
        // survives as the dispatch anchor. Reporting REQ-002 must re-seed it rather than fail on the
        // NOT NULL workitem_id/design_version_id the report alone cannot supply.
        f.acceptances.rows.values().removeIf(r -> "REQ-002".equals(r.getRequirementKey()));
        ArtifactDO artifact = f.artifactFor(DISPATCH);

        int written = f.service.report(WS, DISPATCH, List.of(passed("REQ-002", artifact.getId())), REPORTER);

        assertEquals(1, written);
        PrdRequirementAcceptanceDO reseeded = f.acceptances.findByDispatchAndKey(WS, DISPATCH, "REQ-002");
        assertNotNull(reseeded);
        assertEquals(AcceptanceVerdict.PASSED, reseeded.getVerdict());
        assertEquals(WORKITEM, reseeded.getWorkitemId(), "the anchor supplies the NOT NULL workitem id");
        assertEquals(VERSION, reseeded.getDesignVersionId());
    }

    private static RequirementAcceptanceReport passed(String key, long artifactId) {
        RequirementAcceptanceReport report = new RequirementAcceptanceReport();
        report.setRequirementKey(key);
        report.setImplementation(ImplementationResult.IMPLEMENTED);
        report.setVerdict(AcceptanceVerdict.PASSED);
        report.setNote("按验收标准实现");
        report.setEvidence(List.of(evidence(AcceptanceEvidence.ARTIFACT, String.valueOf(artifactId))));
        return report;
    }

    private static RequirementAcceptanceReport failed(String key, String note) {
        RequirementAcceptanceReport report = new RequirementAcceptanceReport();
        report.setRequirementKey(key);
        report.setImplementation(ImplementationResult.PARTIAL);
        report.setVerdict(AcceptanceVerdict.FAILED);
        report.setNote(note);
        return report;
    }

    private static RequirementAcceptanceReport.EvidenceRef evidence(String kind, String ref) {
        RequirementAcceptanceReport.EvidenceRef e = new RequirementAcceptanceReport.EvidenceRef();
        e.setKind(kind);
        e.setRef(ref);
        e.setTitle("验收证据");
        return e;
    }

    private static PrdDesignData design(String... keys) {
        PrdDesignData data = new PrdDesignData();
        data.setTitle("工单批量导入");
        List<PrdDesignData.Requirement> requirements = new ArrayList<>();
        for (String key : keys) {
            PrdDesignData.Requirement requirement = new PrdDesignData.Requirement();
            requirement.setKey(key);
            requirement.setTitle("需求 " + key);
            requirement.setAcceptanceCriteria(new ArrayList<>(List.of("满足标准")));
            requirements.add(requirement);
        }
        data.setRequirements(requirements);
        return data;
    }

    private static Map<String, PrdRequirementAcceptanceDO> byKey(List<PrdRequirementAcceptanceDO> rows) {
        Map<String, PrdRequirementAcceptanceDO> map = new LinkedHashMap<>();
        for (PrdRequirementAcceptanceDO row : rows) {
            map.put(row.getRequirementKey(), row);
        }
        return map;
    }

    private static final class Fixture {
        final InMemoryAcceptanceDao acceptances = new InMemoryAcceptanceDao();
        final InMemoryArtifactDao artifacts = new InMemoryArtifactDao();
        final InMemoryVersionDao versions = new InMemoryVersionDao();
        final InMemoryBindingDao bindings = new InMemoryBindingDao();
        final InMemoryObjectStorage storage = new InMemoryObjectStorage();
        final PrdAcceptanceService service;

        Fixture() {
            service = new PrdAcceptanceService(acceptances, bindings, versions, artifacts, storage);
            versions.insert(version());
            bindings.insert(binding());
        }

        ArtifactDO artifactFor(long dispatchId) {
            return artifacts.artifact(dispatchId, "evidence/acceptance.png", "test-bucket/evidence/x.png");
        }

        /**
         * Freeze a design version and seed acceptance from it, mirroring packaging: in production the
         * seeded rows and the frozen version are the same parsed model, so a report is validated
         * against exactly the scope that was seeded. Tests must keep them consistent too.
         */
        int given(PrdDesignData data) {
            PrdDesignVersionDO version = version();
            version.setDesignJson(PrdDesignCodec.serialize(data));
            version.setDesignSha256(PrdDesignCodec.digest(version.getDesignJson()));
            versions.rows.put(VERSION, version);
            return service.seed(WS, binding(), data);
        }

        PrdDeliveryBindingDO binding() {
            PrdDeliveryBindingDO binding = new PrdDeliveryBindingDO();
            binding.setWorkspaceId(WS);
            binding.setWorkitemId(WORKITEM);
            binding.setDispatchId(DISPATCH);
            binding.setDesignId(1000L);
            binding.setDesignVersionId(VERSION);
            binding.setVersionNo(1);
            binding.setDesignSha256(PrdDesignCodec.digest(version().getDesignJson()));
            binding.setTargetRepoId(42L);
            binding.setTargetProfileId(55L);
            binding.setTargetAppRoot("frontend");
            binding.setTargetCommitSha("c".repeat(40));
            binding.setVersion(0);
            return binding;
        }

        PrdDesignVersionDO version() {
            PrdDesignVersionDO version = new PrdDesignVersionDO();
            version.setId(VERSION);
            version.setWorkspaceId(WS);
            version.setDesignId(1000L);
            version.setVersionNo(1);
            version.setStatus(PrdVersionStatus.CONFIRMED);
            version.setDesignJson(PrdDesignCodec.serialize(design("REQ-001")));
            version.setDesignSha256(PrdDesignCodec.digest(version.getDesignJson()));
            version.setIsDeleted(0);
            version.setVersion(0);
            return version;
        }
    }

    private static final class InMemoryVersionDao implements PrdDesignVersionDao {
        final Map<Long, PrdDesignVersionDO> rows = new LinkedHashMap<>();

        @Override public void insert(PrdDesignVersionDO version) {
            version.setVersion(version.getVersion() == null ? 0 : version.getVersion());
            rows.put(version.getId(), version);
        }

        @Override public PrdDesignVersionDO findById(Long id, Long workspaceId) {
            PrdDesignVersionDO v = id == null ? null : rows.get(id);
            return v != null && v.getWorkspaceId().equals(workspaceId)
                    && !Integer.valueOf(1).equals(v.getIsDeleted()) ? v : null;
        }

        @Override public PrdDesignVersionDO findByVersionNo(Long workspaceId, Long designId, Integer versionNo) {
            return null;
        }
        @Override public List<PrdDesignVersionDO> listByDesign(Long workspaceId, Long designId) {
            return new ArrayList<>(rows.values());
        }
        @Override public PrdDesignVersionDO findLatestByDesign(Long workspaceId, Long designId) { return null; }
        @Override public PrdDesignVersionDO findByAiSession(Long workspaceId, Long aiSessionId) { return null; }
        @Override public int updateDraft(PrdDesignVersionDO version, Integer expectedVersion, Long modifierId) {
            return 1;
        }
        @Override public int attachHtml(Long id, Long workspaceId, String htmlOssRef, String htmlSha256,
                                        Long htmlSize) { return 1; }
        @Override public int submitForReview(Long id, Long workspaceId, Integer expectedVersion, Long modifierId) {
            return 1;
        }
        @Override public int confirm(Long id, Long workspaceId, String confirmedSha256, Long confirmedBy,
                                     Integer expectedVersion) { return 1; }
        @Override public int softDelete(Long id, Long workspaceId, Integer expectedVersion, Long modifierId) {
            return 1;
        }
    }

    private static final class InMemoryBindingDao implements PrdDeliveryBindingDao {
        final Map<Long, PrdDeliveryBindingDO> rows = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(9000);

        void insert(PrdDeliveryBindingDO binding) {
            binding.setId(ids.incrementAndGet());
            rows.put(binding.getId(), binding);
        }

        @Override public int insertIgnore(PrdDeliveryBindingDO binding) {
            insert(binding);
            return 1;
        }
        @Override public PrdDeliveryBindingDO findByDispatch(Long workspaceId, Long dispatchId) {
            return rows.values().stream()
                    .filter(b -> b.getWorkspaceId().equals(workspaceId) && b.getDispatchId().equals(dispatchId))
                    .findFirst().orElse(null);
        }
        @Override public List<PrdDeliveryBindingDO> listByWorkitem(Long workspaceId, Long workitemId, int limit) {
            return rows.values().stream().filter(b -> b.getWorkitemId().equals(workitemId)).toList();
        }
        @Override public List<PrdDeliveryBindingDO> listByDesignVersion(Long workspaceId, Long designVersionId) {
            return rows.values().stream().filter(b -> b.getDesignVersionId().equals(designVersionId)).toList();
        }
        @Override public int deleteByWorkitem(Long workspaceId, Long workitemId) {
            List<Long> doomed = rows.values().stream().filter(b -> b.getWorkitemId().equals(workitemId))
                    .map(PrdDeliveryBindingDO::getId).toList();
            doomed.forEach(rows::remove);
            return doomed.size();
        }
    }
}
