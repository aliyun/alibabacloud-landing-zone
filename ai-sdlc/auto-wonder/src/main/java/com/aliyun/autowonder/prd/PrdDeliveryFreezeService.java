package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.agent.AgentRepoPermDO;
import com.aliyun.autowonder.agent.AgentRepoPermDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.frontendproject.FrontendProjectProfileDO;
import com.aliyun.autowonder.frontendproject.FrontendProjectProfileDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.taskpackage.PrdDesignPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Freezes and verifies the PRD inputs a dispatch is built from.
 *
 * <p>The binding is pinned once, on first packaging, and re-read on every later packaging of the
 * same dispatch. That single choice is what satisfies both "retry and resume keep using the same
 * frozen inputs" and "a new version does not automatically replace the inputs of an in-flight
 * delivery": a retry never re-resolves "which version is confirmed now".
 *
 * <p>Everything here fails closed. When the mode is PRD_DRIVEN and any check fails, packaging stops
 * rather than degrading to the latest draft — an executor implementing a design nobody confirmed is
 * worse than a delivery that does not start.
 */
@Service
public class PrdDeliveryFreezeService {

    private static final Logger log = LoggerFactory.getLogger(PrdDeliveryFreezeService.class);

    private final PrdDesignDao designDao;
    private final PrdDesignVersionDao versionDao;
    private final PrdDeliveryBindingDao bindingDao;
    private final FrontendProjectProfileDao profileDao;
    private final AgentRepoPermDao agentRepoPermDao;
    private final ObjectStorage storage;
    private final PrdAcceptanceService acceptanceService;

    public PrdDeliveryFreezeService(PrdDesignDao designDao,
                                    PrdDesignVersionDao versionDao,
                                    PrdDeliveryBindingDao bindingDao,
                                    FrontendProjectProfileDao profileDao,
                                    AgentRepoPermDao agentRepoPermDao,
                                    ObjectStorage storage,
                                    PrdAcceptanceService acceptanceService) {
        this.designDao = designDao;
        this.versionDao = versionDao;
        this.bindingDao = bindingDao;
        this.profileDao = profileDao;
        this.agentRepoPermDao = agentRepoPermDao;
        this.storage = storage;
        this.acceptanceService = acceptanceService;
    }

    /**
     * Resolve the frozen design inputs for a dispatch, pinning the binding on first call.
     *
     * @return the package payload, or null when the workitem has no design (legacy delivery path)
     * @throws BizException when the mode is PRD_DRIVEN and any pre-delivery check fails
     */
    @Transactional
    public PrdDesignPackage resolve(long workspaceId, long dispatchId, long workitemId,
                                    Long agentVersionId, Long creatorId) {
        return resolve(workspaceId, dispatchId, workitemId, agentVersionId, creatorId, null);
    }

    @Transactional
    public PrdDesignPackage resolve(long workspaceId, long dispatchId, long workitemId,
                                   Long agentVersionId, Long creatorId, Long sourceDispatchId) {
        PrdDesignDO design = designDao.findByWorkitem(workspaceId, workitemId);
        PrdDeliveryBindingDO binding = bindingDao.findByDispatch(workspaceId, dispatchId);
        if (binding == null && sourceDispatchId != null) {
            PrdDeliveryBindingDO source = bindingDao.findByDispatch(workspaceId, sourceDispatchId);
            if (source != null) {
                if (!java.util.Objects.equals(source.getWorkitemId(), workitemId)) {
                    throw new BizException(ErrorCode.PRD_VERSION_NOT_FOUND);
                }
                PrdDeliveryBindingDO inherited = new PrdDeliveryBindingDO();
                inherited.setWorkspaceId(workspaceId);
                inherited.setWorkitemId(workitemId);
                inherited.setDispatchId(dispatchId);
                inherited.setDesignId(source.getDesignId());
                inherited.setDesignVersionId(source.getDesignVersionId());
                inherited.setVersionNo(source.getVersionNo());
                inherited.setDesignSha256(source.getDesignSha256());
                inherited.setHtmlSha256(source.getHtmlSha256());
                inherited.setTargetRepoId(source.getTargetRepoId());
                inherited.setTargetProfileId(source.getTargetProfileId());
                inherited.setTargetAppRoot(source.getTargetAppRoot());
                inherited.setTargetCommitSha(source.getTargetCommitSha());
                inherited.setVersion(0);
                inherited.setCreatorId(creatorId);
                bindingDao.insertIgnore(inherited);
                binding = bindingDao.findByDispatch(workspaceId, dispatchId);
            }
        }
        if (binding == null && (design == null || !DeliveryMode.isPrdDriven(design.getDeliveryMode()))) {
            // No design, or a workitem that never opted in: its original delivery path is untouched.
            return null;
        }

        if (binding == null) {
            binding = pin(workspaceId, dispatchId, workitemId, design, agentVersionId, creatorId);
        }
        verifyBinding(workspaceId, binding, design, agentVersionId);
        return build(workspaceId, binding);
    }

    /** First packaging: validate the confirmed version, then pin it to this dispatch. */
    private PrdDeliveryBindingDO pin(long workspaceId, long dispatchId, long workitemId,
                                     PrdDesignDO design, Long agentVersionId, Long creatorId) {
        Long versionId = design.getConfirmedVersionId();
        if (versionId == null) {
            throw new BizException(ErrorCode.PRD_NOT_CONFIRMED);
        }
        PrdDesignVersionDO version = versionDao.findById(versionId, workspaceId);
        if (version == null || !version.getDesignId().equals(design.getId())) {
            throw new BizException(ErrorCode.PRD_VERSION_NOT_FOUND);
        }
        if (!PrdVersionStatus.CONFIRMED.equals(version.getStatus())) {
            throw new BizException(ErrorCode.PRD_NOT_CONFIRMED);
        }
        // The executor must be able to operate on the target repo before anything is frozen or
        // dispatched — checked on first packaging too, not only on retry, per the delivery contract.
        PrdProjectSnapshot target = PrdDesignCodec.parse(version.getDesignJson()).getProjectSnapshot();
        if (target == null || target.getRepoId() == null || target.getAppRoot() == null
                || (!target.isManual() && (target.getProfileId() == null || target.getCommitSha() == null))) {
            throw new BizException(ErrorCode.PRD_TARGET_PROJECT_STALE);
        }
        if (!PrdDesignCodec.digest(version.getDesignJson()).equals(version.getConfirmedSha256())) {
            throw new BizException(ErrorCode.PRD_DIGEST_MISMATCH);
        }
        requireExecutorRepoAccess(workspaceId, agentVersionId, target.getRepoId());

        PrdDeliveryBindingDO binding = new PrdDeliveryBindingDO();
        binding.setWorkspaceId(workspaceId);
        binding.setWorkitemId(workitemId);
        binding.setDispatchId(dispatchId);
        binding.setDesignId(design.getId());
        binding.setDesignVersionId(version.getId());
        binding.setVersionNo(version.getVersionNo());
        binding.setDesignSha256(version.getDesignSha256());
        binding.setHtmlSha256(version.getHtmlSha256());
        binding.setTargetRepoId(target.getRepoId());
        binding.setTargetProfileId(target.getProfileId());
        binding.setTargetAppRoot(target.getAppRoot());
        binding.setTargetCommitSha(target.getCommitSha());
        binding.setVersion(0);
        binding.setCreatorId(creatorId);
        bindingDao.insertIgnore(binding);

        // insertIgnore is a no-op on a concurrent first packaging; re-read the winner so this
        // dispatch packages exactly the version that was pinned first, not a locally-built copy.
        PrdDeliveryBindingDO pinned = bindingDao.findByDispatch(workspaceId, dispatchId);
        log.info("prd delivery binding pinned dispatchId={} workitemId={} versionId={} versionNo={}",
                dispatchId, workitemId, pinned.getDesignVersionId(), pinned.getVersionNo());
        return pinned;
    }

    /**
     * Pre-delivery checks on a re-packaging (retry, resume, continue). The pinned version must still
     * be readable, its digest unchanged, and the target project still valid and reachable by the
     * executor. A newer confirmation elsewhere must not alter what this dispatch implements.
     */
    private void verifyBinding(long workspaceId, PrdDeliveryBindingDO binding, PrdDesignDO design,
                               Long agentVersionId) {
        PrdDesignVersionDO version = versionDao.findById(binding.getDesignVersionId(), workspaceId);
        if (version == null) {
            throw new BizException(ErrorCode.PRD_VERSION_NOT_FOUND);
        }
        // Recompute from the content rather than trusting the stored digest column: the point of this
        // check is to catch content that changed underneath a frozen delivery, which a stale or
        // rewritten column would otherwise hide.
        String actualDigest = PrdDesignCodec.digest(version.getDesignJson());
        if (!PrdVersionStatus.CONFIRMED.equals(version.getStatus())
                || !java.util.Objects.equals(version.getConfirmedSha256(), binding.getDesignSha256())
                || !actualDigest.equals(binding.getDesignSha256())) {
            log.error("prd binding digest drift dispatchId={} versionId={} pinned={} actual={}",
                    binding.getDispatchId(), version.getId(), binding.getDesignSha256(), actualDigest);
            throw new BizException(ErrorCode.PRD_DIGEST_MISMATCH);
        }
        PrdProjectSnapshot target = PrdDesignCodec.parse(version.getDesignJson()).getProjectSnapshot();
        if (target == null || !java.util.Objects.equals(target.getRepoId(), binding.getTargetRepoId())
                || !java.util.Objects.equals(target.getAppRoot(), binding.getTargetAppRoot())) {
            throw new BizException(ErrorCode.PRD_TARGET_PROJECT_STALE);
        }
        if (!target.isManual()) {
            if (binding.getTargetProfileId() == null) {
                throw new BizException(ErrorCode.PRD_TARGET_PROJECT_REQUIRED);
            }
            FrontendProjectProfileDO profile = profileDao.findById(binding.getTargetProfileId(), workspaceId);
            if (profile == null) {
                throw new BizException(ErrorCode.PRD_TARGET_PROJECT_STALE);
            }
        }
        requireExecutorRepoAccess(workspaceId, agentVersionId, binding.getTargetRepoId());
        // Design confirmation may have moved on; that must not retroactively change this dispatch.
        if (design != null && design.getConfirmedVersionId() != null
                && !design.getConfirmedVersionId().equals(binding.getDesignVersionId())) {
            log.info("prd binding keeps older confirmed version dispatchId={} pinned={} currentConfirmed={}",
                    binding.getDispatchId(), binding.getDesignVersionId(), design.getConfirmedVersionId());
        }
    }

    /** The executor must be able to operate on the target repo, or the design cannot be delivered. */
    private void requireExecutorRepoAccess(long workspaceId, Long agentVersionId, Long targetRepoId) {
        if (agentVersionId == null || targetRepoId == null) {
            throw new BizException(ErrorCode.PRD_TARGET_PROJECT_REQUIRED);
        }
        for (AgentRepoPermDO perm : agentRepoPermDao.listByVersion(agentVersionId)) {
            if (perm != null && perm.getTenantId() != null && perm.getTenantId() == workspaceId
                    && targetRepoId.equals(perm.getRepoId())) {
                return;
            }
        }
        log.warn("prd delivery refused: executor lacks repo permission agentVersionId={} repoId={}",
                agentVersionId, targetRepoId);
        throw new BizException(ErrorCode.PRD_TARGET_PROJECT_REQUIRED);
    }

    /**
     * Build the four frozen files required by the delivery contract: {@code design/prd.html},
     * {@code design/requirements.json}, {@code design/project-profile.json} and
     * {@code design/manifest.json}. HTML comes from object storage and is re-verified against the
     * pinned digest; the rest are derived from the stored design data, so all four agree.
     */
    private PrdDesignPackage build(long workspaceId, PrdDeliveryBindingDO binding) {
        PrdDesignVersionDO version = versionDao.findById(binding.getDesignVersionId(), workspaceId);
        if (version == null) {
            throw new BizException(ErrorCode.PRD_VERSION_NOT_FOUND);
        }
        byte[] html = readVerifiedHtml(version, binding);
        PrdDesignData data = PrdDesignCodec.parse(version.getDesignJson());
        byte[] requirements = PrdDesignCodec.exportRequirements(data).getBytes(StandardCharsets.UTF_8);
        byte[] projectProfile = buildProjectProfile(workspaceId, binding, data);

        // Seed acceptance rows from the same parsed model that produces requirements.json, so the
        // acceptance view covers exactly the scope the user confirmed — no more, no less.
        acceptanceService.seed(workspaceId, binding, data);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", "autoWonder.prdDeliveryManifest.v1");
        manifest.put("prdVersionId", binding.getDesignVersionId());
        manifest.put("prdVersionNo", binding.getVersionNo());
        manifest.put("designSha256", binding.getDesignSha256());
        manifest.put("rendererVersion", version.getRendererVersion());
        manifest.put("targetRepoId", binding.getTargetRepoId());
        manifest.put("targetAppRoot", binding.getTargetAppRoot());
        manifest.put("targetProfileId", binding.getTargetProfileId());
        manifest.put("probedCommitSha", binding.getTargetCommitSha());
        manifest.put("requirementKeys", new ArrayList<>(data.requirementKeys()));
        manifest.put("workitemId", binding.getWorkitemId());
        manifest.put("dispatchId", binding.getDispatchId());
        // Where and how the executor reports per-requirement results, so acceptance is not an
        // out-of-band convention: one row per key above, and PASSED needs evidence.
        manifest.put("acceptanceReportPath",
                "/api/daemon/dispatches/" + binding.getDispatchId() + "/acceptance");
        manifest.put("acceptanceVerdicts", List.of(AcceptanceVerdict.PASSED, AcceptanceVerdict.FAILED,
                AcceptanceVerdict.NOT_VERIFIED, AcceptanceVerdict.NOT_APPLICABLE));
        manifest.put("acceptanceEvidenceKinds", AcceptanceEvidence.kinds());

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(PrdDesignPackage.HTML_ENTRY, html);
        files.put(PrdDesignPackage.REQUIREMENTS_ENTRY, requirements);
        files.put(PrdDesignPackage.PROJECT_PROFILE_ENTRY, projectProfile);
        // The manifest is also a file in the package, so the executor can read the pinned identity
        // without parsing the package-level manifest.
        byte[] manifestBytes = com.aliyun.autowonder.json.JSON.toJSONString(manifest)
                .getBytes(StandardCharsets.UTF_8);
        files.put(PrdDesignPackage.MANIFEST_ENTRY, manifestBytes);

        log.info("prd design package built dispatchId={} versionNo={} requirements={} htmlBytes={}",
                binding.getDispatchId(), binding.getVersionNo(), data.requirementKeys().size(), html.length);
        return new PrdDesignPackage(files, manifest, binding.getDesignVersionId(),
                binding.getVersionNo(), binding.getDesignSha256());
    }

    /** The frozen profile: the probed snapshot plus the target-project adaptation from the design. */
    private byte[] buildProjectProfile(long workspaceId, PrdDeliveryBindingDO binding, PrdDesignData data) {
        com.aliyun.autowonder.json.JSONObject json = new com.aliyun.autowonder.json.JSONObject();
        json.put("schemaVersion", "autoWonder.prdProjectProfile.v1");
        json.put("repoId", binding.getTargetRepoId());
        json.put("appRoot", binding.getTargetAppRoot());
        json.put("probedCommitSha", binding.getTargetCommitSha());
        json.put("adaptation", data.getTargetProjectAdaptation());
        PrdProjectSnapshot profile = data.getProjectSnapshot();
        if (profile == null) {
            throw new BizException(ErrorCode.PRD_TARGET_PROJECT_STALE);
        }
        json.put("branch", profile.getBranch());
        json.put("source", profile.getSource());
        json.put("projectNotes", profile.getProjectNotes());
        json.put("detectorVersion", profile.getDetectorVersion());
        json.put("profile", profile.getProfileJson() == null ? null
                : com.aliyun.autowonder.json.JSON.parseObject(profile.getProfileJson()));
        json.put("evidence", profile.getEvidenceJson() == null ? null
                : com.aliyun.autowonder.json.JSON.parseObject(profile.getEvidenceJson()));
        return com.aliyun.autowonder.json.JSON.toJSONString(json).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] readVerifiedHtml(PrdDesignVersionDO version, PrdDeliveryBindingDO binding) {
        if (version.getHtmlOssRef() == null) {
            throw new BizException(ErrorCode.PRD_HTML_UNREADABLE);
        }
        byte[] html = storage.get(version.getHtmlOssRef());
        if (html == null) {
            throw new BizException(ErrorCode.PRD_HTML_UNREADABLE);
        }
        String digest = com.aliyun.autowonder.util.MessageDigestUtil.sha256Hex(html);
        if (binding.getHtmlSha256() != null && !binding.getHtmlSha256().equals(digest)) {
            log.error("prd html digest mismatch at packaging versionId={} pinned={} actual={}",
                    version.getId(), binding.getHtmlSha256(), digest);
            throw new BizException(ErrorCode.PRD_DIGEST_MISMATCH);
        }
        return html;
    }

    /** Bindings of one version, for the "superseded while in flight" warning. */
    public List<PrdDeliveryBindingDO> listBindingsForVersion(long workspaceId, long versionId) {
        return bindingDao.listByDesignVersion(workspaceId, versionId);
    }

    /** Lifecycle cleanup: drop bindings when a workitem is deleted. */
    public void deleteByWorkitem(long workspaceId, long workitemId) {
        bindingDao.deleteByWorkitem(workspaceId, workitemId);
    }
}
