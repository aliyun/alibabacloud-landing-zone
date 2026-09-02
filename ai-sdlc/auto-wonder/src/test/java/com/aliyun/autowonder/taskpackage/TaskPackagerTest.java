package com.aliyun.autowonder.taskpackage;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class TaskPackagerTest {

    InMemoryObjectStorage storage;
    TaskPackager packager;

    @BeforeEach
    void setUp() {
        storage = new InMemoryObjectStorage();
        packager = new TaskPackager(storage, "autowonder-task-pkg-daily", "https://daily.auto-wonder.example.com");
    }

    private PackageContext baseCtx() {
        PackageContext c = new PackageContext();
        c.setTenantId(100L);
        c.setDispatchId(9001L);
        c.setWorkitemId(3L);
        c.setAgentId(5L);
        c.setSdlcStepId(2L);
        c.setWorkitemTitle("Build feature X");
        c.setWorkitemContentMd("# feature X\ndetails");
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("name", "Coder");
        identity.put("roleCode", "coding");
        c.setIdentity(identity);
        Map<String, Object> sdlc = new LinkedHashMap<>();
        sdlc.put("onSuccess", "{\"action\":\"NEXT_STEP\"}");
        c.setSdlc(sdlc);
        return c;
    }

    private Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int n;
                while ((n = zis.read(buf)) != -1) bos.write(buf, 0, n);
                out.put(e.getName(), bos.toByteArray());
            }
        }
        return out;
    }

    @Test
    void builds_core_entries_and_uploads() throws Exception {
        PackageContext c = baseCtx();
        c.setClarificationMd("clarified scope");
        c.setSourceDispatchId(8000L);

        TaskPackageResult r = packager.build(c);

        assertEquals("autowonder-task-pkg-daily/100/3/9001.zip", r.getOssRef());
        assertTrue(r.getSize() > 0);
        assertNotNull(r.getMd5());
        assertTrue(r.getDownloadUrl().contains("9001.zip"));
        assertNull(r.getIssuer());
        assertNull(r.getSignatureRef());
        assertNull(r.getSignature());
        assertNull(r.getSignatureAlgorithm());
        assertNull(r.getSignaturePublicKey());
        assertNull(r.getExpiresAt());

        Map<String, byte[]> entries = unzip(storage.get(r.getOssRef()));
        assertTrue(entries.containsKey("manifest.json"));
        assertTrue(entries.containsKey("workitem.md"));
        assertTrue(entries.containsKey("clarification.md"));
        assertTrue(entries.containsKey("identity.json"));
        assertTrue(entries.containsKey("sdlc.json"));
        assertEquals("clarified scope",
                new String(entries.get("clarification.md"), StandardCharsets.UTF_8));

        JSONObject manifest = JSON.parseObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        assertEquals("autoWonder.taskPackage.v1", manifest.getString("schemaVersion"));
        assertEquals("9001", manifest.getString("dispatchId"));
        assertEquals("3", manifest.getString("workitemId"));
        assertEquals("8000", manifest.getString("sourceDispatchId"));
        assertFalse(manifest.containsKey("issuer"));
        assertFalse(manifest.containsKey("signatureRef"));
        assertFalse(manifest.containsKey("expiresAt"));
    }

    @Test
    void omits_clarification_when_absent() throws Exception {
        TaskPackageResult r = packager.build(baseCtx());
        Map<String, byte[]> entries = unzip(storage.get(r.getOssRef()));
        assertFalse(entries.containsKey("clarification.md"));
    }

    @Test
    void legacyNullSdlcStillWritesEmptySdlcFile() throws Exception {
        PackageContext context = baseCtx();
        context.setSdlc(null);
        context.setSdlcId(null);
        context.setSdlcStepId(null);

        TaskPackageResult result = packager.build(context);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        assertEquals("{}", new String(entries.get("sdlc.json"), StandardCharsets.UTF_8));
    }

    @Test
    void explicitlyMarkedScheduledRootOmitsSdlcFile() throws Exception {
        PackageContext context = baseCtx();
        context.setSdlc(null);
        context.setSdlcId(null);
        context.setSdlcStepId(null);
        context.setOmitSdlcFileWhenAbsent(true);

        TaskPackageResult result = packager.build(context);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        assertFalse(entries.containsKey("sdlc.json"));
    }

    @Test
    void interactionOnlySdlcStillWritesSdlcFile() throws Exception {
        PackageContext context = baseCtx();
        context.setSdlc(Map.of("workflow", "interaction-only", "currentStepId", "interaction"));
        context.setOmitSdlcFileWhenAbsent(false);

        TaskPackageResult result = packager.build(context);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        assertTrue(entries.containsKey("sdlc.json"));
    }

    @Test
    void scheduledRootContextKeepsLegacyManifestAndFileNames() throws Exception {
        PackageContext context = baseCtx();
        context.setWorkitemId(50001L);
        context.setWorkType("TASK");
        context.setWorkitemTitle("夜间全量回归");
        context.setWorkitemContentMd("执行全量回归并分析失败原因");
        context.setSdlc(null);
        context.setSdlcId(null);
        context.setSdlcStepId(null);
        context.setOmitSdlcFileWhenAbsent(true);

        TaskPackageResult result = packager.build(context);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));
        JSONObject manifest = JSON.parseObject(
                new String(entries.get("manifest.json"), StandardCharsets.UTF_8));

        assertTrue(entries.containsKey("workitem.md"));
        assertFalse(entries.containsKey("sdlc.json"));
        assertEquals("50001", manifest.getString("workitemId"));
        assertEquals("TASK", manifest.getString("workType"));
        assertFalse(manifest.containsKey("sourceType"));
    }

    @Test
    void writesRepoMapWhenWorkerHasBoundRepositoryContext() throws Exception {
        PackageContext context = baseCtx();
        context.setRepoMap(Map.of(
                "boundRepoIds", List.of(10L),
                "relations", List.of(Map.of(
                        "id", 91L,
                        "fromRepoId", 10L,
                        "toRepoId", 11L,
                        "relationType", "DEPENDS_ON"))));

        TaskPackageResult result = packager.build(context);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        JSONObject repoMap = JSON.parseObject(new String(entries.get("repo-map.json"), StandardCharsets.UTF_8));
        assertEquals(10L, repoMap.getJSONArray("boundRepoIds").getLongValue(0));
        assertEquals("DEPENDS_ON", repoMap.getJSONArray("relations").getJSONObject(0).getString("relationType"));
    }

    @Test
    void doesNotWriteLegacyTriggerCommentFile() throws Exception {
        PackageContext context = baseCtx();

        TaskPackageResult result = packager.build(context);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        assertFalse(entries.containsKey("trigger-comment.md"));
    }

    @Test
    void writesSideInteractionContextOnlyWhenProvided() throws Exception {
        PackageContext context = baseCtx();
        context.setInteractionContextMd("# Side Interaction Conversation\n\nclarified requirement");

        TaskPackageResult result = packager.build(context);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        assertEquals("# Side Interaction Conversation\n\nclarified requirement",
                new String(entries.get("interaction-context.md"), StandardCharsets.UTF_8));
        JSONObject manifest = JSON.parseObject(
                new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        assertTrue(manifest.getJSONObject("fileDigests").containsKey("interaction-context.md"));
    }

    @Test
    void writes_memory_files_by_type() throws Exception {
        PackageContext c = baseCtx();
        Map<String, String> mem = new LinkedHashMap<>();
        mem.put("lessons", "always test");
        mem.put("style", "concise");
        c.setMemory(mem);

        TaskPackageResult r = packager.build(c);
        Map<String, byte[]> entries = unzip(storage.get(r.getOssRef()));
        assertEquals("always test",
                new String(entries.get("memory/lessons.md"), StandardCharsets.UTF_8));
        assertEquals("concise",
                new String(entries.get("memory/style.md"), StandardCharsets.UTF_8));
    }

    @Test
    void teammate_outputs_organized_by_role_with_artifacts() throws Exception {
        storage.put("autowonder-artifacts-daily", "3/arch.md", "architecture".getBytes(StandardCharsets.UTF_8));

        PackageContext c = baseCtx();
        TeammateOutput t = new TeammateOutput();
        t.setRoleName("architect");
        t.setAgentId("4");
        t.setDispatchId("8000");
        t.setConclusionMd("use hexagonal");
        TaskArtifactRef ref = new TaskArtifactRef();
        ref.setName("arch.md");
        ref.setOssRef("autowonder-artifacts-daily/3/arch.md");
        t.setArtifacts(List.of(ref));
        c.setTeammates(List.of(t));

        TaskPackageResult r = packager.build(c);
        Map<String, byte[]> entries = unzip(storage.get(r.getOssRef()));
        assertEquals("use hexagonal",
                new String(entries.get("teammates/architect/conclusion.md"), StandardCharsets.UTF_8));
        assertEquals("architecture",
                new String(entries.get("teammates/architect/artifacts/arch.md"), StandardCharsets.UTF_8));

        JSONObject manifest = JSON.parseObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        assertEquals(1, manifest.getJSONArray("teammates").size());
    }

    @Test
    void requirementDocumentsAreIncludedInZipAndManifest() throws Exception {
        var stored = storage.put("autowonder-artifacts-daily", "t/100/workitem/3/requirements/spec.md",
                "# Spec".getBytes(StandardCharsets.UTF_8));
        TaskArtifactRef ref = new TaskArtifactRef();
        ref.setName("requirements/spec.md");
        ref.setOssRef(stored.getOssRef());
        PackageContext c = baseCtx();
        c.setRequirementDocuments(List.of(ref));

        TaskPackageResult result = packager.build(c);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        assertEquals("# Spec", new String(entries.get("requirements/spec.md"), StandardCharsets.UTF_8));
        JSONObject manifest = JSON.parseObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        JSONObject document = manifest.getJSONArray("requirementDocuments").getJSONObject(0);
        assertEquals("requirements/spec.md", document.getString("name"));
        assertEquals(6, document.getIntValue("size"));
        assertTrue(document.getString("sha256").startsWith("sha256:"));
        assertTrue(manifest.getJSONObject("fileDigests").containsKey("requirements/spec.md"));
    }

    @Test
    void missingRequirementDocumentFailsPackageBuild() {
        TaskArtifactRef ref = new TaskArtifactRef();
        ref.setName("requirements/missing.md");
        ref.setOssRef("autowonder-artifacts-daily/missing.md");
        PackageContext c = baseCtx();
        c.setRequirementDocuments(List.of(ref));

        BizException ex = assertThrows(BizException.class, () -> packager.build(c));

        assertEquals("17020", ex.getCode());
    }

    @Test
    void frozenRequirementHashMismatchFailsPackageBuild() {
        var stored = storage.put("autowonder-artifacts-daily", "frozen/spec.md",
                "changed".getBytes(StandardCharsets.UTF_8));
        TaskArtifactRef ref = new TaskArtifactRef();
        ref.setName("spec.md");
        ref.setOssRef(stored.getOssRef());
        ref.setExpectedSha256("sha256:" + "a".repeat(64));
        PackageContext context = baseCtx();
        context.setRequirementDocuments(List.of(ref));

        BizException ex = assertThrows(BizException.class, () -> packager.build(context));

        assertEquals("17020", ex.getCode());
    }

    @Test
    void visualRequirementDocumentBytesArePackagedWithoutManifestChange() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        var stored = storage.put("autowonder-artifacts-daily", "t/100/workitem/3/requirements/screen.png", png);
        TaskArtifactRef ref = new TaskArtifactRef();
        ref.setName("requirements/screen.png");
        ref.setOssRef(stored.getOssRef());
        PackageContext c = baseCtx();
        c.setRequirementDocuments(List.of(ref));

        TaskPackageResult result = packager.build(c);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        assertArrayEquals(png, entries.get("requirements/screen.png"));
        JSONObject manifest = JSON.parseObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        JSONObject document = manifest.getJSONArray("requirementDocuments").getJSONObject(0);
        assertEquals("requirements/screen.png", document.getString("name"));
        assertFalse(document.containsKey("contentType"));
    }

    @Test
    void duplicate_role_names_get_suffixed() throws Exception {
        PackageContext c = baseCtx();
        TeammateOutput a = new TeammateOutput();
        a.setRoleName("coder"); a.setDispatchId("111111"); a.setConclusionMd("v1");
        TeammateOutput b = new TeammateOutput();
        b.setRoleName("coder"); b.setDispatchId("222222"); b.setConclusionMd("v2");
        c.setTeammates(List.of(a, b));

        TaskPackageResult r = packager.build(c);
        Map<String, byte[]> entries = unzip(storage.get(r.getOssRef()));
        assertTrue(entries.containsKey("teammates/coder/conclusion.md"));
        assertTrue(entries.containsKey("teammates/coder__222222/conclusion.md"));
    }

    @Test
    void direct_predecessor_revision_continues_checkout_and_delivery_branch() throws Exception {
        String baseCommit = "1111111111111111111111111111111111111111";
        String headCommit = "2222222222222222222222222222222222222222";
        String deliveryBranch = "aw/浏览器-favicon-20260715-143025-123";
        String revision = """
                {
                  "dispatchId": "8000",
                  "repositories": [{
                    "name": "service",
                    "branch": "%s",
                    "baseCommit": "%s",
                    "headCommit": "%s"
                  }]
                }
                """.formatted(deliveryBranch, baseCommit, headCommit);
        var stored = storage.put("autowonder-artifacts-daily", "3/runtime-source-revision.json",
                revision.getBytes(StandardCharsets.UTF_8));

        PackageContext c = baseCtx();
        c.setRepos(List.of(new LinkedHashMap<>(Map.of(
                "name", "service",
                "url", "git@example/service.git",
                "ref", "master",
                "mode", "eager"))));
        TeammateOutput predecessor = new TeammateOutput();
        predecessor.setDispatchId("8000");
        TaskArtifactRef artifact = new TaskArtifactRef();
        artifact.setName("artifacts/output/deliverables/runtime-source-revision.json");
        artifact.setOssRef(stored.getOssRef());
        predecessor.setArtifacts(List.of(artifact));
        c.setTeammates(List.of(predecessor));

        TaskPackageResult result = packager.build(c);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));
        JSONObject repos = JSON.parseObject(new String(entries.get("repos.json"), StandardCharsets.UTF_8));
        JSONObject repo = repos.getJSONArray("repos").getJSONObject(0);

        assertEquals(headCommit, repo.getString("ref"));
        assertEquals(deliveryBranch, repo.getString("deliveryBranch"));
        assertFalse(repo.containsKey("worktreeBranch"));
        assertEquals(baseCommit, repo.getString("deliveryBaseCommit"));
    }

    @Test
    void checkpointFallbackContinuesCheckoutWithoutInventingDeliveryLineage() throws Exception {
        String checkoutCommit = "2222222222222222222222222222222222222222";
        String previousBase = "1111111111111111111111111111111111111111";
        String previousHead = "3333333333333333333333333333333333333333";
        String deliveryBranch = "fix/checkpoint-rework";
        var stored = storage.put("autowonder-artifacts-daily", "3/checkpoint-revision.json", ("""
                {"schemaVersion":"autowonder.checkpointSourceRevision.v1","repositories":[{"name":"service","branch":"%s","headCommit":"%s"}]}
                """).formatted(deliveryBranch, checkoutCommit).getBytes(StandardCharsets.UTF_8));
        var previous = storage.put("autowonder-artifacts-daily", "3/previous-revision.json", ("""
                {"repositories":[{"name":"service","branch":"%s","baseCommit":"%s","headCommit":"%s"}]}
                """).formatted(deliveryBranch, previousBase, previousHead).getBytes(StandardCharsets.UTF_8));

        PackageContext c = baseCtx();
        c.setRepos(List.of(new LinkedHashMap<>(Map.of(
                "name", "service", "url", "git@example/service.git", "ref", "master", "mode", "eager"))));
        c.setSourceRevisionArtifacts(List.of(
                artifactRef("checkpoint", stored.getOssRef()),
                artifactRef("previous", previous.getOssRef())));

        TaskPackageResult result = packager.build(c);
        JSONObject repos = JSON.parseObject(new String(
                unzip(storage.get(result.getOssRef())).get("repos.json"), StandardCharsets.UTF_8));
        JSONObject repo = repos.getJSONArray("repos").getJSONObject(0);

        assertEquals(checkoutCommit, repo.getString("ref"));
        assertEquals(deliveryBranch, repo.getString("deliveryBranch"));
        assertFalse(repo.containsKey("deliveryBaseCommit"),
                "checkpoint checkout base is not an authoritative direct-predecessor revision");
    }

    @Test
    void nearestRevisionKeepsHeadAndInheritsMissingDeliveryBranch() throws Exception {
        String baseCommit = "1111111111111111111111111111111111111111";
        String currentHead = "3333333333333333333333333333333333333333";
        String previousHead = "2222222222222222222222222222222222222222";
        String deliveryBranch = "aw/reliable-rework";
        var current = storage.put("autowonder-artifacts-daily", "3/current-revision.json", ("""
                {"repositories":[{"name":"service","baseCommit":"%s","headCommit":"%s"}]}
                """).formatted(baseCommit, currentHead).getBytes(StandardCharsets.UTF_8));
        var previous = storage.put("autowonder-artifacts-daily", "3/previous-revision.json", ("""
                {"repositories":[{"name":"service","branch":"%s","baseCommit":"%s","headCommit":"%s"}]}
                """).formatted(deliveryBranch, baseCommit, previousHead).getBytes(StandardCharsets.UTF_8));

        PackageContext c = baseCtx();
        c.setRepos(List.of(new LinkedHashMap<>(Map.of(
                "name", "service", "url", "git@example/service.git", "ref", "master", "mode", "eager"))));
        c.setSourceRevisionArtifacts(List.of(
                artifactRef("current", current.getOssRef()),
                artifactRef("previous", previous.getOssRef())));

        TaskPackageResult result = packager.build(c);
        JSONObject repos = JSON.parseObject(new String(
                unzip(storage.get(result.getOssRef())).get("repos.json"), StandardCharsets.UTF_8));
        JSONObject repo = repos.getJSONArray("repos").getJSONObject(0);

        assertEquals(currentHead, repo.getString("ref"));
        assertEquals(deliveryBranch, repo.getString("deliveryBranch"));
        assertEquals(baseCommit, repo.getString("deliveryBaseCommit"));
    }

    private TaskArtifactRef artifactRef(String name, String ossRef) {
        TaskArtifactRef ref = new TaskArtifactRef();
        ref.setName(name + "/deliverables/runtime-source-revision.json");
        ref.setOssRef(ossRef);
        return ref;
    }
}
