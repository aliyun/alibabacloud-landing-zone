package com.aliyun.autowonder.frontendproject;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.frontendproject.dto.FrontendProjectProbeVO;
import com.aliyun.autowonder.frontendproject.dto.FrontendProjectProfileVO;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Frontend project probing: enqueue a durable run, execute it against a checked-out repo, and
 * persist immutable profile snapshots.
 *
 * <p>Execution is driven by {@link FrontendProjectProbeScheduler}, not by an in-request thread, so
 * a run survives a page refresh and can be recovered after a crash (a stuck run is swept by the
 * scheduler). This mirrors the dispatch/ai compensation pattern rather than adding a new engine.
 *
 * <p>Space isolation: this domain uses {@code workspace_id} and is deliberately absent from
 * {@code TenantTables}, so every read here re-checks ownership explicitly and no query may omit
 * the workspace predicate. Repo access is validated before any checkout, so a repo the caller
 * cannot read never enters a probe.
 */
@Service
public class FrontendProjectProbeService {

    private static final Logger log = LoggerFactory.getLogger(FrontendProjectProbeService.class);

    private static final int ERROR_MAX_LEN = 1000;
    private static final int LIST_LIMIT = 50;
    private static final int PROFILE_LIST_LIMIT = 200;

    private final FrontendProjectProbeDao probeDao;
    private final FrontendProjectProfileDao profileDao;
    private final RepoDao repoDao;
    private final RepoCheckout repoCheckout;

    public FrontendProjectProbeService(FrontendProjectProbeDao probeDao,
                                       FrontendProjectProfileDao profileDao,
                                       RepoDao repoDao,
                                       RepoCheckout repoCheckout) {
        this.probeDao = probeDao;
        this.profileDao = profileDao;
        this.repoDao = repoDao;
        this.repoCheckout = repoCheckout;
    }

    /** Discover all configured repositories; reuse prior probes instead of duplicating work. */
    public List<FrontendProjectProbeVO> discover(long workspaceId, long userId) {
        List<FrontendProjectProbeVO> result = new ArrayList<>();
        List<RepoDO> repos = repoDao.listAllByTenant(workspaceId);
        if (repos == null) {
            return result;
        }
        for (RepoDO repo : repos) {
            requireRepo(workspaceId, repo.getId());
            FrontendProjectProbeDO existing = probeDao.findLatestByRepo(workspaceId, repo.getId());
            result.add(existing == null ? startProbe(workspaceId, repo.getId(), userId)
                    : getLatestByRepo(workspaceId, repo.getId()));
        }
        return result;
    }

    /** Validate access, reject a concurrent run, and enqueue a QUEUED probe. */
    public FrontendProjectProbeVO startProbe(long workspaceId, long repoId, long userId) {
        RepoDO repo = requireRepo(workspaceId, repoId);
        FrontendProjectProbeDO latest = probeDao.findLatestByRepo(workspaceId, repoId);
        if (latest != null && ProbeStatus.isActive(latest.getStatus())) {
            throw new BizException(ErrorCode.FRONTEND_PROJECT_PROBE_ACTIVE);
        }
        FrontendProjectProbeDO probe = new FrontendProjectProbeDO();
        probe.setWorkspaceId(workspaceId);
        probe.setRepoId(repo.getId());
        probe.setStatus(ProbeStatus.QUEUED);
        probe.setDetectorVersion(FrontendProjectScanner.DETECTOR_VERSION);
        probe.setBranch(repo.getDefaultBranch());
        probe.setVersion(0);
        probe.setCreatorId(userId);
        probeDao.insert(probe);
        log.info("frontend probe queued probeId={} workspaceId={} repoId={}",
                probe.getId(), workspaceId, repoId);
        return toProbeVO(probe, repo.getName(), List.of());
    }

    /**
     * Claim and execute one probe. Returns true when this call performed the work, false when the
     * run was already claimed by another worker (CAS lost) or is not QUEUED.
     */
    public boolean executeProbe(long probeId) {
        FrontendProjectProbeDO probe = probeDao.findByIdForExecution(probeId);
        if (probe == null) {
            log.warn("frontend probe execute skipped, not found probeId={}", probeId);
            return false;
        }
        return executeProbe(probe);
    }

    boolean executeProbe(FrontendProjectProbeDO probe) {
        if (!ProbeStatus.QUEUED.equals(probe.getStatus())) {
            return false;
        }
        // Capture before the CAS: the claim bumps the row version, and the follow-up
        // SUCCEEDED/FAILED update must target the post-claim version.
        int queuedVersion = probe.getVersion();
        // CAS claim: only one worker moves QUEUED->RUNNING; the loser backs off.
        if (probeDao.updateRunning(probe.getId(), probe.getWorkspaceId(), null, queuedVersion) == 0) {
            return false;
        }
        int runningVersion = queuedVersion + 1;
        Path workDir = Path.of("/tmp/aiw/fp-" + probe.getId());
        try {
            RepoDO repo = repoDao.findById(probe.getRepoId());
            if (repo == null || repo.getTenantId() == null
                    || !repo.getTenantId().equals(probe.getWorkspaceId())) {
                fail(probe, runningVersion, "repo not found for probe: " + probe.getRepoId());
                return true;
            }
            Files.createDirectories(workDir);
            RepoCheckout.Checkout checkout = repoCheckout.checkout(repo, workDir);

            FrontendProjectScanner.ScanResult scan = FrontendProjectScanner.scan(checkout.root());
            persistProfiles(probe, checkout, scan);

            probeDao.updateSucceeded(probe.getId(), probe.getWorkspaceId(), checkout.branch(),
                    checkout.commitSha(), scan.candidates().size(), runningVersion);
            log.info("frontend probe succeeded probeId={} repoId={} commit={} candidates={}",
                    probe.getId(), probe.getRepoId(), checkout.commitSha(), scan.candidates().size());
            return true;
        } catch (RepoCheckout.RepoCheckoutException e) {
            fail(probe, runningVersion, e.getMessage());
            return true;
        } catch (IOException | RuntimeException e) {
            log.error("frontend probe failed probeId={}", probe.getId(), e);
            fail(probe, runningVersion, e.getMessage());
            return true;
        } finally {
            deleteQuietly(workDir);
        }
    }

    private void persistProfiles(FrontendProjectProbeDO probe, RepoCheckout.Checkout checkout,
                                 FrontendProjectScanner.ScanResult scan) {
        for (FrontendProjectScanner.CandidateApp app : scan.candidates()) {
            FrontendProjectProfileDO existing = profileDao.findByCacheKey(
                    probe.getWorkspaceId(), probe.getRepoId(), checkout.commitSha(),
                    FrontendProjectProfileCodec.appRootHash(app.appRoot()),
                    FrontendProjectScanner.DETECTOR_VERSION);
            if (existing != null) {
                // Same repo+commit+appRoot+detector => identical snapshot; never rewrite it, so a
                // profile already referenced by a confirmed design stays byte-for-byte stable.
                continue;
            }
            FrontendProjectProfileDO profile = new FrontendProjectProfileDO();
            profile.setWorkspaceId(probe.getWorkspaceId());
            profile.setRepoId(probe.getRepoId());
            profile.setProbeId(probe.getId());
            profile.setBranch(checkout.branch());
            profile.setCommitSha(checkout.commitSha());
            profile.setAppRoot(app.appRoot());
            profile.setAppRootHash(FrontendProjectProfileCodec.appRootHash(app.appRoot()));
            profile.setPackageName(app.packageName());
            profile.setDetectorVersion(FrontendProjectScanner.DETECTOR_VERSION);
            profile.setProfileJson(FrontendProjectProfileCodec.encodeProfile(scan, app));
            profile.setEvidenceJson(FrontendProjectProfileCodec.encodeEvidence(scan, app));
            profile.setLooksLikeLibrary(app.looksLikeLibrary());
            profile.setVersion(0);
            profile.setCreatorId(probe.getCreatorId());
            profileDao.insertIgnore(profile);
        }
    }

    private void fail(FrontendProjectProbeDO probe, int runningVersion, String reason) {
        String error = reason == null ? "unknown error" : reason;
        if (error.length() > ERROR_MAX_LEN) {
            error = error.substring(0, ERROR_MAX_LEN);
        }
        probeDao.updateFailed(probe.getId(), probe.getWorkspaceId(), error, runningVersion);
        log.warn("frontend probe failed probeId={} reason={}", probe.getId(), error);
    }

    // ---------------------------------------------------------------- queries

    public FrontendProjectProbeVO getProbe(long workspaceId, long probeId) {
        FrontendProjectProbeDO probe = probeDao.findById(probeId, workspaceId);
        if (probe == null) {
            throw new BizException(ErrorCode.FRONTEND_PROJECT_PROBE_NOT_FOUND);
        }
        RepoDO repo = repoDao.findById(probe.getRepoId());
        List<FrontendProjectProfileDO> profiles =
                profileDao.listByProbe(workspaceId, probeId);
        return toProbeVO(probe, repo == null ? null : repo.getName(), profiles);
    }

    /** The latest probe for a repo plus its profiles, or an empty VO when never probed. */
    public FrontendProjectProbeVO getLatestByRepo(long workspaceId, long repoId) {
        requireRepo(workspaceId, repoId);
        FrontendProjectProbeDO probe = probeDao.findLatestByRepo(workspaceId, repoId);
        if (probe == null) {
            return null;
        }
        RepoDO repo = repoDao.findById(repoId);
        return toProbeVO(probe, repo == null ? null : repo.getName(),
                profileDao.listByProbe(workspaceId, probe.getId()));
    }

    public List<FrontendProjectProfileVO> listProfilesByRepo(long workspaceId, long repoId) {
        requireRepo(workspaceId, repoId);
        List<FrontendProjectProfileVO> out = new ArrayList<>();
        for (FrontendProjectProfileDO profile : profileDao.listByRepo(workspaceId, repoId, PROFILE_LIST_LIMIT)) {
            out.add(toProfileVO(profile, false));
        }
        return out;
    }

    public FrontendProjectProfileVO getProfile(long workspaceId, long profileId) {
        FrontendProjectProfileDO profile = profileDao.findById(profileId, workspaceId);
        if (profile == null) {
            throw new BizException(ErrorCode.FRONTEND_PROJECT_PROFILE_NOT_FOUND);
        }
        return toProfileVO(profile, false);
    }

    public List<FrontendProjectProbeVO> listProbesByRepo(long workspaceId, long repoId) {
        requireRepo(workspaceId, repoId);
        List<FrontendProjectProbeVO> out = new ArrayList<>();
        for (FrontendProjectProbeDO probe : probeDao.listByRepo(workspaceId, repoId, LIST_LIMIT)) {
            out.add(toProbeVO(probe, null, List.of()));
        }
        return out;
    }

    /** Lifecycle cleanup hook: drop cached profiles when a repo leaves the workspace. */
    public void deleteByRepo(long workspaceId, long repoId) {
        profileDao.deleteByRepo(workspaceId, repoId);
    }

    private RepoDO requireRepo(long workspaceId, long repoId) {
        RepoDO repo = repoDao.findById(repoId);
        if (repo == null || repo.getTenantId() == null || repo.getTenantId() != workspaceId) {
            throw new BizException(ErrorCode.REPO_NOT_FOUND);
        }
        return repo;
    }

    // ---------------------------------------------------------------- mapping

    private FrontendProjectProbeVO toProbeVO(FrontendProjectProbeDO probe, String repoName,
                                             List<FrontendProjectProfileDO> profiles) {
        FrontendProjectProbeVO vo = new FrontendProjectProbeVO();
        vo.setId(probe.getId());
        vo.setRepoId(probe.getRepoId());
        vo.setRepoName(repoName);
        vo.setStatus(probe.getStatus());
        vo.setDetectorVersion(probe.getDetectorVersion());
        vo.setBranch(probe.getBranch());
        vo.setCommitSha(probe.getCommitSha());
        vo.setCandidateCount(probe.getCandidateCount());
        vo.setError(probe.getError());
        vo.setStartedAt(probe.getStartedAt());
        vo.setFinishedAt(probe.getFinishedAt());
        vo.setGmtCreate(probe.getGmtCreate());
        for (FrontendProjectProfileDO profile : profiles) {
            vo.getProfiles().add(toProfileVO(profile, false));
        }
        return vo;
    }

    private FrontendProjectProfileVO toProfileVO(FrontendProjectProfileDO profile, boolean stale) {
        FrontendProjectProfileVO vo = new FrontendProjectProfileVO();
        vo.setId(profile.getId());
        vo.setRepoId(profile.getRepoId());
        vo.setProbeId(profile.getProbeId());
        vo.setBranch(profile.getBranch());
        vo.setCommitSha(profile.getCommitSha());
        vo.setAppRoot(profile.getAppRoot());
        vo.setPackageName(profile.getPackageName());
        vo.setDetectorVersion(profile.getDetectorVersion());
        vo.setLooksLikeLibrary(Boolean.TRUE.equals(profile.getLooksLikeLibrary()));
        vo.setStale(stale);

        FrontendProjectProfileCodec.ProfileView view =
                FrontendProjectProfileCodec.decodeProfile(profile.getProfileJson());
        vo.setLanguage(view.language());
        vo.setPackageManager(view.packageManager());
        vo.setFrameworks(view.frameworks());
        vo.setBuildTools(view.buildTools());
        vo.setUiLibraries(view.uiLibraries());
        vo.setRouters(view.routers());
        vo.setI18nLibraries(view.i18nLibraries());
        vo.setTestTools(view.testTools());
        vo.setInferences(view.inferences());
        vo.setUnknowns(view.unknowns());

        JSONObject evidence = profile.getEvidenceJson() == null
                ? new JSONObject() : JSON.parseObject(profile.getEvidenceJson());
        for (FrontendProjectProfileVO.EvidenceVO e : readEvidence(evidence.getJSONArray("appEvidence"))) {
            vo.getEvidence().add(e);
        }
        for (FrontendProjectProfileVO.EvidenceVO e : readEvidence(evidence.getJSONArray("repoEvidence"))) {
            vo.getEvidence().add(e);
        }
        return vo;
    }

    private List<FrontendProjectProfileVO.EvidenceVO> readEvidence(JSONArray array) {
        List<FrontendProjectProfileVO.EvidenceVO> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (Object item : array) {
            if (item instanceof JSONObject object) {
                FrontendProjectProfileVO.EvidenceVO e = new FrontendProjectProfileVO.EvidenceVO();
                e.setFact(object.getString("fact"));
                e.setFilePath(object.getString("filePath"));
                out.add(e);
            }
        }
        return out;
    }

    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        } catch (Exception e) {
            log.warn("frontend probe workdir cleanup failed path={}", path, e);
        }
    }
}
