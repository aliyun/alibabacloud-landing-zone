package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.frontendproject.FrontendProjectProfileDO;
import com.aliyun.autowonder.frontendproject.FrontendProjectProfileDao;
import com.aliyun.autowonder.prd.dto.ConfirmVersionRequest;
import com.aliyun.autowonder.prd.dto.PrdDesignVO;
import com.aliyun.autowonder.prd.dto.PrdDesignVersionVO;
import com.aliyun.autowonder.prd.dto.SaveDesignRequest;
import com.aliyun.autowonder.prd.dto.SelectTargetRequest;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.util.MessageDigestUtil;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * PRD design lifecycle: draft versions, review submission, and confirmation.
 *
 * <p>Two invariants carry most of the correctness here. First, a version's HTML and requirements
 * export are both derived from the stored {@code design_json} by {@link PrdDesignCodec} and
 * {@link PrdHtmlRenderer}, so they cannot disagree. Second, a confirmed version is immutable — the
 * SQL guards on {@code status <> 'CONFIRMED'} and edits create a new draft, so delivery can keep
 * trusting the version it was handed.
 *
 * <p>Space isolation: workspace-scoped SQL everywhere plus an explicit workitem ownership check, and
 * the target project must belong to the same workspace before it can be bound.
 */
@Service
public class PrdDesignService {

    private static final Logger log = LoggerFactory.getLogger(PrdDesignService.class);

    private static final String MODULE = "PRD_DESIGN";
    private static final int SUMMARY_MAX_LEN = 1024;
    private static final int CHANGE_SUMMARY_MAX_LEN = 2048;
    private static final int HTML_PREVIEW_TTL_SECONDS = 600;

    private final PrdDesignDao designDao;
    private final PrdDesignVersionDao versionDao;
    private final WorkitemDao workitemDao;
    private final FrontendProjectProfileDao profileDao;
    private final ObjectStorage storage;
    private final AuditLogService auditLogService;
    private final String artifactBucket;
    private com.aliyun.autowonder.repo.RepoDao repoDao;

    @org.springframework.beans.factory.annotation.Autowired
    public void setRepoDao(com.aliyun.autowonder.repo.RepoDao repoDao) {
        this.repoDao = repoDao;
    }

    public PrdDesignService(PrdDesignDao designDao,
                            PrdDesignVersionDao versionDao,
                            WorkitemDao workitemDao,
                            FrontendProjectProfileDao profileDao,
                            ObjectStorage storage,
                            AuditLogService auditLogService,
                            OssProperties ossProperties) {
        this.designDao = designDao;
        this.versionDao = versionDao;
        this.workitemDao = workitemDao;
        this.profileDao = profileDao;
        this.storage = storage;
        this.auditLogService = auditLogService;
        this.artifactBucket = ossProperties.resolveArtifactBucket();
    }

    // ------------------------------------------------------------------ read

    /** The design for a workitem, creating an empty aggregate on first access. */
    @Transactional
    public PrdDesignVO getOrCreate(long workspaceId, long workitemId, long userId) {
        requireWorkitem(workspaceId, workitemId);
        PrdDesignDO design = designDao.findByWorkitem(workspaceId, workitemId);
        if (design == null) {
            design = new PrdDesignDO();
            design.setWorkspaceId(workspaceId);
            design.setWorkitemId(workitemId);
            design.setDeliveryMode(DeliveryMode.LEGACY);
            design.setLatestVersionNo(0);
            design.setVersion(0);
            design.setCreatorId(userId);
            designDao.insert(design);
            log.info("prd design created designId={} workspaceId={} workitemId={}",
                    design.getId(), workspaceId, workitemId);
        }
        return toDesignVO(design, false);
    }

    public PrdDesignVO get(long workspaceId, long workitemId) {
        requireWorkitem(workspaceId, workitemId);
        PrdDesignDO design = designDao.findByWorkitem(workspaceId, workitemId);
        if (design == null) {
            throw new BizException(ErrorCode.PRD_DESIGN_NOT_FOUND);
        }
        return toDesignVO(design, false);
    }

    /**
     * The design for a workitem, or null when it has none — never creates a row.
     *
     * <p>This is the passive read used by the workitem summary card. Browsing a workitem must not
     * silently create a design aggregate; only opening the workbench (an explicit design action) may.
     */
    public PrdDesignVO peek(long workspaceId, long workitemId) {
        requireWorkitem(workspaceId, workitemId);
        PrdDesignDO design = designDao.findByWorkitem(workspaceId, workitemId);
        return design == null ? null : toDesignVO(design, false);
    }

    /** Version history without design payloads. */
    public List<PrdDesignVersionVO> listVersions(long workspaceId, long workitemId) {
        PrdDesignDO design = requireDesign(workspaceId, workitemId);
        List<PrdDesignVersionVO> out = new ArrayList<>();
        for (PrdDesignVersionDO version : versionDao.listByDesign(workspaceId, design.getId())) {
            out.add(toVersionVO(version, false));
        }
        return out;
    }

    public PrdDesignVersionVO getVersion(long workspaceId, long versionId) {
        PrdDesignVersionDO version = requireVersion(workspaceId, versionId);
        return toVersionVO(version, true);
    }

    // ------------------------------------------------------------------ target

    /** Bind the target frontend project. The profile must belong to this workspace. */
    @Transactional
    public PrdDesignVO selectTarget(long workspaceId, long workitemId, SelectTargetRequest request, long userId) {
        PrdDesignDO design = requireDesign(workspaceId, workitemId);
        if (request.getRepoId() != null) {
            if (request.getProfileId() != null) throw new BizException(ErrorCode.PARAM_INVALID);
            var repo = repoDao.findById(request.getRepoId());
            if (repo == null || !java.util.Objects.equals(repo.getTenantId(), workspaceId)) {
                throw new BizException(ErrorCode.REPO_NOT_FOUND);
            }
            String root = request.getAppRoot() == null || request.getAppRoot().isBlank()
                    ? "." : request.getAppRoot().trim();
            if (root.length() > 512 || root.startsWith("/") || root.contains("\\") || root.contains(":")
                    || root.chars().anyMatch(Character::isISOControl)
                    || java.util.Arrays.asList(root.split("/", -1)).contains("..")
                    || (request.getProjectNotes() != null && request.getProjectNotes().length() > 8000)) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "prd.target.invalid_config", new Object[]{},
                        "Invalid manual project directory or notes length");
            }
            root = java.nio.file.Path.of(root).normalize().toString().replace('\\', '/');
            if (root.isEmpty()) root = ".";
            PrdProjectSnapshot config = new PrdProjectSnapshot();
            config.setSource("USER_CONFIGURED");
            config.setRepoId(repo.getId());
            config.setAppRoot(root);
            config.setBranch(repo.getDefaultBranch());
            config.setProjectNotes(request.getProjectNotes());
            if (designDao.updateManualTarget(design.getId(), workspaceId, repo.getId(), root,
                    com.aliyun.autowonder.json.JSON.toJSONString(config), design.getVersion(), userId) == 0) {
                throw new BizException(ErrorCode.PRD_VERSION_CONFLICT);
            }
            return toDesignVO(designDao.findByWorkitem(workspaceId, workitemId), false);
        }
        FrontendProjectProfileDO profile = profileDao.findById(request.getProfileId(), workspaceId);
        if (profile == null) {
            throw new BizException(ErrorCode.FRONTEND_PROJECT_PROFILE_NOT_FOUND);
        }
        if (designDao.updateTarget(design.getId(), workspaceId, profile.getRepoId(), profile.getId(),
                profile.getAppRoot(), profile.getCommitSha(), design.getVersion(), userId) == 0) {
            throw new BizException(ErrorCode.PRD_VERSION_CONFLICT);
        }
        log.info("prd design target bound designId={} profileId={} repoId={} appRoot={} commit={}",
                design.getId(), profile.getId(), profile.getRepoId(), profile.getAppRoot(), profile.getCommitSha());
        return toDesignVO(designDao.findByWorkitem(workspaceId, workitemId), false);
    }

    // ------------------------------------------------------------------ author

    /**
     * Save a design draft. Editing a confirmed version is refused; the caller must create a new
     * draft from it instead, which is what {@link #createDraftFrom} does.
     */
    @Transactional
    public PrdDesignVersionVO saveDraft(long workspaceId, long workitemId, SaveDesignRequest request, long userId) {
        PrdDesignDO design = requireDesign(workspaceId, workitemId);
        PrdDesignData data = PrdDesignCodec.parse(request.getDesignJson());
        data.setSchemaVersion(PrdDesignData.SCHEMA_VERSION);
        captureTarget(design, data);
        List<String> keyProblems = PrdDesignCodec.normalizeKeys(data);
        if (!keyProblems.isEmpty()) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "prd.design.duplicate_requirement_key",
                    new Object[]{String.join("；", keyProblems)}, "duplicate requirement keys: " + keyProblems);
        }

        PrdDesignVersionDO existing = request.getVersionId() == null
                ? null : requireVersion(workspaceId, request.getVersionId());
        if (existing != null) {
            if (!existing.getDesignId().equals(design.getId())) {
                throw new BizException(ErrorCode.PRD_VERSION_NOT_FOUND);
            }
            if (existing.isConfirmed()) {
                throw new BizException(ErrorCode.PRD_VERSION_IMMUTABLE);
            }
            if (request.getExpectedVersion() == null
                    || !request.getExpectedVersion().equals(existing.getVersion())) {
                throw new BizException(ErrorCode.PRD_VERSION_CONFLICT);
            }
            return rewriteDraft(existing, data, request, userId);
        }
        return appendVersion(design, data, request, null, userId);
    }

    /**
     * Create a new draft derived from an existing version, keeping requirement keys so acceptance
     * history stays linked. Used when the user edits a confirmed version.
     */
    @Transactional
    public PrdDesignVersionVO createDraftFrom(long workspaceId, long workitemId, long sourceVersionId,
                                              String changeSummary, long userId) {
        PrdDesignDO design = requireDesign(workspaceId, workitemId);
        PrdDesignVersionDO source = requireVersion(workspaceId, sourceVersionId);
        if (!source.getDesignId().equals(design.getId())) {
            throw new BizException(ErrorCode.PRD_VERSION_NOT_FOUND);
        }
        PrdDesignData data = PrdDesignCodec.parse(source.getDesignJson());
        SaveDesignRequest request = new SaveDesignRequest();
        request.setDesignJson(source.getDesignJson());
        request.setChangeSummary(changeSummary);
        captureTarget(design, data);
        return appendVersion(design, data, request, source, userId);
    }

    private PrdDesignVersionVO rewriteDraft(PrdDesignVersionDO existing, PrdDesignData data,
                                            SaveDesignRequest request, long userId) {
        String designJson = PrdDesignCodec.serialize(data);
        String digest = PrdDesignCodec.digest(designJson);
        // Re-render from the parsed model: the HTML can only reflect the stored design data.
        String html = PrdHtmlRenderer.render(data);
        StoredObject stored = putHtml(existing, html);

        PrdDesignVersionDO updated = new PrdDesignVersionDO();
        updated.setId(existing.getId());
        updated.setWorkspaceId(existing.getWorkspaceId());
        updated.setDesignId(existing.getDesignId());
        updated.setVersionNo(existing.getVersionNo());
        updated.setStatus(PrdVersionStatus.DRAFT);
        updated.setSourceVersionId(existing.getSourceVersionId());
        updated.setDesignJson(designJson);
        updated.setDesignSha256(digest);
        updated.setHtmlOssRef(stored.getOssRef());
        updated.setHtmlSha256(MessageDigestUtil.sha256Hex(html));
        updated.setHtmlSize(stored.getSize());
        updated.setRendererVersion(PrdHtmlRenderer.RENDERER_VERSION);
        updated.setSummary(truncate(buildSummary(data), SUMMARY_MAX_LEN));
        updated.setChangeSummary(truncate(request.getChangeSummary(), CHANGE_SUMMARY_MAX_LEN));
        updated.setRequirementCount(data.getRequirements().size());
        updated.setOpenQuestionCount(data.getOpenQuestions().size());
        updated.setBlockingQuestionCount(data.unresolvedBlockingQuestions().size());
        updated.setAiSessionId(existing.getAiSessionId());
        // A draft rewrite replaces the row's payload in place; the optimistic version guards races.
        if (versionDao.updateDraft(updated, existing.getVersion(), userId) == 0) {
            throw new BizException(ErrorCode.PRD_VERSION_CONFLICT);
        }
        return toVersionVO(versionDao.findById(existing.getId(), existing.getWorkspaceId()), true);
    }

    private PrdDesignVersionVO appendVersion(PrdDesignDO design, PrdDesignData data,
                                             SaveDesignRequest request, PrdDesignVersionDO source, long userId) {
        int versionNo = nextVersionNo(design);
        String designJson = PrdDesignCodec.serialize(data);
        String digest = PrdDesignCodec.digest(designJson);
        String html = PrdHtmlRenderer.render(data);

        PrdDesignVersionDO version = new PrdDesignVersionDO();
        version.setWorkspaceId(design.getWorkspaceId());
        version.setDesignId(design.getId());
        version.setVersionNo(versionNo);
        version.setStatus(PrdVersionStatus.DRAFT);
        version.setSourceVersionId(source == null ? null : source.getId());
        version.setDesignJson(designJson);
        version.setDesignSha256(digest);
        version.setRendererVersion(PrdHtmlRenderer.RENDERER_VERSION);
        version.setSummary(truncate(buildSummary(data), SUMMARY_MAX_LEN));
        version.setChangeSummary(truncate(request.getChangeSummary(), CHANGE_SUMMARY_MAX_LEN));
        version.setRequirementCount(data.getRequirements().size());
        version.setOpenQuestionCount(data.getOpenQuestions().size());
        version.setBlockingQuestionCount(data.unresolvedBlockingQuestions().size());
        version.setAiSessionId(request.getAiSessionId());
        version.setIsDeleted(0);
        version.setVersion(0);
        version.setCreatorId(userId);
        versionDao.insert(version);

        StoredObject stored = putHtml(version, html);
        versionDao.attachHtml(version.getId(), version.getWorkspaceId(), stored.getOssRef(),
                MessageDigestUtil.sha256Hex(html), stored.getSize());

        log.info("prd version appended designId={} versionNo={} requirements={} blocking={}",
                design.getId(), versionNo, version.getRequirementCount(), version.getBlockingQuestionCount());
        return toVersionVO(versionDao.findById(version.getId(), version.getWorkspaceId()), true);
    }

    /**
     * Claim the next version number with a CAS so two concurrent authors cannot mint the same one;
     * the unique key on (workspace, design, version_no) is the last line of defence.
     */
    private int nextVersionNo(PrdDesignDO design) {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (designDao.incrementLatestVersionNo(design.getId(), design.getWorkspaceId(),
                    design.getVersion()) > 0) {
                return design.getLatestVersionNo() + 1;
            }
            PrdDesignDO fresh = designDao.findById(design.getId(), design.getWorkspaceId());
            if (fresh == null) {
                throw new BizException(ErrorCode.PRD_DESIGN_NOT_FOUND);
            }
            design.setVersion(fresh.getVersion());
            design.setLatestVersionNo(fresh.getLatestVersionNo());
        }
        throw new BizException(ErrorCode.PRD_VERSION_CONFLICT);
    }

    // ------------------------------------------------------------------ review

    @Transactional
    public PrdDesignVersionVO submitForReview(long workspaceId, long versionId, long userId) {
        PrdDesignVersionDO version = requireVersion(workspaceId, versionId);
        if (version.isConfirmed()) {
            throw new BizException(ErrorCode.PRD_VERSION_IMMUTABLE);
        }
        if (versionDao.submitForReview(versionId, workspaceId, version.getVersion(), userId) == 0) {
            throw new BizException(ErrorCode.PRD_VERSION_CONFLICT);
        }
        return toVersionVO(versionDao.findById(versionId, workspaceId), false);
    }

    /**
     * Confirm a specific version. Binds the version to its content digest, runs the confirmation
     * gate, requires a valid target project, and records who confirmed what and when.
     */
    @Transactional
    public PrdDesignVersionVO confirm(long workspaceId, long workitemId, ConfirmVersionRequest request, long userId) {
        PrdDesignDO design = requireDesign(workspaceId, workitemId);
        PrdDesignVersionDO version = requireVersion(workspaceId, request.getVersionId());
        if (!version.getDesignId().equals(design.getId())) {
            throw new BizException(ErrorCode.PRD_VERSION_NOT_FOUND);
        }
        if (version.isConfirmed()) {
            throw new BizException(ErrorCode.PRD_VERSION_IMMUTABLE);
        }

        // The caller must confirm the digest it actually reviewed; a stale view cannot confirm.
        String currentDigest = PrdDesignCodec.digest(version.getDesignJson());
        if (request.getExpectedDigest() != null && !request.getExpectedDigest().isBlank()
                && !currentDigest.equals(request.getExpectedDigest())) {
            throw new BizException(ErrorCode.PRD_DIGEST_MISMATCH);
        }

        PrdProjectSnapshot currentTarget = targetSnapshot(design);
        if (currentTarget == null) {
            throw new BizException(ErrorCode.PRD_TARGET_PROJECT_REQUIRED);
        }

        PrdDesignData data = PrdDesignCodec.parse(version.getDesignJson());
        PrdProjectSnapshot target = data.getProjectSnapshot();
        if (target == null || !java.util.Objects.equals(
                com.aliyun.autowonder.json.JSON.toJSONString(target),
                com.aliyun.autowonder.json.JSON.toJSONString(currentTarget))) {
            throw BizException.keyed(ErrorCode.PRD_TARGET_PROJECT_STALE, "prd.confirmation.target_changed",
                    new Object[]{}, "Reviewed version target snapshot differs from current target configuration");
        }
        List<String> problems = PrdDesignCodec.validateForConfirmation(data, request.isIgnoreOpenQuestions());
        if (!problems.isEmpty()) {
            throw BizException.keyed(ErrorCode.PRD_CONFIRMATION_BLOCKED, "prd.confirmation.blocked",
                    new Object[]{String.join("；", problems)},
                    "confirmation blocked: " + String.join("; ", problems));
        }

        if (versionDao.confirm(versionId(version), workspaceId, currentDigest, userId, version.getVersion()) == 0) {
            throw new BizException(ErrorCode.PRD_VERSION_CONFLICT);
        }
        if (designDao.confirmVersion(design.getId(), workspaceId, version.getId(),
                design.getVersion(), userId) == 0) {
            throw new BizException(ErrorCode.PRD_VERSION_CONFLICT);
        }
        if (DeliveryMode.isPrdDriven(request.getDeliveryMode())) {
            // Re-read after the confirm CAS bumped the row, so this write targets the live version
            // rather than relying on arithmetic that drifts if another update lands in between.
            PrdDesignDO fresh = designDao.findById(design.getId(), workspaceId);
            if (fresh == null) {
                throw new BizException(ErrorCode.PRD_DESIGN_NOT_FOUND);
            }
            if (designDao.updateDeliveryMode(design.getId(), workspaceId, DeliveryMode.PRD_DRIVEN,
                    fresh.getVersion(), userId) == 0) {
                throw new BizException(ErrorCode.PRD_VERSION_CONFLICT);
            }
            design.setVersion(fresh.getVersion() + 1);
        }

        auditConfirmation(workspaceId, userId, design, version, currentDigest);
        log.info("prd version confirmed designId={} versionId={} versionNo={} digest={} by={}",
                design.getId(), version.getId(), version.getVersionNo(), currentDigest, userId);
        return toVersionVO(versionDao.findById(version.getId(), workspaceId), false);
    }

    private long versionId(PrdDesignVersionDO version) {
        return version.getId();
    }

    private void auditConfirmation(long workspaceId, long userId, PrdDesignDO design,
                                   PrdDesignVersionDO version, String digest) {
        if (auditLogService == null) {
            return;
        }
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(workspaceId);
        record.setActorId(userId);
        record.setActorType("USER");
        record.setModule(MODULE);
        record.setAction("CONFIRM_VERSION");
        record.setTargetType("PRD_DESIGN_VERSION");
        record.setTargetId(version.getId());
        record.setTriggerType("USER");
        record.setEventType("PRD_VERSION_CONFIRMED");
        record.detail("designId", design.getId());
        record.detail("workitemId", design.getWorkitemId());
        record.detail("versionNo", version.getVersionNo());
        record.detail("designSha256", digest);
        record.detail("targetProfileId", design.getTargetProfileId());
        record.detail("targetCommitSha", design.getTargetCommitSha());
        auditLogService.record(record);
    }

    // ------------------------------------------------------------------ html

    /** A short-lived presigned URL for the isolated preview iframe. */
    public String previewHtml(long workspaceId, long versionId) {
        PrdDesignVersionDO version = requireVersion(workspaceId, versionId);
        if (version.getHtmlOssRef() == null || !storage.exists(version.getHtmlOssRef())) {
            throw new BizException(ErrorCode.PRD_HTML_UNREADABLE);
        }
        return storage.presignGet(version.getHtmlOssRef(), HTML_PREVIEW_TTL_SECONDS);
    }

    /** The stored HTML bytes, re-verified against the digest recorded when the version was written. */
    public byte[] readHtml(long workspaceId, long versionId) {
        PrdDesignVersionDO version = requireVersion(workspaceId, versionId);
        if (version.getHtmlOssRef() == null) {
            throw new BizException(ErrorCode.PRD_HTML_UNREADABLE);
        }
        byte[] html = storage.get(version.getHtmlOssRef());
        if (html == null) {
            throw new BizException(ErrorCode.PRD_HTML_UNREADABLE);
        }
        String digest = MessageDigestUtil.sha256Hex(html);
        if (version.getHtmlSha256() != null && !version.getHtmlSha256().equals(digest)) {
            log.error("prd html digest mismatch versionId={} expected={} actual={}",
                    versionId, version.getHtmlSha256(), digest);
            throw new BizException(ErrorCode.PRD_DIGEST_MISMATCH);
        }
        return html;
    }

    /**
     * Store the rendered HTML under a content-addressed key.
     *
     * <p>Addressing by digest is what makes a concurrent draft rewrite safe: the HTML is written
     * before the row's optimistic-version CAS is attempted, so two authors editing the same draft
     * would otherwise race on one key and the loser could overwrite the winner's object while its own
     * DB write was rejected — leaving a row whose recorded digest no longer matches its content.
     * Distinct content lands on distinct keys, and identical content overwrites itself harmlessly.
     */
    private StoredObject putHtml(PrdDesignVersionDO version, String html) {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String htmlSha256 = MessageDigestUtil.sha256Hex(bytes);
        String key = "t/" + version.getWorkspaceId() + "/workitem/prd/" + version.getDesignId()
                + "/html/" + htmlSha256 + ".html";
        return storage.put(artifactBucket, key, bytes);
    }

    private void captureTarget(PrdDesignDO design, PrdDesignData data) {
        data.setProjectSnapshot(targetSnapshot(design));
        if (!"en-US".equals(data.getLanguage())) {
            data.setLanguage("zh-CN");
        }
    }

    PrdProjectSnapshot targetSnapshot(PrdDesignDO design) {
        if (design.getTargetConfigJson() != null) {
            PrdProjectSnapshot config = com.aliyun.autowonder.json.JSON.parseObject(
                    design.getTargetConfigJson(), PrdProjectSnapshot.class);
            var repo = repoDao.findById(config.getRepoId());
            if (repo == null || !java.util.Objects.equals(repo.getTenantId(), design.getWorkspaceId())) {
                throw new BizException(ErrorCode.REPO_NOT_FOUND);
            }
            return config;
        }
        FrontendProjectProfileDO profile = design.getTargetProfileId() == null ? null
                : profileDao.findById(design.getTargetProfileId(), design.getWorkspaceId());
        return profile == null ? null : PrdProjectSnapshot.from(profile);
    }

    // ------------------------------------------------------------------ guards

    private void requireWorkitem(long workspaceId, long workitemId) {
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null || workitem.getTenantId() == null
                || workitem.getTenantId() != workspaceId) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
    }

    private PrdDesignDO requireDesign(long workspaceId, long workitemId) {
        requireWorkitem(workspaceId, workitemId);
        PrdDesignDO design = designDao.findByWorkitem(workspaceId, workitemId);
        if (design == null) {
            throw new BizException(ErrorCode.PRD_DESIGN_NOT_FOUND);
        }
        return design;
    }

    private PrdDesignVersionDO requireVersion(long workspaceId, long versionId) {
        PrdDesignVersionDO version = versionDao.findById(versionId, workspaceId);
        if (version == null) {
            throw new BizException(ErrorCode.PRD_VERSION_NOT_FOUND);
        }
        return version;
    }

    // ------------------------------------------------------------------ mapping

    private String buildSummary(PrdDesignData data) {
        java.util.Locale locale = "en-US".equals(data.getLanguage())
                ? java.util.Locale.US : java.util.Locale.SIMPLIFIED_CHINESE;
        String summary = com.aliyun.autowonder.common.i18n.SystemMessages.render("prd.summary",
                new Object[]{data.getTitle(), data.activeRequirements().size()}, data.getTitle(), locale);
        if (!data.unresolvedBlockingQuestions().isEmpty()) {
            summary += com.aliyun.autowonder.common.i18n.SystemMessages.render("prd.summary.blockers",
                    new Object[]{data.unresolvedBlockingQuestions().size()}, "", locale);
        }
        return summary;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private PrdDesignVO toDesignVO(PrdDesignDO design, boolean includeVersions) {
        PrdDesignVO vo = new PrdDesignVO();
        vo.setId(design.getId());
        vo.setWorkitemId(design.getWorkitemId());
        vo.setTargetRepoId(design.getTargetRepoId());
        vo.setTargetProfileId(design.getTargetProfileId());
        vo.setTargetAppRoot(design.getTargetAppRoot());
        vo.setTargetCommitSha(design.getTargetCommitSha());
        if (design.getTargetConfigJson() != null) {
            vo.setTargetProjectNotes(com.aliyun.autowonder.json.JSON.parseObject(
                    design.getTargetConfigJson(), PrdProjectSnapshot.class).getProjectNotes());
        }
        vo.setConfirmedVersionId(design.getConfirmedVersionId());
        vo.setDeliveryMode(design.getDeliveryMode());
        vo.setLatestVersionNo(design.getLatestVersionNo());
        vo.setContentLanguage(design.getContentLanguage());
        vo.setVersion(design.getVersion());
        vo.setGmtModified(design.getGmtModified());
        if (design.getConfirmedVersionId() != null) {
            PrdDesignVersionDO confirmed = versionDao.findById(design.getConfirmedVersionId(),
                    design.getWorkspaceId());
            if (confirmed != null) {
                vo.setConfirmedVersionNo(confirmed.getVersionNo());
                vo.setConfirmedSha256(confirmed.getConfirmedSha256());
            }
        }
        if (includeVersions) {
            for (PrdDesignVersionDO version : versionDao.listByDesign(design.getWorkspaceId(), design.getId())) {
                vo.getVersions().add(toVersionVO(version, false));
            }
        }
        return vo;
    }

    private PrdDesignVersionVO toVersionVO(PrdDesignVersionDO version, boolean includeDesign) {
        PrdDesignVersionVO vo = new PrdDesignVersionVO();
        vo.setId(version.getId());
        vo.setVersion(version.getVersion());
        vo.setDesignId(version.getDesignId());
        vo.setVersionNo(version.getVersionNo());
        vo.setStatus(version.getStatus());
        vo.setSourceVersionId(version.getSourceVersionId());
        vo.setDesignSha256(version.getDesignSha256());
        vo.setHtmlOssRef(version.getHtmlOssRef());
        vo.setHtmlSha256(version.getHtmlSha256());
        vo.setHtmlSize(version.getHtmlSize());
        vo.setRendererVersion(version.getRendererVersion());
        vo.setSummary(version.getSummary());
        vo.setChangeSummary(version.getChangeSummary());
        vo.setRequirementCount(version.getRequirementCount());
        vo.setOpenQuestionCount(version.getOpenQuestionCount());
        vo.setBlockingQuestionCount(version.getBlockingQuestionCount());
        vo.setAiSessionId(version.getAiSessionId());
        vo.setConfirmedBy(version.getConfirmedBy());
        vo.setConfirmedAt(version.getConfirmedAt());
        vo.setConfirmedSha256(version.getConfirmedSha256());
        vo.setGmtCreate(version.getGmtCreate());
        if (includeDesign) {
            vo.setDesignJson(version.getDesignJson());
        }
        return vo;
    }
}
