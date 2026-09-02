package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.SkillPackageInspectVO;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillPackageServiceTest {

    private SkillDao skillDao;
    private SkillPackageService service;
    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        skillDao = mock(SkillDao.class);
        storage = mock(ObjectStorage.class);
        SkillService skillService = new SkillService(skillDao, mock(AgentSkillDao.class), mock(UserDao.class));
        service = new SkillPackageService(skillDao, skillService, storage, "artifact-bucket");
    }

    @Test
    void inspectReadsRootSkillFrontmatter() throws Exception {
        MockMultipartFile file = skillZip("custom-skill", "Custom skill for AutoWonder");

        SkillPackageInspectVO vo = service.inspect(file);

        assertEquals("custom-skill", vo.getName());
        assertEquals("Custom skill for AutoWonder", vo.getDescription());
        assertEquals("custom-skill.zip", vo.getFileName());
        assertTrue(vo.getPackageSize() > 0);
    }

    @Test
    void inspectAcceptsTarGzSkillPackageWithDotPrefixedFiles() throws Exception {
        MockMultipartFile file = tarGz("custom-skill.tar.gz", Map.of(
                "SKILL.md", skillMd("custom-skill", "Custom skill for AutoWonder"),
                ".qoder/config.json", "{}",
                "references/readme.md", "reference"));

        SkillPackageInspectVO vo = service.inspect(file);

        assertEquals("custom-skill", vo.getName());
        assertEquals("custom-skill.tar.gz", vo.getFileName());
        assertTrue(vo.getPackageSize() > 0);
    }

    @Test
    void uploadMcpPackageRejectsDigestMismatch() throws Exception {
        MockMultipartFile file = skillZip("custom-skill", "Custom skill for AutoWonder");

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                "custom-skill.zip", file.getBytes(), "SKILL", null, null, null, "wrong-md5", 1L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void inspectRejectsZipWithoutRootSkillMd() throws Exception {
        MockMultipartFile file = zip("bad.zip", "nested/SKILL.md", skillMd("nested", "desc"));

        BizException ex = assertThrows(BizException.class, () -> service.inspect(file));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void createUploadedSkillStoresZipAndPersistsOssRef() throws Exception {
        MockMultipartFile file = skillZip("custom-skill", "Custom skill for AutoWonder");
        when(skillDao.findByTypeAndName(1L, "SKILL", "custom-skill")).thenReturn(null);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10000/skill.zip"), any()))
                .thenReturn(new StoredObject("artifact-bucket/t/1/skills/10000/skill.zip", "md5", file.getSize()));
        doAnswer(invocation -> {
            SkillDO skill = invocation.getArgument(0);
            skill.setId(10000L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(skillDao.updatePackage(eq(10000L), eq(1L), eq("SKILL"), anyString(), eq("custom-skill"), eq("Custom skill for AutoWonder"),
                eq("OSS_ZIP"), eq("artifact-bucket/t/1/skills/10000/skill.zip"), eq("custom-skill.zip"),
                eq(file.getSize()), eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(10000L);
        stored.setType("SKILL");
        stored.setName("custom-skill");
        stored.setDescription("Custom skill for AutoWonder");
        stored.setSourceType("OSS_ZIP");
        stored.setPackageOssRef("artifact-bucket/t/1/skills/10000/skill.zip");
        stored.setPackageFileName("custom-skill.zip");
        stored.setPackageSize(file.getSize());
        stored.setPackageMd5("md5");
        stored.setVersion(1);
        when(skillDao.findById(10000L)).thenReturn(stored);

        SkillVO vo = service.createFromPackage(file, 1L, 2L);

        assertEquals("custom-skill", vo.getName());
        assertEquals("OSS_ZIP", vo.getSourceType());
        assertEquals("artifact-bucket/t/1/skills/10000/skill.zip", vo.getPackageOssRef());
        ArgumentCaptor<SkillDO> captor = ArgumentCaptor.forClass(SkillDO.class);
        verify(skillDao).insert(captor.capture());
        assertEquals("SKILL", captor.getValue().getType());
        assertEquals("OSS_ZIP", captor.getValue().getSourceType());
    }

    @Test
    void createUploadedPluginStoresProviderCompatibility() throws Exception {
        MockMultipartFile file = zip("team-tools.zip", "plugin.json", "{\"name\":\"team-tools\"}");
        when(skillDao.findByTypeAndName(1L, "PLUGIN", "team-tools")).thenReturn(null);
        doAnswer(invocation -> { invocation.<SkillDO>getArgument(0).setId(10001L); return null; })
                .when(skillDao).insert(any(SkillDO.class));
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10001/skill.zip"), any()))
                .thenReturn(new StoredObject("artifact-bucket/t/1/skills/10001/skill.zip", "md5", file.getSize()));
        when(skillDao.updatePackage(eq(10001L), eq(1L), eq("PLUGIN"), contains("claude"), eq("team-tools"),
                eq("Team tools"), eq("OSS_ZIP"), anyString(), eq("team-tools.zip"), eq(file.getSize()),
                eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(10001L); stored.setTenantId(1L); stored.setType("PLUGIN"); stored.setName("team-tools");
        stored.setInstallSpec("{\"providers\":[\"claude\"]}");
        when(skillDao.findById(10001L)).thenReturn(stored);

        SkillVO vo = service.createFromPackage(file, "PLUGIN", "team-tools", "Team tools", List.of("claude"), 1L, 2L);

        assertEquals("PLUGIN", vo.getType());
        verify(skillDao).insert(argThat(row -> "PLUGIN".equals(row.getType()) && row.getInstallSpec().contains("claude")));
    }

    @Test
    void createUploadedHookUsesRootDescriptorIdentity() throws Exception {
        MockMultipartFile file = hookZip("sample-before-step", "beforeStep");
        when(skillDao.findByTypeAndName(1L, "HOOK", "sample-before-step")).thenReturn(null);
        doAnswer(invocation -> { invocation.<SkillDO>getArgument(0).setId(10002L); return null; })
                .when(skillDao).insert(any(SkillDO.class));
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10002/skill.zip"), any()))
                .thenReturn(new StoredObject("artifact-bucket/t/1/skills/10002/skill.zip", "md5", file.getSize()));
        when(skillDao.updatePackage(eq(10002L), eq(1L), eq("HOOK"), contains("OSS_ZIP"),
                eq("sample-before-step"), eq("Runtime lifecycle hook: beforeStep"), eq("OSS_ZIP"),
                anyString(), eq("sample-before-step.zip"), eq(file.getSize()), eq("md5"), eq(0), eq(2L)))
                .thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(10002L);
        stored.setTenantId(1L);
        stored.setType("HOOK");
        stored.setName("sample-before-step");
        stored.setDescription("Runtime lifecycle hook: beforeStep");
        stored.setSourceType("OSS_ZIP");
        when(skillDao.findById(10002L)).thenReturn(stored);

        SkillVO vo = service.createFromPackage(file, "HOOK", null, null, null, 1L, 2L);

        assertEquals("HOOK", vo.getType());
        assertEquals("sample-before-step", vo.getName());
        verify(skillDao).insert(argThat(row -> "HOOK".equals(row.getType())
                && "sample-before-step".equals(row.getName())));
    }

    @Test
    void uploadHookRejectsMissingRootDescriptor() throws Exception {
        MockMultipartFile file = zip("bad-hook.zip", "scripts/run.sh", "#!/bin/sh\n");

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                file.getOriginalFilename(), file.getBytes(), "HOOK", null, null, null, null, 1L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void uploadHookAcceptsToolLifecycleTriggers() throws Exception {
        for (String trigger : List.of("beforeTool", "afterTool")) {
            MockMultipartFile file = hookZip("sample-" + trigger, trigger);
            when(storage.put(anyString(), anyString(), any())).thenReturn(
                    new StoredObject("artifact-bucket/tool-hook.zip", "md5", file.getSize()));

            SkillPackageService.UploadedPackage uploaded = service.uploadMcpPackage(
                    file.getOriginalFilename(), file.getBytes(), "HOOK", null, null,
                    null, null, 1L);

            assertEquals("HOOK", uploaded.type());
            assertEquals("sample-" + trigger, uploaded.name());
        }
    }

    @Test
    void updateUploadedSkillOverwritesExistingOssKey() throws Exception {
        MockMultipartFile file = skillZip("custom-skill-v2", "Updated description");
        SkillDO existing = new SkillDO();
        existing.setId(10000L);
        existing.setTenantId(1L);
        existing.setType("SKILL");
        existing.setName("custom-skill");
        existing.setDescription("old");
        existing.setVersion(3);
        existing.setSourceType("OSS_ZIP");
        existing.setPackageOssRef("artifact-bucket/t/1/skills/10000/skill.zip");
        when(skillDao.findById(10000L)).thenReturn(existing);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10000/skill.zip"), any()))
                .thenReturn(new StoredObject("artifact-bucket/t/1/skills/10000/skill.zip", "md5-v2", file.getSize()));
        when(skillDao.updatePackage(eq(10000L), eq(1L), eq("SKILL"), anyString(), eq("custom-skill-v2"), eq("Updated description"),
                eq("OSS_ZIP"), eq("artifact-bucket/t/1/skills/10000/skill.zip"), eq("custom-skill-v2.zip"),
                eq(file.getSize()), eq("md5-v2"), eq(3), eq(2L))).thenReturn(1);
        SkillDO updated = new SkillDO();
        updated.setId(10000L);
        updated.setType("SKILL");
        updated.setName("custom-skill-v2");
        updated.setDescription("Updated description");
        updated.setVersion(4);
        updated.setSourceType("OSS_ZIP");
        updated.setPackageOssRef("artifact-bucket/t/1/skills/10000/skill.zip");
        when(skillDao.findById(10000L)).thenReturn(existing, updated);

        SkillVO vo = service.updatePackage(10000L, file, 1L, 2L);

        assertEquals("custom-skill-v2", vo.getName());
        assertEquals("OSS_ZIP", vo.getSourceType());
        verify(storage).put(eq("artifact-bucket"), eq("t/1/skills/10000/skill.zip"), any());
    }

    @Test
    void updateUploadedPackageWithSameIdempotencyKeyAndDigestDoesNotCreateDuplicateVersion() throws Exception {
        MockMultipartFile file = skillZip("custom-skill-v2", "Updated description");
        String packageMd5 = md5(file.getBytes());
        SkillDO existing = new SkillDO();
        existing.setId(10000L);
        existing.setTenantId(1L);
        existing.setType("SKILL");
        existing.setName("custom-skill");
        existing.setVersion(3);
        existing.setPackageMd5("old-md5");
        SkillDO updated = new SkillDO();
        updated.setId(10000L);
        updated.setTenantId(1L);
        updated.setType("SKILL");
        updated.setName("custom-skill-v2");
        updated.setDescription("Updated description");
        updated.setVersion(4);
        updated.setSourceType("OSS_ZIP");
        updated.setPackageMd5(packageMd5);
        updated.setPackageOssRef("artifact-bucket/t/1/skills/10000/skill.zip");
        updated.setPackageFileName("custom-skill-v2.zip");
        when(storage.get("artifact-bucket/uploads/custom-skill-v2.zip")).thenReturn(file.getBytes());
        when(skillDao.findById(10000L)).thenReturn(existing, updated, updated);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10000/skill.zip"), any()))
                .thenReturn(new StoredObject("artifact-bucket/t/1/skills/10000/skill.zip", packageMd5, file.getSize()));
        when(skillDao.updatePackage(eq(10000L), eq(1L), eq("SKILL"), anyString(), eq("custom-skill-v2"),
                eq("Updated description"), eq("OSS_ZIP"), eq("artifact-bucket/t/1/skills/10000/skill.zip"),
                eq("custom-skill-v2.zip"), eq(file.getSize()), eq(packageMd5), eq(3), eq(2L))).thenReturn(1);

        SkillVO first = service.updateUploadedPackage(10000L, "artifact-bucket/uploads/custom-skill-v2.zip",
                null, null, null, packageMd5, "idem-1", 1L, 2L);
        SkillVO second = service.updateUploadedPackage(10000L, "artifact-bucket/uploads/custom-skill-v2.zip",
                null, null, null, packageMd5, "idem-1", 1L, 2L);

        assertEquals("custom-skill-v2", first.getName());
        assertEquals("custom-skill-v2", second.getName());
        verify(storage, times(1)).put(eq("artifact-bucket"), eq("t/1/skills/10000/skill.zip"), any());
        verify(skillDao, times(1)).updatePackage(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test
    void inspectRejectsZipWithOversizedNonSkillMdEntry() throws Exception {
        MockMultipartFile file = skillZipWithOversizedAsset("custom-skill", "Custom skill for AutoWonder");

        BizException ex = assertThrows(BizException.class, () -> service.inspect(file));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void inspectAcceptsZipWithDirectoriesAssetsAndDotPrefixedFiles() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("SKILL.md", skillMd("custom-skill", "Custom skill for AutoWonder").getBytes(StandardCharsets.UTF_8));
        entries.put("references/readme.md", "reference".getBytes(StandardCharsets.UTF_8));
        entries.put("scripts/run.sh", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/logo.txt", "asset".getBytes(StandardCharsets.UTF_8));
        entries.put(".qoder/config.json", "{}".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = zip("custom-skill.zip", entries);

        SkillPackageInspectVO vo = service.inspect(file);

        assertEquals("custom-skill", vo.getName());
        assertEquals("custom-skill.zip", vo.getFileName());
    }

    @Test
    void updateUploadedSkillRejectsOtherTenantBeforeUploading() throws Exception {
        MockMultipartFile file = skillZip("custom-skill-v2", "Updated description");
        SkillDO existing = new SkillDO();
        existing.setId(10000L);
        existing.setTenantId(99L);
        existing.setVersion(3);
        when(skillDao.findById(10000L)).thenReturn(existing);

        BizException ex = assertThrows(BizException.class, () -> service.updatePackage(10000L, file, 1L, 2L));

        assertEquals(ErrorCode.SKILL_NOT_FOUND.getCode(), ex.getCode());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void updateUploadedSkillRejectsDuplicateNameBeforeUploading() throws Exception {
        MockMultipartFile file = skillZip("duplicate-skill", "Updated description");
        SkillDO existing = new SkillDO();
        existing.setId(10000L);
        existing.setTenantId(1L);
        existing.setVersion(3);
        SkillDO duplicate = new SkillDO();
        duplicate.setId(10001L);
        when(skillDao.findById(10000L)).thenReturn(existing);
        when(skillDao.findByTypeAndName(1L, "SKILL", "duplicate-skill")).thenReturn(duplicate);

        BizException ex = assertThrows(BizException.class, () -> service.updatePackage(10000L, file, 1L, 2L));

        assertEquals(ErrorCode.SKILL_DUPLICATE_NAME.getCode(), ex.getCode());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    private static MockMultipartFile skillZip(String name, String description) throws Exception {
        return zip(name + ".zip", "SKILL.md", skillMd(name, description));
    }

    private static MockMultipartFile hookZip(String name, String trigger) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("hook.yaml", ("schemaVersion: autowonder.hook.v1\n"
                + "name: " + name + "\n"
                + "version: 1\n"
                + "trigger: " + trigger + "\n"
                + "interpreter: bash\n"
                + "command: scripts/run.sh\n").getBytes(StandardCharsets.UTF_8));
        entries.put("scripts/run.sh", "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        return zip(name + ".zip", entries);
    }

    private static MockMultipartFile zip(String fileName, String entryName, String content) throws Exception {
        return zip(fileName, Map.of(entryName, content.getBytes(StandardCharsets.UTF_8)));
    }

    private static MockMultipartFile zip(String fileName, Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return new MockMultipartFile("file", fileName, "application/zip", baos.toByteArray());
    }

    private static MockMultipartFile skillZipWithOversizedAsset(String name, String description) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            zos.write(skillMd(name, description).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("assets/large.bin"));
            byte[] chunk = new byte[8192];
            long remaining = SkillPackageService.MAX_PACKAGE_SIZE + 1L;
            while (remaining > 0) {
                int size = (int) Math.min(chunk.length, remaining);
                zos.write(chunk, 0, size);
                remaining -= size;
            }
            zos.closeEntry();
        }
        return new MockMultipartFile("file", name + ".zip", "application/zip", baos.toByteArray());
    }

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    private static MockMultipartFile tarGz(String fileName, Map<String, String> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
                byte[] header = new byte[512];
                writeTarString(header, 0, 100, entry.getKey());
                writeTarString(header, 100, 8, "0000777");
                writeTarString(header, 108, 8, "0000000");
                writeTarString(header, 116, 8, "0000000");
                writeTarString(header, 124, 12, String.format("%011o", content.length));
                writeTarString(header, 136, 12, "00000000000");
                for (int i = 148; i < 156; i++) {
                    header[i] = ' ';
                }
                header[156] = '0';
                writeTarString(header, 257, 6, "ustar");
                int checksum = 0;
                for (byte value : header) {
                    checksum += value & 0xff;
                }
                writeTarString(header, 148, 8, String.format("%06o  ", checksum));
                gos.write(header);
                gos.write(content);
                int padding = (int) ((512 - (content.length % 512)) % 512);
                gos.write(new byte[padding]);
            }
            gos.write(new byte[1024]);
        }
        return new MockMultipartFile("file", fileName, "application/gzip", baos.toByteArray());
    }

    private static void writeTarString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private static String skillMd(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + name + "\n";
    }
}
