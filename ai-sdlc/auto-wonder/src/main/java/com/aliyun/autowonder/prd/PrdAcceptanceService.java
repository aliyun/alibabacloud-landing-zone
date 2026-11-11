package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.prd.dto.PrdDeliveryAcceptanceVO;
import com.aliyun.autowonder.prd.dto.PrdDeliveryBindingVO;
import com.aliyun.autowonder.prd.dto.RequirementAcceptanceReport;
import com.aliyun.autowonder.prd.dto.RequirementAcceptanceVO;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-requirement acceptance results for a delivery.
 *
 * <p>Rows are seeded from the frozen design version at packaging time, one per requirement key, so
 * "every requirement has a result" holds before the executor reports anything. Seeding is also
 * repeated idempotently on read: a requirement can therefore never disappear from the acceptance
 * view, which is what keeps an unfinished item from being read as a finished one.
 *
 * <p>A seeded row is {@code NOT_VERIFIED}. Only an explicit report moves it off that value, and a
 * report claiming {@code PASSED} must carry evidence. Together these make "not verified is never
 * displayed as passed" a property of the data rather than of the rendering.
 */
@Service
public class PrdAcceptanceService {

    private static final Logger log = LoggerFactory.getLogger(PrdAcceptanceService.class);

    /** Stable marker stored as the reason for a requirement the confirmed design excluded. */
    static final String EXCLUDED_BY_DESIGN = "EXCLUDED_BY_CONFIRMED_DESIGN";

    private static final int EVIDENCE_TTL_SECONDS = 600;
    private static final int MAX_NOTE_CHARS = 2000;
    private static final int MAX_REASON_CHARS = 1000;
    private static final int MAX_EVIDENCE = 20;
    private static final int MAX_REF_CHARS = 1000;
    private static final int MAX_TITLE_CHARS = 300;

    private final PrdRequirementAcceptanceDao acceptanceDao;
    private final PrdDeliveryBindingDao bindingDao;
    private final PrdDesignVersionDao versionDao;
    private final ArtifactDao artifactDao;
    private final ObjectStorage storage;

    public PrdAcceptanceService(PrdRequirementAcceptanceDao acceptanceDao,
                                PrdDeliveryBindingDao bindingDao,
                                PrdDesignVersionDao versionDao,
                                ArtifactDao artifactDao,
                                ObjectStorage storage) {
        this.acceptanceDao = acceptanceDao;
        this.bindingDao = bindingDao;
        this.versionDao = versionDao;
        this.artifactDao = artifactDao;
        this.storage = storage;
    }

    /**
     * Seed one row per requirement of the frozen version. Called while packaging, so the acceptance
     * view exists from the moment the delivery is committed to implementing that version.
     *
     * <p>Excluded requirements are seeded as NOT_APPLICABLE with {@link #EXCLUDED_BY_DESIGN}: the
     * exclusion is a decision the user made when confirming the design, not something the executor
     * may assert, and it still gets a result row so nothing is silently dropped.
     */
    @Transactional
    public int seed(long workspaceId, PrdDeliveryBindingDO binding, PrdDesignData data) {
        int seeded = 0;
        for (PrdDesignData.Requirement requirement : data.getRequirements()) {
            if (requirement == null || requirement.getKey() == null || requirement.getKey().isBlank()) {
                continue;
            }
            PrdRequirementAcceptanceDO row = new PrdRequirementAcceptanceDO();
            row.setWorkspaceId(workspaceId);
            row.setWorkitemId(binding.getWorkitemId());
            row.setDispatchId(binding.getDispatchId());
            row.setDesignVersionId(binding.getDesignVersionId());
            row.setRequirementKey(requirement.getKey());
            if (requirement.isExcluded()) {
                row.setImplementation(ImplementationResult.NOT_IMPLEMENTED);
                row.setVerdict(AcceptanceVerdict.NOT_APPLICABLE);
                // insertIgnore cannot write these columns, so an excluded row is seeded through the
                // report path instead: same idempotence, and the reason is recorded.
                seeded += upsertExcluded(row, requirement.getTitle());
            } else {
                row.setImplementation(ImplementationResult.NOT_IMPLEMENTED);
                row.setVerdict(AcceptanceVerdict.NOT_VERIFIED);
                seeded += acceptanceDao.insertIgnore(row);
            }
        }
        if (seeded > 0) {
            log.info("prd acceptance seeded dispatchId={} versionId={} rows={}",
                    binding.getDispatchId(), binding.getDesignVersionId(), seeded);
        }
        return seeded;
    }

    /** Seed an excluded requirement as NOT_APPLICABLE, without overwriting a later report. */
    private int upsertExcluded(PrdRequirementAcceptanceDO row, String title) {
        PrdRequirementAcceptanceDO existing = acceptanceDao.findByDispatchAndKey(
                row.getWorkspaceId(), row.getDispatchId(), row.getRequirementKey());
        if (existing != null) {
            return 0;
        }
        row.setNotApplicableReason(EXCLUDED_BY_DESIGN);
        JSONObject evidence = new JSONObject();
        evidence.put("excludedByDesign", true);
        evidence.put("requirementTitle", title);
        row.setEvidenceJson(JSON.toJSONString(evidence));
        row.setReportedAt(new Date());
        acceptanceDao.insertIgnore(row);
        // insertIgnore may have lost a race; only count a row this call actually wrote.
        PrdRequirementAcceptanceDO written = acceptanceDao.findByDispatchAndKey(
                row.getWorkspaceId(), row.getDispatchId(), row.getRequirementKey());
        return written != null && EXCLUDED_BY_DESIGN.equals(written.getNotApplicableReason()) ? 1 : 0;
    }

    /**
     * Record the executor's reports for a dispatch.
     *
     * <p>Each report must name a requirement of the pinned version; a key the user never confirmed is
     * rejected rather than stored, because storing it would let a delivery claim coverage of
     * requirements that were not part of the agreed scope.
     *
     * @return the number of rows written
     * @throws BizException when the dispatch has no frozen design, or a report is invalid
     */
    @Transactional
    public int report(long workspaceId, long dispatchId, List<RequirementAcceptanceReport> reports,
                      Long reporterId) {
        if (reports == null || reports.isEmpty()) {
            return 0;
        }
        PrdRequirementAcceptanceDO any = acceptanceDao.listByDispatch(workspaceId, dispatchId)
                .stream().findFirst().orElse(null);
        if (any == null) {
            throw new BizException(ErrorCode.PRD_ACCEPTANCE_NOT_FOUND);
        }
        Set<String> frozenKeys = frozenKeys(workspaceId, any.getDesignVersionId());
        int written = 0;
        for (RequirementAcceptanceReport report : reports) {
            written += applyReport(workspaceId, dispatchId, any, report, frozenKeys, reporterId);
        }
        return written;
    }

    private int applyReport(long workspaceId, long dispatchId, PrdRequirementAcceptanceDO anchor,
                            RequirementAcceptanceReport report, Set<String> frozenKeys, Long reporterId) {
        if (report == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String key = report.getRequirementKey() == null ? null : report.getRequirementKey().trim();
        if (key == null || key.isBlank()) {
            throw new BizException(ErrorCode.PRD_ACCEPTANCE_UNKNOWN_REQUIREMENT);
        }
        if (!frozenKeys.contains(key)) {
            log.warn("prd acceptance report refused dispatchId={} unknownKey={} frozen={}",
                    dispatchId, key, frozenKeys.size());
            throw BizException.keyed(ErrorCode.PRD_ACCEPTANCE_UNKNOWN_REQUIREMENT,
                    "prd.acceptance.unknown_requirement", new Object[]{key},
                    "dispatchId=" + dispatchId + " key=" + key);
        }
        String verdict = report.getVerdict() == null ? null : report.getVerdict().trim();
        if (!AcceptanceVerdict.isValid(verdict)) {
            throw new BizException(ErrorCode.PRD_ACCEPTANCE_INVALID_VERDICT);
        }
        String implementation = report.getImplementation() == null ? null : report.getImplementation().trim();
        if (!ImplementationResult.isValid(implementation)) {
            throw new BizException(ErrorCode.PRD_ACCEPTANCE_INVALID_IMPLEMENTATION);
        }
        PrdRequirementAcceptanceDO existing = acceptanceDao.findByDispatchAndKey(workspaceId, dispatchId, key);
        if (existing != null && EXCLUDED_BY_DESIGN.equals(existing.getNotApplicableReason())) {
            // Out of scope for this round by the user's own confirmation; the executor may not reopen it.
            throw BizException.keyed(ErrorCode.PRD_ACCEPTANCE_UNKNOWN_REQUIREMENT,
                    "prd.acceptance.out_of_scope", new Object[]{key},
                    "dispatchId=" + dispatchId + " key=" + key + " excludedByDesign=true");
        }
        List<JSONObject> evidence = normalizeEvidence(workspaceId, dispatchId, report.getEvidence(), verdict);
        String reason = trim(report.getNotApplicableReason(), MAX_REASON_CHARS);
        if (AcceptanceVerdict.requiresReason(verdict) && (reason == null || reason.isBlank())) {
            throw BizException.keyed(ErrorCode.PRD_ACCEPTANCE_REASON_REQUIRED,
                    "prd.acceptance.reason_required", new Object[]{key},
                    "dispatchId=" + dispatchId + " key=" + key);
        }
        if (!AcceptanceVerdict.requiresReason(verdict)) {
            reason = null;
        }

        if (existing == null) {
            // Seeding should have created it; re-seed rather than lose a legitimate report. The anchor
            // row supplies workitemId and designVersionId, both NOT NULL, which this call cannot derive
            // from the report alone.
            PrdRequirementAcceptanceDO row = new PrdRequirementAcceptanceDO();
            row.setWorkspaceId(workspaceId);
            row.setWorkitemId(anchor.getWorkitemId());
            row.setDispatchId(dispatchId);
            row.setDesignVersionId(anchor.getDesignVersionId());
            row.setRequirementKey(key);
            row.setImplementation(ImplementationResult.NOT_IMPLEMENTED);
            row.setVerdict(AcceptanceVerdict.NOT_VERIFIED);
            row.setCreatorId(reporterId);
            acceptanceDao.insertIgnore(row);
            existing = acceptanceDao.findByDispatchAndKey(workspaceId, dispatchId, key);
            if (existing == null) {
                throw new BizException(ErrorCode.PRD_ACCEPTANCE_NOT_FOUND);
            }
        }

        existing.setImplementation(implementation);
        existing.setVerdict(verdict);
        existing.setNote(trim(report.getNote(), MAX_NOTE_CHARS));
        existing.setNotApplicableReason(reason);
        existing.setEvidenceJson(evidence.isEmpty() ? null : JSON.toJSONString(new JSONArray(evidence)));
        existing.setReportedAt(new Date());
        existing.setModifierId(reporterId);
        if (acceptanceDao.updateReport(existing) == 0) {
            log.warn("prd acceptance report lost a race dispatchId={} key={}", dispatchId, key);
        }
        return 1;
    }

    /**
     * Validate and normalize evidence. A PASSED verdict without evidence is refused: an unverified
     * claim of success is exactly what the acceptance view must not be able to show.
     */
    private List<JSONObject> normalizeEvidence(long workspaceId, long dispatchId,
                                               List<RequirementAcceptanceReport.EvidenceRef> refs,
                                               String verdict) {
        List<JSONObject> out = new ArrayList<>();
        if (refs != null) {
            for (RequirementAcceptanceReport.EvidenceRef ref : refs) {
                if (ref == null) {
                    continue;
                }
                if (out.size() >= MAX_EVIDENCE) {
                    break;
                }
                String kind = ref.getKind() == null ? null : ref.getKind().trim();
                String value = trim(ref.getRef(), MAX_REF_CHARS);
                if (!AcceptanceEvidence.isValidKind(kind) || value == null || value.isBlank()) {
                    throw BizException.keyed(ErrorCode.PRD_ACCEPTANCE_INVALID_EVIDENCE,
                            "prd.acceptance.invalid_evidence", new Object[]{String.valueOf(kind)},
                            "dispatchId=" + dispatchId);
                }
                if (AcceptanceEvidence.ARTIFACT.equals(kind)) {
                    requireDispatchArtifact(workspaceId, dispatchId, value);
                }
                JSONObject item = new JSONObject();
                item.put("kind", kind);
                item.put("ref", value);
                String title = trim(ref.getTitle(), MAX_TITLE_CHARS);
                if (title != null && !title.isBlank()) {
                    item.put("title", title);
                }
                out.add(item);
            }
        }
        if (AcceptanceVerdict.PASSED.equals(verdict) && out.isEmpty()) {
            throw BizException.keyed(ErrorCode.PRD_ACCEPTANCE_EVIDENCE_REQUIRED,
                    "prd.acceptance.evidence_required", new Object[]{String.valueOf(dispatchId)},
                    "dispatchId=" + dispatchId + " verdict=PASSED");
        }
        return out;
    }

    /** Evidence pointing at an artifact must be an artifact of this delivery, in this workspace. */
    private void requireDispatchArtifact(long workspaceId, long dispatchId, String ref) {
        Long artifactId;
        try {
            artifactId = Long.valueOf(ref);
        } catch (NumberFormatException e) {
            throw BizException.keyed(ErrorCode.PRD_ACCEPTANCE_INVALID_EVIDENCE,
                    "prd.acceptance.invalid_evidence", new Object[]{AcceptanceEvidence.ARTIFACT},
                    "dispatchId=" + dispatchId + " ref=" + ref);
        }
        ArtifactDO artifact = artifactDao.findWorkitemByTenantAndId(workspaceId, artifactId);
        if (artifact == null || artifact.getDispatchId() == null
                || artifact.getDispatchId() != dispatchId) {
            throw BizException.keyed(ErrorCode.PRD_ACCEPTANCE_INVALID_EVIDENCE,
                    "prd.acceptance.evidence_not_in_delivery", new Object[]{ref},
                    "dispatchId=" + dispatchId + " artifactId=" + ref);
        }
    }

    /**
     * Acceptance rows for a delivery, joined with the frozen design so each row carries its
     * requirement title and exclusion flag. Missing rows are seeded first.
     */
    @Transactional
    public List<RequirementAcceptanceVO> listForDispatch(long workspaceId, long dispatchId) {
        List<PrdRequirementAcceptanceDO> rows = acceptanceDao.listByDispatch(workspaceId, dispatchId);
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Long designVersionId = rows.get(0).getDesignVersionId();
        PrdDesignVersionDO version = versionDao.findById(designVersionId, workspaceId);
        PrdDesignData data = version == null ? new PrdDesignData() : PrdDesignCodec.parse(version.getDesignJson());
        if (seedMissing(workspaceId, rows, data)) {
            // Re-read so the caller sees persisted rows with their ids, not locally built stubs.
            rows = acceptanceDao.listByDispatch(workspaceId, dispatchId);
        }

        Map<String, PrdDesignData.Requirement> byKey = new LinkedHashMap<>();
        for (PrdDesignData.Requirement requirement : data.getRequirements()) {
            if (requirement != null && requirement.getKey() != null) {
                byKey.putIfAbsent(requirement.getKey(), requirement);
            }
        }
        List<RequirementAcceptanceVO> out = new ArrayList<>();
        for (PrdRequirementAcceptanceDO row : rows) {
            out.add(toVo(workspaceId, row, byKey.get(row.getRequirementKey())));
        }
        return out;
    }

    /**
     * Re-seed any frozen requirement that has no row, so a requirement cannot vanish from the
     * acceptance view.
     *
     * @return true when at least one row was seeded and the caller should re-read
     */
    private boolean seedMissing(long workspaceId, List<PrdRequirementAcceptanceDO> rows, PrdDesignData data) {
        Set<String> present = new LinkedHashSet<>();
        for (PrdRequirementAcceptanceDO row : rows) {
            present.add(row.getRequirementKey());
        }
        boolean seeded = false;
        for (PrdDesignData.Requirement requirement : data.getRequirements()) {
            if (requirement == null || requirement.getKey() == null || present.contains(requirement.getKey())) {
                continue;
            }
            PrdRequirementAcceptanceDO row = new PrdRequirementAcceptanceDO();
            row.setWorkspaceId(workspaceId);
            row.setWorkitemId(rows.get(0).getWorkitemId());
            row.setDispatchId(rows.get(0).getDispatchId());
            row.setDesignVersionId(rows.get(0).getDesignVersionId());
            row.setRequirementKey(requirement.getKey());
            row.setImplementation(ImplementationResult.NOT_IMPLEMENTED);
            if (requirement.isExcluded()) {
                seeded |= upsertExcluded(row, requirement.getTitle()) > 0;
            } else {
                row.setVerdict(AcceptanceVerdict.NOT_VERIFIED);
                seeded |= acceptanceDao.insertIgnore(row) > 0;
            }
        }
        return seeded;
    }

    private RequirementAcceptanceVO toVo(long workspaceId, PrdRequirementAcceptanceDO row,
                                         PrdDesignData.Requirement requirement) {
        RequirementAcceptanceVO vo = new RequirementAcceptanceVO();
        vo.setId(row.getId());
        vo.setDispatchId(row.getDispatchId());
        vo.setDesignVersionId(row.getDesignVersionId());
        vo.setRequirementKey(row.getRequirementKey());
        vo.setImplementation(row.getImplementation());
        vo.setVerdict(row.getVerdict());
        vo.setNote(row.getNote());
        vo.setNotApplicableReason(row.getNotApplicableReason());
        vo.setVerified(AcceptanceVerdict.isVerified(row.getVerdict()));
        vo.setReportedAt(row.getReportedAt() == null ? null : row.getReportedAt().getTime());
        boolean excluded = requirement != null && requirement.isExcluded();
        vo.setExcluded(excluded);
        if (requirement != null) {
            vo.setRequirementTitle(requirement.getTitle());
            vo.setPriority(requirement.getPriority());
        }
        vo.setEvidence(resolveEvidence(workspaceId, row.getEvidenceJson()));
        return vo;
    }

    private List<RequirementAcceptanceVO.Evidence> resolveEvidence(long workspaceId, String evidenceJson) {
        List<RequirementAcceptanceVO.Evidence> out = new ArrayList<>();
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return out;
        }
        JSONArray array;
        try {
            array = JSON.parseArray(evidenceJson);
        } catch (RuntimeException e) {
            // Persisted evidence is executor-authored; malformed content must not break the view.
            log.warn("prd acceptance evidence unparseable, shown as raw: {}", e.getMessage());
            return out;
        }
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                continue;
            }
            RequirementAcceptanceVO.Evidence evidence = new RequirementAcceptanceVO.Evidence();
            evidence.setKind(item.getString("kind"));
            evidence.setRef(item.getString("ref"));
            evidence.setTitle(item.getString("title"));
            if (AcceptanceEvidence.ARTIFACT.equals(evidence.getKind())) {
                evidence.setUrl(artifactUrl(workspaceId, evidence.getRef()));
            }
            out.add(evidence);
        }
        return out;
    }

    /** A presigned URL for artifact evidence; null when the artifact is gone, so the row still shows. */
    private String artifactUrl(long workspaceId, String ref) {
        try {
            ArtifactDO artifact = artifactDao.findWorkitemByTenantAndId(workspaceId, Long.valueOf(ref));
            return artifact == null ? null : storage.presignGet(artifact.getOssRef(), EVIDENCE_TTL_SECONDS);
        } catch (RuntimeException e) {
            log.warn("prd acceptance evidence url unavailable ref={} reason={}", ref, e.getMessage());
            return null;
        }
    }

    /** Counts over a delivery's acceptance rows, for the delivery header and the workitem overview. */
    public AcceptanceSummary summarize(List<RequirementAcceptanceVO> rows) {
        int passed = 0;
        int failed = 0;
        int notVerified = 0;
        int notApplicable = 0;
        for (RequirementAcceptanceVO row : rows) {
            switch (row.getVerdict() == null ? "" : row.getVerdict()) {
                case AcceptanceVerdict.PASSED -> passed++;
                case AcceptanceVerdict.FAILED -> failed++;
                case AcceptanceVerdict.NOT_APPLICABLE -> notApplicable++;
                default -> notVerified++;
            }
        }
        return new AcceptanceSummary(rows.size(), passed, failed, notVerified, notApplicable);
    }

    /**
     * Full acceptance view for one delivery, joining the frozen binding, the requirement rows and the
     * summary counts. The dispatch must belong to the given workitem and be a PRD-driven delivery.
     */
    public PrdDeliveryAcceptanceVO deliveryAcceptance(long workspaceId, long workitemId, long dispatchId) {
        PrdDeliveryBindingDO binding = bindingDao.findByDispatch(workspaceId, dispatchId);
        if (binding == null || binding.getWorkitemId() == null
                || binding.getWorkitemId() != workitemId) {
            throw new BizException(ErrorCode.PRD_ACCEPTANCE_NOT_FOUND);
        }
        List<RequirementAcceptanceVO> rows = listForDispatch(workspaceId, dispatchId);
        if (rows.isEmpty()) {
            // A binding exists but seeding did not (e.g. packaging aborted between pin and seed).
            // Re-seed from the frozen version so the view still covers the confirmed scope.
            PrdDesignVersionDO version = versionDao.findById(binding.getDesignVersionId(), workspaceId);
            if (version != null) {
                seed(workspaceId, binding, PrdDesignCodec.parse(version.getDesignJson()));
                rows = listForDispatch(workspaceId, dispatchId);
            }
        }
        AcceptanceSummary summary = summarize(rows);
        PrdDeliveryAcceptanceVO vo = new PrdDeliveryAcceptanceVO();
        vo.setDispatchId(dispatchId);
        vo.setDesignVersionId(binding.getDesignVersionId());
        vo.setVersionNo(binding.getVersionNo());
        vo.setDesignSha256(binding.getDesignSha256());
        vo.setTargetRepoId(binding.getTargetRepoId());
        vo.setTargetAppRoot(binding.getTargetAppRoot());
        vo.setTargetCommitSha(binding.getTargetCommitSha());
        vo.setRequirements(rows);
        vo.setTotal(summary.total());
        vo.setPassed(summary.passed());
        vo.setFailed(summary.failed());
        vo.setNotVerified(summary.notVerified());
        vo.setNotApplicable(summary.notApplicable());
        vo.setComplete(summary.isComplete());
        return vo;
    }

    /** Deliveries of one workitem that were driven by a PRD design, newest first. */
    public List<PrdDeliveryBindingVO> listDeliveryBindings(long workspaceId, long workitemId, int limit) {
        List<PrdDeliveryBindingVO> out = new ArrayList<>();
        for (PrdDeliveryBindingDO binding : bindingDao.listByWorkitem(workspaceId, workitemId, limit)) {
            PrdDeliveryBindingVO vo = new PrdDeliveryBindingVO();
            vo.setDispatchId(binding.getDispatchId());
            vo.setWorkitemId(binding.getWorkitemId());
            vo.setDesignId(binding.getDesignId());
            vo.setDesignVersionId(binding.getDesignVersionId());
            vo.setVersionNo(binding.getVersionNo());
            vo.setDesignSha256(binding.getDesignSha256());
            vo.setTargetRepoId(binding.getTargetRepoId());
            vo.setTargetAppRoot(binding.getTargetAppRoot());
            vo.setTargetCommitSha(binding.getTargetCommitSha());
            vo.setGmtCreate(binding.getGmtCreate());
            out.add(vo);
        }
        return out;
    }

    /** Acceptance rows across every delivery of a workitem, for its delivery history. */
    public List<PrdRequirementAcceptanceDO> listByWorkitem(long workspaceId, long workitemId) {
        return acceptanceDao.listByWorkitem(workspaceId, workitemId);
    }

    /** Lifecycle cleanup: drop acceptance rows when a workitem is deleted. */
    public void deleteByWorkitem(long workspaceId, long workitemId) {
        acceptanceDao.deleteByWorkitem(workspaceId, workitemId);
    }

    private Set<String> frozenKeys(long workspaceId, Long designVersionId) {
        PrdDesignVersionDO version = versionDao.findById(designVersionId, workspaceId);
        if (version == null) {
            throw new BizException(ErrorCode.PRD_VERSION_NOT_FOUND);
        }
        return PrdDesignCodec.parse(version.getDesignJson()).requirementKeys();
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    /** Acceptance counts. {@code complete} is false while anything is still unverified. */
    public record AcceptanceSummary(int total, int passed, int failed, int notVerified, int notApplicable) {
        public boolean isComplete() {
            return notVerified == 0;
        }
    }
}
