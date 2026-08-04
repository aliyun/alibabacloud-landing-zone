package com.aliyun.autowonder.taskpackage;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.StoredObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskPackagerManifestTest {

    @Test
    void writesV1ManifestWithFileDigests() throws Exception {
        ObjectStorage storage = mock(ObjectStorage.class);
        final byte[][] captured = new byte[1][];
        when(storage.put(anyString(), anyString(), any(byte[].class))).thenAnswer(inv -> {
            captured[0] = inv.getArgument(2);
            return new StoredObject("oss://bucket/key.zip", "md5abc", captured[0].length);
        });
        when(storage.presignGet(anyString(), anyInt())).thenReturn("https://oss/dl");

        PackageContext ctx = new PackageContext();
        ctx.setTenantId(100L);
        ctx.setWorkitemId(500L);
        ctx.setDispatchId(300L);
        ctx.setAttempt(1);
        ctx.setWorkType("BUGFIX");
		ctx.setTaskPatternKey("coding:monorepo:checkout");
		ctx.setSessionRole("CANONICAL_SDLC");
		ctx.setTrialId("91");
		ctx.setTrialArm("CANDIDATE");
        ctx.setSdlcId(600L);
        ctx.setSdlcStepId(700L);
        ctx.setAgentId(200L);
        ctx.setAgentVersionId(201L);
        ctx.setExecutorId(900L);
        ctx.setRoleCode("backend_fixer");
        ctx.setRoleName("Backend Fixer");
        ctx.setIdempotencyKey("100:500:1");
        ctx.setWorkitemTitle("t");
        ctx.setWorkitemContentMd("body");

        java.util.Map<String, Object> roster = new java.util.LinkedHashMap<>();
        roster.put("digitalTeammates", java.util.List.of(java.util.Map.of("agentId", 202L, "roleCode", "reviewer")));
        roster.put("humanTeammates", java.util.List.of());
        ctx.setRoster(roster);

        TaskPackager packager = new TaskPackager(storage, "task-bucket", "https://daily.auto-wonder.example.com");
        TaskPackageResult r = packager.build(ctx);
        assertNotNull(r.getSha256());
        assertEquals(64, r.getSha256().length()); // hex sha256

        JSONObject manifest = readEntry(captured[0], "manifest.json");
        assertEquals("autoWonder.taskPackage.v1", manifest.getString("schemaVersion"));
        assertEquals("100", manifest.getString("tenantId"));
        assertEquals("300", manifest.getString("dispatchId"));
        assertEquals(1, manifest.getIntValue("attempt"));
        assertEquals("backend_fixer", manifest.getString("roleCode"));
		assertEquals("coding:monorepo:checkout", manifest.getString("taskPatternKey"));
		assertEquals("CANONICAL_SDLC", manifest.getString("sessionRole"));
		assertEquals("91", manifest.getString("trialId"));
		assertEquals("CANDIDATE", manifest.getString("trialArm"));
        JSONObject digests = manifest.getJSONObject("fileDigests");
        assertNotNull(digests);
        assertTrue(digests.getString("workitem.md").startsWith("sha256:"));

        JSONObject rosterJson = readEntry(captured[0], "roster.json");
        assertNotNull(rosterJson);
        assertEquals("reviewer", rosterJson.getJSONArray("digitalTeammates").getJSONObject(0).getString("roleCode"));
    }

    @Test
    void writesConfiguredAutowonderMcpEndpoint() throws Exception {
        ObjectStorage storage = mock(ObjectStorage.class);
        final byte[][] captured = new byte[1][];
        when(storage.put(anyString(), anyString(), any(byte[].class))).thenAnswer(inv -> {
            captured[0] = inv.getArgument(2);
            return new StoredObject("oss://bucket/key.zip", "md5abc", captured[0].length);
        });
        when(storage.presignGet(anyString(), anyInt())).thenReturn("https://oss/dl");

        PackageContext ctx = new PackageContext();
        ctx.setTenantId(100L);
        ctx.setWorkitemId(500L);
        ctx.setDispatchId(300L);
        ctx.setWorkitemTitle("t");
        ctx.setWorkitemContentMd("body");

        new TaskPackager(storage, "task-bucket", () -> "https://private.example.com/api/mcp/").build(ctx);

        JSONObject capabilities = readEntry(captured[0], "skills.json");
        JSONObject autowonder = capabilities.getJSONArray("mcpServers").getJSONObject(0);
        assertEquals("autowonder", autowonder.getString("name"));
        assertEquals("https://private.example.com/api/mcp", autowonder.getString("url"));
    }

    private JSONObject readEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return JSON.parseObject(new String(zis.readAllBytes()));
                }
            }
        }
        return null;
    }
}
