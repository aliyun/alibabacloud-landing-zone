package com.aliyun.autowonder.frontendproject;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.frontendproject.dto.FrontendProjectProbeVO;
import com.aliyun.autowonder.frontendproject.dto.FrontendProjectProfileVO;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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
 * Probe orchestration against in-memory fakes. Covers the behaviours that cannot be re-derived from
 * the SQL: single-worker claim, snapshot immutability across re-probes, space isolation, and
 * checkout failure leaving a clear retryable error.
 */
class FrontendProjectProbeServiceTest {

    private static final long WS = 1L;
    private static final long OTHER_WS = 2L;

    @Test
    void enqueuesProbeAndRejectsConcurrentRun() {
        Fixture f = new Fixture();
        FrontendProjectProbeVO probe = f.service.startProbe(WS, 10L, 7L);

        assertEquals(ProbeStatus.QUEUED, probe.getStatus());
        assertEquals(FrontendProjectScanner.DETECTOR_VERSION, probe.getDetectorVersion());
        assertEquals("web", probe.getRepoName());

        BizException active = assertThrows(BizException.class, () -> f.service.startProbe(WS, 10L, 7L));
        assertEquals(ErrorCode.FRONTEND_PROJECT_PROBE_ACTIVE.getCode(), active.getCode());
    }

    @Test
    void executesQueuedProbeAndPersistsProfiles() throws IOException {
        Fixture f = new Fixture();
        f.checkout.repoRoot = monorepo();
        f.checkout.branch = "main";
        f.checkout.commitSha = "a".repeat(40);
        FrontendProjectProbeVO enqueued = f.service.startProbe(WS, 10L, 7L);

        boolean executed = f.service.executeProbe(enqueued.getId());

        assertTrue(executed, "this worker must have claimed the run");
        FrontendProjectProbeVO done = f.service.getProbe(WS, enqueued.getId());
        assertEquals(ProbeStatus.SUCCEEDED, done.getStatus());
        assertEquals("a".repeat(40), done.getCommitSha());
        assertEquals("main", done.getBranch());
        assertEquals(2, done.getCandidateCount());
        assertEquals(2, done.getProfiles().size());

        FrontendProjectProfileVO app = done.getProfiles().stream()
                .filter(p -> "apps/web".equals(p.getAppRoot())).findFirst().orElseThrow();
        assertEquals(List.of("React"), app.getFrameworks());
        assertFalse(app.getEvidence().isEmpty());
        assertTrue(app.getEvidence().stream().allMatch(e -> e.getFact() != null && e.getFilePath() != null));
        // Routing genuinely absent => reported as unknown, never invented.
        assertTrue(app.getUnknowns().stream().anyMatch(u -> u.contains("路由")));
    }

    @Test
    void onlyOneWorkerClaimsARun() throws IOException {
        Fixture f = new Fixture();
        f.checkout.repoRoot = monorepo();
        f.checkout.commitSha = "b".repeat(40);
        FrontendProjectProbeVO enqueued = f.service.startProbe(WS, 10L, 7L);

        assertTrue(f.service.executeProbe(enqueued.getId()));
        // Re-driving a terminal run must not re-execute or duplicate snapshots.
        assertFalse(f.service.executeProbe(enqueued.getId()));
        assertEquals(2, f.profiles.values().stream()
                .filter(p -> p.getWorkspaceId() == WS).count());
    }

    @Test
    void reprobeAtSameCommitDoesNotRewriteExistingSnapshot() throws IOException {
        Fixture f = new Fixture();
        f.checkout.repoRoot = monorepo();
        f.checkout.commitSha = "c".repeat(40);
        Long first = f.service.startProbe(WS, 10L, 7L).getId();
        f.service.executeProbe(first);

        FrontendProjectProfileVO before = f.service.listProfilesByRepo(WS, 10L).get(0);
        long snapshotId = before.getId();
        Date createdBefore = f.profiles.get(snapshotId).getGmtCreate();

        // A second probe at the same commit hits the cache key and must leave the row untouched,
        // so a design already referencing this snapshot stays byte-for-byte stable.
        Long second = f.service.startProbe(WS, 10L, 7L).getId();
        f.service.executeProbe(second);

        assertEquals(ProbeStatus.SUCCEEDED, f.service.getProbe(WS, second).getStatus());
        assertEquals(2, f.profiles.size(), "no duplicate snapshots for the same cache key");
        assertEquals(snapshotId, f.service.listProfilesByRepo(WS, 10L).get(0).getId());
        assertEquals(createdBefore, f.profiles.get(snapshotId).getGmtCreate());
    }

    @Test
    void reprobeAtNewCommitAddsSnapshotWithoutTouchingHistory() throws IOException {
        Fixture f = new Fixture();
        f.checkout.repoRoot = monorepo();
        f.checkout.commitSha = "d".repeat(40);
        Long first = f.service.startProbe(WS, 10L, 7L).getId();
        f.service.executeProbe(first);

        f.checkout.commitSha = "e".repeat(40);
        Long second = f.service.startProbe(WS, 10L, 7L).getId();
        f.service.executeProbe(second);

        assertEquals(4, f.profiles.size(), "old commit snapshots are preserved as history");
        assertTrue(f.profiles.values().stream().anyMatch(p -> "d".repeat(40).equals(p.getCommitSha())));
        assertTrue(f.profiles.values().stream().anyMatch(p -> "e".repeat(40).equals(p.getCommitSha())));
    }

    @Test
    void checkoutFailureIsTerminalAndRetryableAndKeepsPriorSuccess() throws IOException {
        Fixture f = new Fixture();
        f.checkout.repoRoot = monorepo();
        f.checkout.commitSha = "f".repeat(40);
        Long ok = f.service.startProbe(WS, 10L, 7L).getId();
        f.service.executeProbe(ok);

        f.checkout.failure = "permission denied (publickey)";
        Long failed = f.service.startProbe(WS, 10L, 7L).getId();
        assertTrue(f.service.executeProbe(failed));

        FrontendProjectProbeVO vo = f.service.getProbe(WS, failed);
        assertEquals(ProbeStatus.FAILED, vo.getStatus());
        assertEquals("permission denied (publickey)", vo.getError());
        assertNotNull(vo.getFinishedAt());
        // The earlier successful result survives, per the spec's failure contract.
        assertEquals(ProbeStatus.SUCCEEDED, f.service.getProbe(WS, ok).getStatus());
        assertEquals(2, f.service.listProfilesByRepo(WS, 10L).size());
    }

    @Test
    void unknownRunTypeYieldsSucceededProbeWithZeroCandidates() throws IOException {
        Fixture f = new Fixture();
        Path bare = Files.createTempDirectory("bare");
        Files.writeString(bare.resolve("README.md"), "backend only");
        f.checkout.repoRoot = bare;
        f.checkout.commitSha = "0".repeat(40);

        Long id = f.service.startProbe(WS, 10L, 7L).getId();
        f.service.executeProbe(id);

        FrontendProjectProbeVO vo = f.service.getProbe(WS, id);
        assertEquals(ProbeStatus.SUCCEEDED, vo.getStatus());
        assertEquals(0, vo.getCandidateCount());
        assertTrue(vo.getProfiles().isEmpty());
    }

    @Test
    void crossSpaceRepoIsRejected() {
        Fixture f = new Fixture();
        BizException notFound = assertThrows(BizException.class,
                () -> f.service.startProbe(OTHER_WS, 10L, 7L));
        assertEquals(ErrorCode.REPO_NOT_FOUND.getCode(), notFound.getCode());
    }

    @Test
    void readsAreWorkspaceScoped() throws IOException {
        Fixture f = new Fixture();
        f.checkout.repoRoot = monorepo();
        f.checkout.commitSha = "1".repeat(40);
        // Probe repo 11, which belongs to WS.
        Long id = f.service.startProbe(WS, 11L, 7L).getId();
        f.service.executeProbe(id);
        assertEquals(2, f.service.listProfilesByRepo(WS, 11L).size());

        // Another workspace must not see the probe or its profiles.
        BizException crossSpace = assertThrows(BizException.class, () -> f.service.getProbe(OTHER_WS, id));
        assertEquals(ErrorCode.FRONTEND_PROJECT_PROBE_NOT_FOUND.getCode(), crossSpace.getCode());
        // And repo 10, never probed, has no profiles.
        assertTrue(f.service.listProfilesByRepo(WS, 10L).isEmpty());
    }

    @Test
    void errorReasonIsTruncatedToColumnWidth() throws IOException {
        Fixture f = new Fixture();
        f.checkout.failure = "x".repeat(5000);
        Long id = f.service.startProbe(WS, 10L, 7L).getId();
        f.service.executeProbe(id);

        assertEquals(1000, f.service.getProbe(WS, id).getError().length());
    }

    @Test
    void deleteByRepoClearsCachedProfiles() throws IOException {
        Fixture f = new Fixture();
        f.checkout.repoRoot = monorepo();
        f.checkout.commitSha = "2".repeat(40);
        Long id = f.service.startProbe(WS, 10L, 7L).getId();
        f.service.executeProbe(id);
        assertFalse(f.profiles.isEmpty());

        f.service.deleteByRepo(WS, 10L);

        assertTrue(f.profiles.isEmpty());
    }

    // ------------------------------------------------------------------ fixture

    private static Path monorepo() throws IOException {
        Path root = Files.createTempDirectory("mono");
        write(root, "package.json", """
                { "name": "mono", "private": true, "workspaces": ["apps/*"] }
                """);
        write(root, "apps/web/package.json", """
                { "name": "web", "private": true, "dependencies": { "react": "^18.0.0" },
                  "devDependencies": { "vite": "^5.0.0", "typescript": "^5.0.0" } }
                """);
        write(root, "apps/web/index.html", "<html></html>");
        write(root, "apps/admin/package.json", """
                { "name": "admin", "private": true, "dependencies": { "vue": "^3.0.0" } }
                """);
        write(root, "backend/package.json", """
                { "name": "backend", "private": true, "dependencies": { "express": "^4.0.0" } }
                """);
        return root;
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static final class Fixture {
        final InMemoryProbeDao probeDao = new InMemoryProbeDao();
        final InMemoryProfileDao profileDao = new InMemoryProfileDao();
        final InMemoryRepoDao repoDao = new InMemoryRepoDao();
        final StubCheckout checkout = new StubCheckout();
        final FrontendProjectProbeService service =
                new FrontendProjectProbeService(probeDao, profileDao, repoDao, checkout);
        final Map<Long, FrontendProjectProfileDO> profiles = profileDao.rows;

        Fixture() {
            repoDao.put(repo(10L, WS, "web"));
            repoDao.put(repo(11L, WS, "mixed"));
        }

        private static RepoDO repo(long id, long workspaceId, String name) {
            RepoDO repo = new RepoDO();
            repo.setId(id);
            repo.setTenantId(workspaceId);
            repo.setName(name);
            repo.setUrl("git@example.com:" + name + ".git");
            repo.setDefaultBranch("main");
            repo.setVersion(0);
            return repo;
        }
    }

    private static final class StubCheckout implements RepoCheckout {
        Path repoRoot;
        String branch = "main";
        String commitSha;
        String failure;

        @Override
        public Checkout checkout(RepoDO repo, Path workDir) {
            if (failure != null) {
                throw new RepoCheckoutException(failure);
            }
            if (repoRoot == null) {
                throw new RepoCheckoutException("no repo root configured");
            }
            return new Checkout(repoRoot, branch, commitSha);
        }
    }

    @Test
    void discoveryReusesJobsAndNeverScansAnotherWorkspace() {
        Fixture f = new Fixture();
        List<FrontendProjectProbeVO> first = f.service.discover(WS, 7L);
        List<FrontendProjectProbeVO> again = f.service.discover(WS, 7L);
        assertEquals(2, first.size());
        assertEquals(first.stream().map(FrontendProjectProbeVO::getId).toList(),
                again.stream().map(FrontendProjectProbeVO::getId).toList());
        assertTrue(f.service.discover(99999L, 7L).isEmpty());
    }

    private static final class InMemoryRepoDao implements RepoDao {
        final Map<Long, RepoDO> rows = new HashMap<>();

        void put(RepoDO repo) {
            rows.put(repo.getId(), repo);
        }

        @Override public void insert(RepoDO repo) { rows.put(repo.getId(), repo); }
        @Override public RepoDO findById(Long id) { return id == null ? null : rows.get(id); }
        @Override public List<RepoDO> list(Long tenantId, int offset, int limit) {
            return listAllByTenant(tenantId).stream().skip(offset).limit(limit).toList();
        }
        @Override public List<RepoDO> listAllByTenant(Long tenantId) {
            return rows.values().stream().filter(r -> r.getTenantId().equals(tenantId)).toList();
        }
        @Override public int update(Long id, Long tenantId, String name, String url, String defaultBranch,
                                    String description, Integer version, Long modifierId) { return 1; }
        @Override public int updateScanStatus(Long id, Long tenantId, String scanStatus,
                                              Integer version, Long modifierId) { return 1; }
        @Override public int softDelete(Long id, Long tenantId, Integer version, Long modifierId) {
            return rows.remove(id) == null ? 0 : 1;
        }
    }

    private static final class InMemoryProbeDao implements FrontendProjectProbeDao {
        final Map<Long, FrontendProjectProbeDO> rows = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(10000);

        @Override public void insert(FrontendProjectProbeDO probe) {
            probe.setId(ids.incrementAndGet());
            probe.setVersion(0);
            probe.setGmtCreate(new Date());
            probe.setGmtModified(new Date());
            rows.put(probe.getId(), probe);
        }

        @Override public FrontendProjectProbeDO findById(Long id, Long workspaceId) {
            FrontendProjectProbeDO probe = rows.get(id);
            return probe != null && probe.getWorkspaceId().equals(workspaceId) ? probe : null;
        }

        @Override public FrontendProjectProbeDO findByIdForExecution(Long id) { return rows.get(id); }

        @Override public FrontendProjectProbeDO findLatestByRepo(Long workspaceId, Long repoId) {
            return rows.values().stream()
                    .filter(p -> p.getWorkspaceId().equals(workspaceId) && p.getRepoId().equals(repoId))
                    .max(java.util.Comparator.comparingLong(FrontendProjectProbeDO::getId)).orElse(null);
        }

        @Override public List<FrontendProjectProbeDO> listByRepo(Long workspaceId, Long repoId, int limit) {
            return rows.values().stream()
                    .filter(p -> p.getWorkspaceId().equals(workspaceId) && p.getRepoId().equals(repoId))
                    .sorted(java.util.Comparator.comparingLong(FrontendProjectProbeDO::getId).reversed())
                    .limit(limit).toList();
        }

        @Override public List<FrontendProjectProbeDO> listByStatus(String status, int limit) {
            return rows.values().stream().filter(p -> status.equals(p.getStatus())).limit(limit).toList();
        }

        @Override public int updateStatus(Long id, Long workspaceId, String status,
                                          Integer expectedVersion, Long modifierId) {
            return cas(id, workspaceId, expectedVersion, p -> p.setStatus(status));
        }

        @Override public int updateRunning(Long id, Long workspaceId, String nodeId, Integer expectedVersion) {
            return cas(id, workspaceId, expectedVersion, p -> {
                if (!ProbeStatus.QUEUED.equals(p.getStatus())) {
                    throw new IllegalStateException("not queued");
                }
                p.setStatus(ProbeStatus.RUNNING);
                p.setNodeId(nodeId);
                p.setStartedAt(new Date());
            });
        }

        @Override public int updateSucceeded(Long id, Long workspaceId, String branch, String commitSha,
                                             Integer candidateCount, Integer expectedVersion) {
            return cas(id, workspaceId, expectedVersion, p -> {
                p.setStatus(ProbeStatus.SUCCEEDED);
                p.setBranch(branch);
                p.setCommitSha(commitSha);
                p.setCandidateCount(candidateCount);
                p.setError(null);
                p.setFinishedAt(new Date());
            });
        }

        @Override public int updateFailed(Long id, Long workspaceId, String error, Integer expectedVersion) {
            return cas(id, workspaceId, expectedVersion, p -> {
                p.setStatus(ProbeStatus.FAILED);
                p.setError(error);
                p.setFinishedAt(new Date());
            });
        }

        @Override public List<FrontendProjectProbeDO> listStuck(List<String> statuses, long beforeMs, int limit) {
            return rows.values().stream().filter(p -> statuses.contains(p.getStatus())).limit(limit).toList();
        }

        private int cas(Long id, Long workspaceId, Integer expectedVersion,
                        java.util.function.Consumer<FrontendProjectProbeDO> mutation) {
            FrontendProjectProbeDO probe = rows.get(id);
            if (probe == null || !probe.getWorkspaceId().equals(workspaceId)
                    || !probe.getVersion().equals(expectedVersion)) {
                return 0;
            }
            try {
                mutation.accept(probe);
            } catch (IllegalStateException e) {
                return 0;
            }
            probe.setVersion(probe.getVersion() + 1);
            probe.setGmtModified(new Date());
            return 1;
        }
    }

    private static final class InMemoryProfileDao implements FrontendProjectProfileDao {
        final Map<Long, FrontendProjectProfileDO> rows = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(20000);

        @Override public int insertIgnore(FrontendProjectProfileDO profile) {
            if (findByCacheKey(profile.getWorkspaceId(), profile.getRepoId(), profile.getCommitSha(),
                    profile.getAppRootHash(), profile.getDetectorVersion()) != null) {
                return 0;
            }
            profile.setId(ids.incrementAndGet());
            profile.setVersion(0);
            profile.setGmtCreate(new Date());
            profile.setGmtModified(new Date());
            rows.put(profile.getId(), profile);
            return 1;
        }

        @Override public FrontendProjectProfileDO findById(Long id, Long workspaceId) {
            FrontendProjectProfileDO profile = rows.get(id);
            return profile != null && profile.getWorkspaceId().equals(workspaceId) ? profile : null;
        }

        @Override public FrontendProjectProfileDO findByCacheKey(Long workspaceId, Long repoId, String commitSha,
                                                                 String appRootHash, String detectorVersion) {
            return rows.values().stream()
                    .filter(p -> p.getWorkspaceId().equals(workspaceId) && p.getRepoId().equals(repoId)
                            && p.getCommitSha().equals(commitSha) && p.getAppRootHash().equals(appRootHash)
                            && p.getDetectorVersion().equals(detectorVersion))
                    .findFirst().orElse(null);
        }

        @Override public List<FrontendProjectProfileDO> listByRepo(Long workspaceId, Long repoId, int limit) {
            return rows.values().stream()
                    .filter(p -> p.getWorkspaceId().equals(workspaceId) && p.getRepoId().equals(repoId))
                    .sorted(java.util.Comparator.comparingLong(FrontendProjectProfileDO::getId).reversed())
                    .limit(limit).toList();
        }

        @Override public List<FrontendProjectProfileDO> listByProbe(Long workspaceId, Long probeId) {
            return rows.values().stream()
                    .filter(p -> p.getWorkspaceId().equals(workspaceId) && p.getProbeId().equals(probeId))
                    .sorted(java.util.Comparator.comparing(FrontendProjectProfileDO::getAppRoot))
                    .toList();
        }

        @Override public List<FrontendProjectProfileDO> listByCommit(Long workspaceId, Long repoId,
                                                                     String commitSha, String detectorVersion) {
            return rows.values().stream()
                    .filter(p -> p.getWorkspaceId().equals(workspaceId) && p.getRepoId().equals(repoId)
                            && p.getCommitSha().equals(commitSha)
                            && p.getDetectorVersion().equals(detectorVersion))
                    .toList();
        }

        @Override public int deleteByRepo(Long workspaceId, Long repoId) {
            List<Long> doomed = rows.values().stream()
                    .filter(p -> p.getWorkspaceId().equals(workspaceId) && p.getRepoId().equals(repoId))
                    .map(FrontendProjectProfileDO::getId).toList();
            doomed.forEach(rows::remove);
            return doomed.size();
        }
    }
}
