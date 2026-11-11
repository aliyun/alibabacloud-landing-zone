package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.agent.AgentRepoPermDO;
import com.aliyun.autowonder.agent.AgentRepoPermDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.frontendproject.FrontendProjectProfileDO;
import com.aliyun.autowonder.frontendproject.FrontendProjectProfileDao;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.taskpackage.PrdDesignPackage;
import com.aliyun.autowonder.util.MessageDigestUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Freezing and verifying delivery inputs. These are the behaviours the spec calls out as
 * non-negotiable: the executor gets the confirmed version, retries never drift onto a newer one,
 * and a failed check stops dispatch instead of degrading to the latest draft.
 */
class PrdDeliveryFreezeServiceTest {

    private static final long WS = 1L;
    private static final long WORKITEM = 10L;
    private static final long DISPATCH = 77L;
    private static final long AGENT_VERSION = 33L;
    private static final long REPO = 42L;
    private static final long PROFILE = 55L;

    @Test
    void manualTargetPackagesAndRetriesWithoutAProbeProfile() {
        Fixture f = new Fixture();
        PrdDesignData data = design();
        PrdProjectSnapshot target = new PrdProjectSnapshot();
        target.setSource("USER_CONFIGURED");
        target.setRepoId(REPO);
        target.setAppRoot(".");
        target.setProjectNotes("React / 用户配置");
        data.setProjectSnapshot(target);
        f.confirmVersion(DeliveryMode.PRD_DRIVEN, data, null);
        PrdDesignPackage first = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);
        PrdDesignPackage retry = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);
        assertEquals(first.designVersionId(), retry.designVersionId());
        JSONObject profile = JSON.parseObject(new String(
                retry.files().get(PrdDesignPackage.PROJECT_PROFILE_ENTRY), StandardCharsets.UTF_8));
        assertEquals("USER_CONFIGURED", profile.getString("source"));
        assertEquals(target.getProjectNotes(), profile.getString("projectNotes"));
        assertNull(profile.getString("probedCommitSha"));
    }

    @Test
    void noDesignKeepsTheLegacyDeliveryPath() {
        Fixture f = new Fixture();
        f.withoutDesign();

        assertNull(f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));
        assertTrue(f.bindings.isEmpty(), "no binding is pinned for a legacy delivery");
    }

    @Test
    void legacyModeWorkitemIsUntouchedEvenWithAConfirmedVersion() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.LEGACY);

        assertNull(f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));
        assertTrue(f.bindings.isEmpty());
    }

    @Test
    void prdDrivenDeliveryRequiresAConfirmedVersion() {
        Fixture f = new Fixture();
        f.enablePrdDriven();

        BizException unconfirmed = assertThrows(BizException.class,
                () -> f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));

        assertEquals(ErrorCode.PRD_NOT_CONFIRMED.getCode(), unconfirmed.getCode());
        assertTrue(f.bindings.isEmpty(), "nothing may be pinned when there is no confirmed version");
    }

    @Test
    void prdDrivenDeliveryRequiresATargetProject() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.PRD_DRIVEN);
        PrdDesignVersionDO frozen = f.versions.findById(f.designs.only().getConfirmedVersionId(), WS);
        PrdDesignData noTarget = PrdDesignCodec.parse(frozen.getDesignJson());
        noTarget.setProjectSnapshot(null);
        frozen.setDesignJson(PrdDesignCodec.serialize(noTarget));

        BizException missing = assertThrows(BizException.class,
                () -> f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));

        assertEquals(ErrorCode.PRD_TARGET_PROJECT_STALE.getCode(), missing.getCode());
    }

    @Test
    void packagesTheFourFrozenDesignFiles() {
        Fixture f = new Fixture();
        long versionId = f.confirm(DeliveryMode.PRD_DRIVEN);

        PrdDesignPackage pkg = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);

        assertNotNull(pkg);
        assertEquals(List.of(PrdDesignPackage.HTML_ENTRY, PrdDesignPackage.REQUIREMENTS_ENTRY,
                        PrdDesignPackage.PROJECT_PROFILE_ENTRY, PrdDesignPackage.MANIFEST_ENTRY),
                new ArrayList<>(pkg.files().keySet()));
        assertEquals(versionId, pkg.designVersionId());
        assertEquals(1, pkg.versionNo());

        // requirements.json is derived from the same design data the HTML was rendered from.
        String requirements = new String(pkg.files().get(PrdDesignPackage.REQUIREMENTS_ENTRY),
                StandardCharsets.UTF_8);
        String html = new String(pkg.files().get(PrdDesignPackage.HTML_ENTRY), StandardCharsets.UTF_8);
        assertTrue(requirements.contains("REQ-001"));
        assertTrue(html.contains("REQ-001"));

        JSONObject manifest = JSON.parseObject(new String(pkg.files().get(PrdDesignPackage.MANIFEST_ENTRY),
                StandardCharsets.UTF_8));
        assertEquals("autoWonder.prdDeliveryManifest.v1", manifest.getString("schemaVersion"));
        assertEquals(REPO, manifest.getLong("targetRepoId"));
        assertEquals("frontend", manifest.getString("targetAppRoot"));
        assertEquals("c".repeat(40), manifest.getString("probedCommitSha"));
        assertEquals(DISPATCH, manifest.getLong("dispatchId"));
        assertTrue(manifest.getJSONArray("requirementKeys").getString(0).contains("REQ-001"));

        JSONObject profile = JSON.parseObject(new String(
                pkg.files().get(PrdDesignPackage.PROJECT_PROFILE_ENTRY), StandardCharsets.UTF_8));
        assertEquals(REPO, profile.getLong("repoId"));
        assertEquals("c".repeat(40), profile.getString("probedCommitSha"));
        assertNotNull(profile.getJSONObject("profile"), "the probed snapshot travels with the package");
        assertNotNull(profile.getJSONObject("evidence"));
    }

    @Test
    void retriesReuseThePinnedVersionAndNeverDriftToANewerOne() {
        Fixture f = new Fixture();
        long confirmed = f.confirm(DeliveryMode.PRD_DRIVEN);
        PrdDesignPackage first = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);
        assertEquals(1, f.bindings.size());

        // A newer version is confirmed while the first delivery is still in flight.
        long newer = f.confirmNewerVersion();
        PrdDesignPackage retry = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);

        assertEquals(first.designVersionId(), retry.designVersionId());
        assertEquals(first.versionNo(), retry.versionNo());
        assertEquals(confirmed, retry.designVersionId(), "still the version this dispatch was built from");
        assertNotEquals(newer, retry.designVersionId(),
                "a new confirmation must not change an in-flight delivery");
        assertEquals(1, f.bindings.size(), "re-packaging pins no second binding");
        assertEquals(first.designSha256(), retry.designSha256());
    }

    @Test
    void concurrentFirstPackagingPinsOneBindingOnly() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.PRD_DRIVEN);

        PrdDesignPackage first = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);
        PrdDesignPackage second = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 8L);

        assertEquals(1, f.bindings.size());
        assertEquals(first.designVersionId(), second.designVersionId());
        assertEquals(first.versionNo(), second.versionNo());
    }

    @Test
    void stopsDispatchWhenTheExecutorLacksRepoPermission() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.PRD_DRIVEN);
        f.perms.clear();

        BizException refused = assertThrows(BizException.class,
                () -> f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));

        assertEquals(ErrorCode.PRD_TARGET_PROJECT_REQUIRED.getCode(), refused.getCode());
        assertTrue(f.bindings.isEmpty(), "a refused delivery pins nothing");
    }

    @Test
    void permitsOnlyTheBoundTargetRepoNotAnyRepo() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.PRD_DRIVEN);
        // Executor may write a different repo, but not the design's target.
        f.perms.clear();
        f.perms.add(f.perm(REPO + 1));

        BizException refused = assertThrows(BizException.class,
                () -> f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));
        assertEquals(ErrorCode.PRD_TARGET_PROJECT_REQUIRED.getCode(), refused.getCode());
    }

    @Test
    void ignoresRepoPermissionsFromAnotherWorkspace() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.PRD_DRIVEN);
        f.perms.clear();
        AgentRepoPermDO foreign = f.perm(REPO);
        foreign.setTenantId(999L);
        f.perms.add(foreign);

        BizException refused = assertThrows(BizException.class,
                () -> f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));
        assertEquals(ErrorCode.PRD_TARGET_PROJECT_REQUIRED.getCode(), refused.getCode());
    }

    @Test
    void stopsDispatchWhenThePinnedVersionDisappeared() {
        Fixture f = new Fixture();
        long versionId = f.confirm(DeliveryMode.PRD_DRIVEN);
        f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);

        f.versions.rows.remove(versionId);

        BizException gone = assertThrows(BizException.class,
                () -> f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));
        assertEquals(ErrorCode.PRD_VERSION_NOT_FOUND.getCode(), gone.getCode());
    }

    @Test
    void stopsDispatchWhenTheTargetProfileDisappeared() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.PRD_DRIVEN);
        f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);

        f.profiles.rows.remove(PROFILE);

        BizException stale = assertThrows(BizException.class,
                () -> f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));
        assertEquals(ErrorCode.PRD_TARGET_PROJECT_STALE.getCode(), stale.getCode());
    }

    @Test
    void stopsDispatchWhenStoredDesignContentChangedUnderThePinnedDigest() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.PRD_DRIVEN);
        f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);

        // Content mutated behind the pinned digest — the binding must not silently accept it.
        PrdDesignVersionDO version = f.versions.rows.get(f.designs.only().getConfirmedVersionId());
        version.setDesignJson(PrdDesignCodec.serialize(mutated()));

        BizException drift = assertThrows(BizException.class,
                () -> f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));
        assertEquals(ErrorCode.PRD_DIGEST_MISMATCH.getCode(), drift.getCode());
    }

    @Test
    void stopsDispatchWhenTheHtmlArtifactIsUnreadable() {
        Fixture f = new Fixture();
        long versionId = f.confirm(DeliveryMode.PRD_DRIVEN);
        f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);

        f.storage.delete(f.versions.rows.get(versionId).getHtmlOssRef());

        BizException unreadable = assertThrows(BizException.class,
                () -> f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));
        assertEquals(ErrorCode.PRD_HTML_UNREADABLE.getCode(), unreadable.getCode());
    }

    @Test
    void stopsDispatchWhenTheHtmlArtifactWasTamperedWith() {
        Fixture f = new Fixture();
        long versionId = f.confirm(DeliveryMode.PRD_DRIVEN);
        f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);

        PrdDesignVersionDO version = f.versions.rows.get(versionId);
        String ref = version.getHtmlOssRef();
        f.storage.put(ref.substring(0, ref.indexOf('/')), ref.substring(ref.indexOf('/') + 1),
                "<html>tampered</html>".getBytes(StandardCharsets.UTF_8));

        BizException mismatch = assertThrows(BizException.class,
                () -> f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L));
        assertEquals(ErrorCode.PRD_DIGEST_MISMATCH.getCode(), mismatch.getCode());
    }

    @Test
    void anotherDispatchPinsItsOwnBindingFromTheThenCurrentVersion() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.PRD_DRIVEN);
        PrdDesignPackage first = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);

        long newer = f.confirmNewerVersion();
        PrdDesignPackage second = f.service.resolve(WS, DISPATCH + 1, WORKITEM, AGENT_VERSION, 7L);

        assertEquals(newer, second.designVersionId(), "a new delivery starts from the current version");
        assertFalse(first.designVersionId().equals(second.designVersionId()),
                "the in-flight dispatch keeps its own pinned version");
        assertEquals(2, second.versionNo());
        assertEquals(2, f.bindings.size());
    }

    @Test
    void packagedHtmlIsByteIdenticalToTheStoredRender() {
        Fixture f = new Fixture();
        long versionId = f.confirm(DeliveryMode.PRD_DRIVEN);

        PrdDesignPackage pkg = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);
        byte[] packaged = pkg.files().get(PrdDesignPackage.HTML_ENTRY);

        PrdDesignVersionDO version = f.versions.rows.get(versionId);
        PrdDesignData data = PrdDesignCodec.parse(version.getDesignJson());
        assertEquals(PrdHtmlRenderer.render(data), new String(packaged, StandardCharsets.UTF_8));
        assertEquals(version.getHtmlSha256(), MessageDigestUtil.sha256Hex(packaged));
    }

    // ------------------------------------------------------------------ fixture

    private static PrdDesignData mutated() {
        PrdDesignData data = design();
        data.setTitle("事后被改动的标题");
        return data;
    }

    private static PrdDesignData design() {
        PrdDesignData data = new PrdDesignData();
        data.setTitle("工单批量导入");
        data.setTargetProjectAdaptation("复用 frontend 应用的既有表格组件。");
        PrdDesignData.Requirement requirement = new PrdDesignData.Requirement();
        requirement.setKey("REQ-001");
        requirement.setTitle("上传 CSV");
        requirement.setAcceptanceCriteria(new ArrayList<>(List.of("超过 10MB 时拒绝并说明原因")));
        data.setRequirements(new ArrayList<>(List.of(requirement)));
        return data;
    }

    @Test
    void newDispatchUsesTheConfirmedTargetEvenAfterAnotherTargetIsSelected() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.PRD_DRIVEN);
        f.designs.only().setTargetRepoId(999L);
        f.designs.only().setTargetProfileId(999L);
        f.designs.only().setTargetAppRoot("unreviewed-app");
        PrdDesignPackage pkg = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);
        JSONObject manifest = JSON.parseObject(new String(pkg.files().get(PrdDesignPackage.MANIFEST_ENTRY),
                StandardCharsets.UTF_8));
        assertEquals(REPO, manifest.getLong("targetRepoId"));
        assertEquals("frontend", manifest.getString("targetAppRoot"));
    }

    @Test
    void changingDeliveryModeNeverRemovesInputsFromAnAlreadyPinnedDispatch() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.PRD_DRIVEN);
        PrdDesignPackage first = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);
        f.designs.only().setDeliveryMode(DeliveryMode.LEGACY);
        PrdDesignPackage retried = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);
        assertEquals(first.designVersionId(), retried.designVersionId());
        org.junit.jupiter.api.Assertions.assertArrayEquals(first.files().get(PrdDesignPackage.HTML_ENTRY),
                retried.files().get(PrdDesignPackage.HTML_ENTRY));
    }

    @Test
    void recoveryDispatchInheritsItsPredecessorInsteadOfTheNewestDesign() {
        Fixture f = new Fixture();
        f.confirm(DeliveryMode.PRD_DRIVEN);
        PrdDesignPackage first = f.service.resolve(WS, DISPATCH, WORKITEM, AGENT_VERSION, 7L);
        f.confirmNewerVersion();
        PrdDesignPackage resumed = f.service.resolve(WS, DISPATCH + 1, WORKITEM, AGENT_VERSION, 7L, DISPATCH);
        assertEquals(first.designVersionId(), resumed.designVersionId());
        org.junit.jupiter.api.Assertions.assertArrayEquals(first.files().get(PrdDesignPackage.HTML_ENTRY),
                resumed.files().get(PrdDesignPackage.HTML_ENTRY));
    }

    private final class Fixture {
        final InMemoryDesignDao designs = new InMemoryDesignDao();
        final InMemoryVersionDao versions = new InMemoryVersionDao();
        final InMemoryBindingDao bindings = new InMemoryBindingDao();
        final InMemoryProfileDao profiles = new InMemoryProfileDao();
        final PrdAcceptanceTestSupport.InMemoryAcceptanceDao acceptances =
                new PrdAcceptanceTestSupport.InMemoryAcceptanceDao();
        final List<AgentRepoPermDO> perms = new ArrayList<>();
        final InMemoryObjectStorage storage = new InMemoryObjectStorage();
        final PrdAcceptanceService acceptanceService;
        final PrdDeliveryFreezeService service;

        Fixture() {
            AgentRepoPermDao permDao = new StubPermDao();
            acceptanceService = new PrdAcceptanceService(acceptances, bindings, versions,
                    new PrdAcceptanceTestSupport.InMemoryArtifactDao(), storage);
            service = new PrdDeliveryFreezeService(designs, versions, bindings, profiles, permDao,
                    storage, acceptanceService);
            perms.add(perm(REPO));
            profiles.rows.put(PROFILE, profile());
        }

        AgentRepoPermDO perm(long repoId) {
            AgentRepoPermDO perm = new AgentRepoPermDO();
            perm.setTenantId(WS);
            perm.setAgentVersionId(AGENT_VERSION);
            perm.setRepoId(repoId);
            perm.setPermLevel("WRITE");
            return perm;
        }

        FrontendProjectProfileDO profile() {
            FrontendProjectProfileDO profile = new FrontendProjectProfileDO();
            profile.setId(PROFILE);
            profile.setWorkspaceId(WS);
            profile.setRepoId(REPO);
            profile.setAppRoot("frontend");
            profile.setBranch("main");
            profile.setCommitSha("c".repeat(40));
            profile.setDetectorVersion("frontend-project-detector-v1");
            profile.setProfileJson("{\"stack\":{\"frameworks\":[\"React\"]}}");
            profile.setEvidenceJson("{\"appEvidence\":[]}");
            return profile;
        }

        void withoutDesign() {
            designs.rows.clear();
        }

        void enablePrdDriven() {
            designs.only().setDeliveryMode(DeliveryMode.PRD_DRIVEN);
        }

        /** Store a confirmed version and return its id. */
        long confirm(String deliveryMode) {
            return confirmVersion(deliveryMode, design(), null);
        }

        /** Confirm a second version derived from the first; returns its id. */
        long confirmNewerVersion() {
            PrdDesignData data = design();
            data.setTitle("工单批量导入（第二版）");
            return confirmVersion(DeliveryMode.PRD_DRIVEN, data, designs.only().getConfirmedVersionId());
        }

        private long confirmVersion(String deliveryMode, PrdDesignData data, Long sourceVersionId) {
            PrdDesignDO design = designs.only();
            if (data.getProjectSnapshot() == null) data.setProjectSnapshot(PrdProjectSnapshot.from(profile()));
            String designJson = PrdDesignCodec.serialize(data);
            String html = PrdHtmlRenderer.render(data);
            int versionNo = design.getLatestVersionNo() + 1;
            // Content-addressed key like the real service, so a second confirmed version never
            // overwrites the first version's stored HTML.
            String ref = storage.put("prd-test-bucket",
                    "t/" + WS + "/workitem/prd/" + design.getId() + "/html/"
                            + MessageDigestUtil.sha256Hex(html) + ".html",
                    html.getBytes(StandardCharsets.UTF_8)).getOssRef();

            PrdDesignVersionDO version = new PrdDesignVersionDO();
            version.setWorkspaceId(WS);
            version.setDesignId(design.getId());
            version.setVersionNo(versionNo);
            version.setStatus(PrdVersionStatus.CONFIRMED);
            version.setSourceVersionId(sourceVersionId);
            version.setDesignJson(designJson);
            version.setDesignSha256(PrdDesignCodec.digest(designJson));
            version.setConfirmedSha256(version.getDesignSha256());
            version.setHtmlOssRef(ref);
            version.setHtmlSha256(MessageDigestUtil.sha256Hex(html));
            version.setRendererVersion(PrdHtmlRenderer.RENDERER_VERSION);
            version.setIsDeleted(0);
            version.setVersion(0);
            versions.insert(version);

            design.setConfirmedVersionId(version.getId());
            design.setLatestVersionNo(version.getVersionNo());
            design.setDeliveryMode(deliveryMode);
            design.setTargetRepoId(REPO);
            design.setTargetProfileId(PROFILE);
            design.setTargetAppRoot("frontend");
            design.setTargetCommitSha("c".repeat(40));
            return version.getId();
        }

        private final class StubPermDao implements AgentRepoPermDao {
            @Override public void insert(AgentRepoPermDO perm) { perms.add(perm); }
            @Override public int update(AgentRepoPermDO perm) { return 1; }
            @Override public List<AgentRepoPermDO> listByVersion(Long agentVersionId) {
                return perms.stream().filter(p -> p.getAgentVersionId().equals(agentVersionId)).toList();
            }
            @Override public AgentRepoPermDO findByVersionAndRepoForUpdate(Long agentVersionId, Long repoId,
                                                                          Long tenantId) { return null; }
            @Override public int deleteByVersionAndRepo(Long agentVersionId, Long repoId, Long tenantId) { return 0; }
            @Override public int deleteByVersion(Long agentVersionId) { return 0; }
            @Override public List<com.aliyun.autowonder.agent.AgentRepoRefDO> listActiveRefsByRepoId(
                    Long repoId, Long tenantId) { return List.of(); }
        }
    }

    private static final class InMemoryDesignDao implements PrdDesignDao {
        final Map<Long, PrdDesignDO> rows = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(1000);

        InMemoryDesignDao() {
            PrdDesignDO design = new PrdDesignDO();
            design.setWorkspaceId(WS);
            design.setWorkitemId(WORKITEM);
            design.setDeliveryMode(DeliveryMode.LEGACY);
            design.setLatestVersionNo(0);
            design.setVersion(0);
            insert(design);
        }

        PrdDesignDO only() {
            return rows.values().iterator().next();
        }

        @Override public void insert(PrdDesignDO design) {
            design.setId(ids.incrementAndGet());
            design.setVersion(0);
            rows.put(design.getId(), design);
        }

        @Override public PrdDesignDO findById(Long id, Long workspaceId) {
            PrdDesignDO design = rows.get(id);
            return design != null && design.getWorkspaceId().equals(workspaceId) ? design : null;
        }

        @Override public PrdDesignDO findByWorkitem(Long workspaceId, Long workitemId) {
            return rows.values().stream()
                    .filter(d -> d.getWorkspaceId().equals(workspaceId) && d.getWorkitemId().equals(workitemId))
                    .findFirst().orElse(null);
        }

        @Override public int updateTarget(Long id, Long workspaceId, Long targetRepoId, Long targetProfileId,
                                          String targetAppRoot, String targetCommitSha,
                                          Integer expectedVersion, Long modifierId) { return 1; }

        @Override public int updateManualTarget(Long id, Long workspaceId, Long repoId, String appRoot,
                                                String configJson, Integer expectedVersion, Long modifierId) { return 1; }

        @Override public int updateDeliveryMode(Long id, Long workspaceId, String deliveryMode,
                                                Integer expectedVersion, Long modifierId) { return 1; }

        @Override public int updateContentLanguage(Long id, Long workspaceId, String contentLanguage,
                                                   Integer expectedVersion, Long modifierId) { return 1; }

        @Override public int incrementLatestVersionNo(Long id, Long workspaceId, Integer expectedVersion) { return 1; }

        @Override public int confirmVersion(Long id, Long workspaceId, Long confirmedVersionId,
                                            Integer expectedVersion, Long modifierId) { return 1; }
    }

    private static final class InMemoryVersionDao implements PrdDesignVersionDao {
        final Map<Long, PrdDesignVersionDO> rows = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(5000);

        @Override public void insert(PrdDesignVersionDO version) {
            version.setId(ids.incrementAndGet());
            version.setVersion(0);
            version.setGmtCreate(new Date());
            rows.put(version.getId(), version);
        }

        @Override public PrdDesignVersionDO findById(Long id, Long workspaceId) {
            PrdDesignVersionDO version = id == null ? null : rows.get(id);
            if (version == null || !version.getWorkspaceId().equals(workspaceId)
                    || Integer.valueOf(1).equals(version.getIsDeleted())) {
                return null;
            }
            return version;
        }

        @Override public PrdDesignVersionDO findByVersionNo(Long workspaceId, Long designId, Integer versionNo) {
            return rows.values().stream()
                    .filter(v -> v.getVersionNo().equals(versionNo)).findFirst().orElse(null);
        }

        @Override public List<PrdDesignVersionDO> listByDesign(Long workspaceId, Long designId) {
            return new ArrayList<>(rows.values());
        }

        @Override public PrdDesignVersionDO findLatestByDesign(Long workspaceId, Long designId) {
            return rows.values().stream()
                    .max((a, b) -> a.getVersionNo().compareTo(b.getVersionNo())).orElse(null);
        }

        @Override public PrdDesignVersionDO findByAiSession(Long workspaceId, Long aiSessionId) { return null; }

        @Override public int updateDraft(PrdDesignVersionDO patch, Integer expectedVersion, Long modifierId) {
            return 1;
        }

        @Override public int attachHtml(Long id, Long workspaceId, String htmlOssRef,
                                        String htmlSha256, Long htmlSize) { return 1; }

        @Override public int submitForReview(Long id, Long workspaceId, Integer expectedVersion, Long modifierId) {
            return 1;
        }

        @Override public int confirm(Long id, Long workspaceId, String confirmedSha256,
                                     Long confirmedBy, Integer expectedVersion) {
            PrdDesignVersionDO version = rows.get(id);
            if (version == null || PrdVersionStatus.CONFIRMED.equals(version.getStatus())) {
                return 0;
            }
            version.setStatus(PrdVersionStatus.CONFIRMED);
            version.setConfirmedSha256(confirmedSha256);
            return 1;
        }

        @Override public int softDelete(Long id, Long workspaceId, Integer expectedVersion, Long modifierId) {
            return 1;
        }
    }

    private static final class InMemoryBindingDao implements PrdDeliveryBindingDao {
        final Map<Long, PrdDeliveryBindingDO> rows = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(9000);

        boolean isEmpty() {
            return rows.isEmpty();
        }

        int size() {
            return rows.size();
        }

        @Override public int insertIgnore(PrdDeliveryBindingDO binding) {
            boolean exists = rows.values().stream().anyMatch(b ->
                    b.getWorkspaceId().equals(binding.getWorkspaceId())
                            && b.getDispatchId().equals(binding.getDispatchId()));
            if (exists) {
                return 0;
            }
            binding.setId(ids.incrementAndGet());
            binding.setVersion(0);
            rows.put(binding.getId(), binding);
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

    private static final class InMemoryProfileDao implements FrontendProjectProfileDao {
        final Map<Long, FrontendProjectProfileDO> rows = new LinkedHashMap<>();

        @Override public int insertIgnore(FrontendProjectProfileDO profile) {
            rows.put(profile.getId(), profile);
            return 1;
        }

        @Override public FrontendProjectProfileDO findById(Long id, Long workspaceId) {
            FrontendProjectProfileDO profile = id == null ? null : rows.get(id);
            return profile != null && profile.getWorkspaceId().equals(workspaceId) ? profile : null;
        }

        @Override public FrontendProjectProfileDO findByCacheKey(Long workspaceId, Long repoId, String commitSha,
                                                                 String appRootHash, String detectorVersion) {
            return null;
        }

        @Override public List<FrontendProjectProfileDO> listByRepo(Long workspaceId, Long repoId, int limit) {
            return List.of();
        }

        @Override public List<FrontendProjectProfileDO> listByProbe(Long workspaceId, Long probeId) {
            return List.of();
        }

        @Override public List<FrontendProjectProfileDO> listByCommit(Long workspaceId, Long repoId,
                                                                     String commitSha, String detectorVersion) {
            return List.of();
        }

        @Override public int deleteByRepo(Long workspaceId, Long repoId) {
            return 0;
        }
    }
}
