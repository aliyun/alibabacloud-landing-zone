package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.SkillPackageFileContentVO;
import com.aliyun.autowonder.skill.dto.SkillPackageFilesVO;
import com.aliyun.autowonder.skill.dto.SkillPackageInspectVO;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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

    /** 包裹包上传成功路径的公共 mock 脚手架：返回捕获存储字节的 captor。 */
    private ArgumentCaptor<byte[]> mockUpload(long id, String name, String description, String fileName) {
        when(skillDao.findByTypeAndName(1L, "SKILL", name)).thenReturn(null);
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/" + id + "/skill.zip"), captor.capture()))
                .thenAnswer(invocation -> new StoredObject(
                        "artifact-bucket/t/1/skills/" + id + "/skill.zip", "md5", captor.getValue().length));
        doAnswer(invocation -> {
            invocation.<SkillDO>getArgument(0).setId(id);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(skillDao.updatePackage(eq(id), eq(1L), eq("SKILL"), anyString(), eq(name), eq(description),
                eq("OSS_ZIP"), anyString(), eq(fileName), anyLong(), eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(id);
        stored.setTenantId(1L);
        stored.setType("SKILL");
        stored.setName(name);
        stored.setSourceType("OSS_ZIP");
        when(skillDao.findById(id)).thenReturn(stored);
        return captor;
    }

    @Test
    void directoryPackingPreservesContentsAndIsStableAcrossRetries() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", java.util.Base64.getEncoder().encodeToString(skillMd("demo", "Demo").getBytes(StandardCharsets.UTF_8)));
        files.put(".config/empty", "");
        files.put("assets/binary", "AAH/");
        byte[] archive = service.packDirectory(files);
        assertEquals("demo", service.inspect("directory.zip", archive).getName());
        Map<String, String> reversed = new LinkedHashMap<>();
        files.entrySet().stream().sorted(Map.Entry.<String, String>comparingByKey().reversed())
                .forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        assertArrayEquals(archive, service.packDirectory(reversed));
        Map<String, byte[]> unpacked = new LinkedHashMap<>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) unpacked.put(entry.getName(), zip.readAllBytes());
        }
        assertEquals(files.keySet(), unpacked.keySet());
        assertArrayEquals(new byte[]{0, 1, (byte) 255}, unpacked.get("assets/binary"));
        assertEquals(0, unpacked.get(".config/empty").length);
    }

    @Test
    void directoryPackingRejectsUnsafePathsInvalidBase64AndTooManyFiles() {
        for (String path : List.of("../escape", "/absolute", "C:/absolute", "a\\b", "a//b", "./a", "folder/")) {
            assertThrows(BizException.class, () -> service.packDirectory(Map.of(path, "")), path);
        }
        assertThrows(BizException.class, () -> service.packDirectory(Map.of()));
        assertThrows(BizException.class, () -> service.packDirectory(Map.of("SKILL.md", "%%%")));
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < 501; i++) files.put("file" + i, "");
        assertThrows(BizException.class, () -> service.packDirectory(files));
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
        MockMultipartFile file = zip("bad.zip", "nested/docs.md", "no skill manifest");

        BizException ex = assertThrows(BizException.class, () -> service.inspect(file));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void inspectAcceptsZipWrappedInSingleTopLevelDirectory() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("wrapped-skill/", new byte[0]);
        entries.put("wrapped-skill/SKILL.md", skillMd("wrapped-skill", "Wrapped skill").getBytes(StandardCharsets.UTF_8));
        entries.put("wrapped-skill/references/readme.md", "reference".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = zip("wrapped-skill.zip", entries);

        SkillPackageInspectVO vo = service.inspect(file);

        assertEquals("wrapped-skill", vo.getName());
        assertEquals("Wrapped skill", vo.getDescription());
    }

    @Test
    void inspectRejectsZipWithMixedTopLevelEntries() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("README.md", "readme".getBytes(StandardCharsets.UTF_8));
        entries.put("wrapped-skill/SKILL.md", skillMd("wrapped-skill", "Wrapped skill").getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = zip("mixed.zip", entries);

        BizException ex = assertThrows(BizException.class, () -> service.inspect(file));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void inspectRejectsZipWithTwoTopLevelDirectories() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a/SKILL.md", skillMd("a", "desc a").getBytes(StandardCharsets.UTF_8));
        entries.put("b/docs.md", "doc".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = zip("two-roots.zip", entries);

        BizException ex = assertThrows(BizException.class, () -> service.inspect(file));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void inspectRejectsZipWithSkillMdStillNestedAfterStripping() throws Exception {
        MockMultipartFile file = zip("nested.zip", "a/inner/SKILL.md", skillMd("nested", "desc"));

        BizException ex = assertThrows(BizException.class, () -> service.inspect(file));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void inspectAcceptsTarGzWrappedInSingleTopLevelDirectory() throws Exception {
        MockMultipartFile file = tarGz("wrapped-skill.tar.gz", Map.of(
                "wrapped-skill/SKILL.md", skillMd("wrapped-skill", "Wrapped skill"),
                "wrapped-skill/references/readme.md", "reference"));

        SkillPackageInspectVO vo = service.inspect(file);

        assertEquals("wrapped-skill", vo.getName());
        assertEquals("wrapped-skill.tar.gz", vo.getFileName());
    }

    @Test
    void createUploadedSkillFromWrappedZipStoresStrippedArchive() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("wrapped-skill/", new byte[0]);
        entries.put("wrapped-skill/SKILL.md", skillMd("wrapped-skill", "Wrapped skill").getBytes(StandardCharsets.UTF_8));
        entries.put("wrapped-skill/references/readme.md", "reference".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = zip("wrapped-skill.zip", entries);
        ArgumentCaptor<byte[]> storedBytes = mockUpload(10000L, "wrapped-skill", "Wrapped skill", "wrapped-skill.zip");
        SkillDO stored = new SkillDO();
        stored.setId(10000L);

        SkillVO vo = service.createFromPackage(file, 1L, 2L);

        assertEquals("wrapped-skill", vo.getName());
        assertEquals(Set.of("SKILL.md", "references/readme.md"), zipEntryNames(storedBytes.getValue()));
    }

    @Test
    void createUploadedSkillFromWrappedTarGzStoresStrippedArchive() throws Exception {
        MockMultipartFile file = tarGz("wrapped-skill.tar.gz", Map.of(
                "wrapped-skill/SKILL.md", skillMd("wrapped-skill", "Wrapped skill"),
                "wrapped-skill/references/readme.md", "reference"));
        ArgumentCaptor<byte[]> storedBytes = mockUpload(10001L, "wrapped-skill", "Wrapped skill", "wrapped-skill.tar.gz");
        SkillDO stored = new SkillDO();
        stored.setId(10001L);

        SkillVO vo = service.createFromPackage(file, 1L, 2L);

        assertEquals("wrapped-skill", vo.getName());
        assertEquals(Set.of("SKILL.md", "references/readme.md"), tarGzEntryNames(storedBytes.getValue()));
    }

    @Test
    void wrappedMcpUploadSignsStrippedBytesAndIsIdempotent() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("wrapped-skill/", new byte[0]);
        entries.put("wrapped-skill/SKILL.md", skillMd("wrapped-skill", "Wrapped skill").getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = zip("wrapped-skill.zip", entries);
        byte[] original = file.getBytes();
        ArgumentCaptor<byte[]> uploadedBytes = ArgumentCaptor.forClass(byte[].class);
        when(storage.put(eq("artifact-bucket"), startsWith("t/1/skills/packages/"), uploadedBytes.capture()))
                .thenAnswer(invocation -> new StoredObject("artifact-bucket/uploads/wrapped-skill.zip", "md5",
                        uploadedBytes.getValue().length));

        SkillPackageService.UploadedPackage uploaded = service.uploadMcpPackage(
                "wrapped-skill.zip", original, "SKILL", null, null, null, null, 1L);

        byte[] normalized = uploadedBytes.getValue();
        assertEquals(Set.of("SKILL.md"), zipEntryNames(normalized));
        assertEquals(sha256(normalized), uploaded.sha256());
        assertNotEquals(sha256(original), uploaded.sha256());

        SkillDO duplicate = new SkillDO();
        duplicate.setId(10002L);
        duplicate.setTenantId(1L);
        duplicate.setType("SKILL");
        duplicate.setName("wrapped-skill");
        duplicate.setPackageMd5(md5(normalized));
        when(storage.get("artifact-bucket/uploads/wrapped-skill.zip")).thenReturn(original);
        when(skillDao.findByTypeAndName(1L, "SKILL", "wrapped-skill")).thenReturn(duplicate);
        when(skillDao.findById(10002L)).thenReturn(duplicate);

        SkillVO idempotent = service.createFromUploadedPackage(
                "artifact-bucket/uploads/wrapped-skill.zip", "SKILL", null, null, null, md5(original), "idem-1", 1L, 2L);

        assertEquals(10002L, idempotent.getId());
        verify(storage, never()).put(eq("artifact-bucket"), eq("t/1/skills/10002/skill.zip"), any());
    }

    @Test
    void pluginSemanticManifestDirectoryIsStoredAsIs() throws Exception {
        MockMultipartFile file = zip("team-tools.zip",
                ".claude-plugin/plugin.json", "{\"name\":\"team-tools\"}");
        ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
        when(skillDao.findByTypeAndName(1L, "PLUGIN", "team-tools")).thenReturn(null);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10010/skill.zip"), storedBytes.capture()))
                .thenAnswer(invocation -> new StoredObject("artifact-bucket/t/1/skills/10010/skill.zip", "md5",
                        storedBytes.getValue().length));
        doAnswer(invocation -> {
            invocation.<SkillDO>getArgument(0).setId(10010L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(skillDao.updatePackage(eq(10010L), eq(1L), eq("PLUGIN"), anyString(), eq("team-tools"),
                eq("Team tools"), eq("OSS_ZIP"), anyString(), eq("team-tools.zip"), anyLong(),
                eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(10010L);
        stored.setTenantId(1L);
        stored.setType("PLUGIN");
        stored.setName("team-tools");
        stored.setSourceType("OSS_ZIP");
        when(skillDao.findById(10010L)).thenReturn(stored);

        SkillVO vo = service.createFromPackage(file, "PLUGIN", "team-tools", "Team tools",
                List.of("claude"), 1L, 2L);

        assertEquals("PLUGIN", vo.getType());
        assertEquals(Set.of(".claude-plugin/plugin.json"), zipEntryNames(storedBytes.getValue()));
    }

    @Test
    void pluginPackageUnderWrapperDirectoryStaysIntactAcrossReparse() throws Exception {
        MockMultipartFile file = zip("team-tools.zip",
                "demo/.claude-plugin/plugin.json", "{\"name\":\"team-tools\"}");
        ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
        when(skillDao.findByTypeAndName(1L, "PLUGIN", "team-tools")).thenReturn(null);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10011/skill.zip"), storedBytes.capture()))
                .thenAnswer(invocation -> new StoredObject("artifact-bucket/t/1/skills/10011/skill.zip", "md5",
                        storedBytes.getValue().length));
        doAnswer(invocation -> {
            invocation.<SkillDO>getArgument(0).setId(10011L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(skillDao.updatePackage(eq(10011L), eq(1L), eq("PLUGIN"), anyString(), eq("team-tools"),
                eq("Team tools"), eq("OSS_ZIP"), anyString(), eq("team-tools.zip"), anyLong(),
                eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(10011L);
        stored.setTenantId(1L);
        stored.setType("PLUGIN");
        stored.setName("team-tools");
        stored.setSourceType("OSS_ZIP");
        when(skillDao.findById(10011L)).thenReturn(stored);

        service.createFromPackage(file, "PLUGIN", "team-tools", "Team tools", List.of("claude"), 1L, 2L);
        byte[] first = storedBytes.getValue();

        // PLUGIN 不剥壳，布局（含外壳目录）与原始字节一致，重复 parse 链路无二次剥层
        assertEquals(Set.of("demo/.claude-plugin/plugin.json"), zipEntryNames(first));
        assertArrayEquals(file.getBytes(), first);
    }

    @Test
    void wrappedTarGzPreservesChineseEntryNames() throws Exception {
        MockMultipartFile file = tarGz("wrapped-skill.tar.gz", Map.of(
                "wrapped-skill/SKILL.md", skillMd("wrapped-skill", "Wrapped skill"),
                "wrapped-skill/references/中文.md", "中文内容"));
        ArgumentCaptor<byte[]> storedBytes = mockUpload(10012L, "wrapped-skill", "Wrapped skill", "wrapped-skill.tar.gz");
        SkillDO stored = new SkillDO();
        stored.setId(10012L);

        service.createFromPackage(file, 1L, 2L);

        assertEquals(Set.of("SKILL.md", "references/中文.md"), tarGzEntryNames(storedBytes.getValue()));
    }

    @Test
    void tarGzHardLinkEntriesAreRejectedInsteadOfStoredAsEmptyFiles() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "demo/SKILL.md", skillMd("hardlink-skill", "desc").getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "demo/scripts/run.sh", "#!/bin/sh\necho ok\n".getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "demo/scripts/alias.sh", new byte[0], '1', "scripts/run.sh");
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "hardlink-skill.tar.gz", "application/gzip", baos.toByteArray());

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                "hardlink-skill.tar.gz", file.getBytes(), "SKILL", null, null, null, null, 1L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void tarGzWithPaxMetadataHeaderIsAcceptedOnUpload() throws Exception {
        // Python tarfile 默认 PAX 格式：././@PaxHeader 仅携带 mtime 等元数据，不含任何链接
        byte[] meta = paxRecords(Map.of("mtime", "1760000000.123456789"));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "././@PaxHeader", meta, 'x', null);
            writeTarEntry(gos, "SKILL.md", skillMd("pax-skill", "PAX skill").getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "pax-skill.tar.gz", "application/gzip", baos.toByteArray());

        SkillPackageInspectVO vo = service.inspect(file);

        assertEquals("pax-skill", vo.getName());
    }

    @Test
    void wrappedTarGzWithPaxHeaderStillStripsWrapper() throws Exception {
        byte[] meta = paxRecords(Map.of("mtime", "1760000000.123456789"));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "././@PaxHeader", meta, 'x', null);
            writeTarEntry(gos, "demo/SKILL.md", skillMd("wrapped-pax", "Wrapped PAX skill").getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "demo/references/readme.md", "reference".getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "wrapped-pax.tar.gz", "application/gzip", baos.toByteArray());
        ArgumentCaptor<byte[]> storedBytes = mockUpload(10013L, "wrapped-pax", "Wrapped PAX skill", "wrapped-pax.tar.gz");
        SkillDO stored = new SkillDO();
        stored.setId(10013L);

        service.createFromPackage(file, 1L, 2L);

        // PAX 元数据头不参与外壳判定，剥壳重打后的规范包也不再保留它
        assertEquals(Set.of("SKILL.md", "references/readme.md"), tarGzEntryNames(storedBytes.getValue()));
    }

    @Test
    void wrappedTarGzWithLongPaxPathStripsWrapperAndRoundTrips() throws Exception {
        // 116 字节相对路径：剥掉 demo/ 外壳后仍超 ustar name 字段（100 字节），
        // 重打包必须输出 PAX path 扩展记录，且存储后可按完整路径读回
        String longPath = "references/integration-tests/cloud-provider-authentication-and-permissions/"
                + "regression-cases/download-verification.md";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "demo/SKILL.md", skillMd("wrapped-long", "Long path skill").getBytes(StandardCharsets.UTF_8), '0', null);
            byte[] pax = paxRecords(Map.of("path", "demo/" + longPath));
            writeTarEntry(gos, "././@PaxHeader", pax, 'x', null);
            writeTarEntry(gos, "truncated.md", "long path content".getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "wrapped-long.tar.gz", "application/gzip", baos.toByteArray());
        when(skillDao.findByTypeAndName(1L, "SKILL", "wrapped-long")).thenReturn(null);
        ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10014/skill.zip"), storedBytes.capture()))
                .thenAnswer(invocation -> new StoredObject("artifact-bucket/t/1/skills/10014/skill.zip", "md5",
                        storedBytes.getValue().length));
        doAnswer(invocation -> {
            invocation.<SkillDO>getArgument(0).setId(10014L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(skillDao.updatePackage(eq(10014L), eq(1L), eq("SKILL"), anyString(), eq("wrapped-long"),
                eq("Long path skill"), eq("OSS_ZIP"), anyString(), eq("wrapped-long.tar.gz"), anyLong(),
                eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(10014L);
        stored.setTenantId(1L);
        stored.setType("SKILL");
        stored.setName("wrapped-long");
        stored.setSourceType("OSS_ZIP");
        stored.setPackageOssRef("artifact-bucket/t/1/skills/10014/skill.zip");
        stored.setPackageFileName("wrapped-long.tar.gz");
        when(skillDao.findById(10014L)).thenReturn(stored);

        service.createFromPackage(file, 1L, 2L);

        // 存储的规范包：真实长路径经 PAX 记录恢复，截断名不出现在目录树
        SkillVO storedSkill = new SkillVO();
        storedSkill.setId(10014L);
        storedSkill.setType("SKILL");
        storedSkill.setName("wrapped-long");
        storedSkill.setSourceType("OSS_ZIP");
        storedSkill.setPackageOssRef("artifact-bucket/t/1/skills/10014/skill.zip");
        storedSkill.setPackageFileName("wrapped-long.tar.gz");
        when(storage.get("artifact-bucket/t/1/skills/10014/skill.zip")).thenReturn(storedBytes.getValue());
        SkillPackageFilesVO listed = service.listPackageFiles(storedSkill);
        Set<String> paths = listed.getFiles().stream()
                .map(item -> item.getPath()).collect(java.util.stream.Collectors.toSet());
        assertTrue(paths.contains(longPath), "长路径应出现在目录树: " + paths);
        assertFalse(paths.contains("truncated.md"), "截断名不应出现: " + paths);
        // 按完整路径读回内容
        SkillPackageFileContentVO content = service.readPackageFile(storedSkill, longPath);
        assertEquals("long path content", content.getContent());
    }

    @Test
    void wrappedTarGzPaxRecordLengthBeyondThreeDigitsStillUploads() throws Exception {
        // 剥壳后 990+ 字节路径：path 记录总长超三位十进制，须以四位长度字段写出
        StringBuilder sb = new StringBuilder("references/");
        for (int i = 0; i < 9; i++) {
            sb.append("a".repeat(100)).append('/');
        }
        sb.append("b".repeat(62)).append("/note.md");
        String longPath = sb.toString();
        assertTrue(longPath.getBytes(StandardCharsets.UTF_8).length >= 990, longPath.length() + " chars");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "demo/SKILL.md", skillMd("wrapped-edge", "Edge path skill").getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "././@PaxHeader", paxRecords(Map.of("path", "demo/" + longPath)), 'x', null);
            writeTarEntry(gos, "trunc.md", "edge path content".getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "wrapped-edge.tar.gz", "application/gzip", baos.toByteArray());
        ArgumentCaptor<byte[]> storedBytes = mockUpload(10015L, "wrapped-edge", "Edge path skill", "wrapped-edge.tar.gz");
        SkillDO stored = new SkillDO();
        stored.setId(10015L);

        service.createFromPackage(file, 1L, 2L);

        SkillVO storedSkill = new SkillVO();
        storedSkill.setId(10015L);
        storedSkill.setType("SKILL");
        storedSkill.setName("wrapped-edge");
        storedSkill.setSourceType("OSS_ZIP");
        storedSkill.setPackageOssRef("artifact-bucket/t/1/skills/10015/skill.zip");
        storedSkill.setPackageFileName("wrapped-edge.tar.gz");
        when(storage.get("artifact-bucket/t/1/skills/10015/skill.zip")).thenReturn(storedBytes.getValue());
        SkillPackageFileContentVO content = service.readPackageFile(storedSkill, longPath);
        assertEquals("edge path content", content.getContent());
    }

    @Test
    void wrappedTarGzPreservesExecutableModeOfScriptEntries() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "demo/SKILL.md", skillMd("exec-skill", "Exec mode skill").getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "demo/scripts/run.sh", "#!/bin/sh\necho ok\n".getBytes(StandardCharsets.UTF_8), '0', null, 0100755);
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "exec-skill.tar.gz", "application/gzip", baos.toByteArray());
        ArgumentCaptor<byte[]> storedBytes = mockUpload(10016L, "exec-skill", "Exec mode skill", "exec-skill.tar.gz");
        SkillDO stored = new SkillDO();
        stored.setId(10016L);

        service.createFromPackage(file, 1L, 2L);

        assertEquals(0100755, tarEntryMode(storedBytes.getValue(), "scripts/run.sh"));
    }

    @Test
    void wrappedArchivesPreserveExplicitEmptyDirectories() throws Exception {
        // ZIP：demo/cache/ 空目录剥壳后保留为 cache/
        Map<String, byte[]> zipEntries = new LinkedHashMap<>();
        zipEntries.put("demo/", new byte[0]);
        zipEntries.put("demo/SKILL.md", skillMd("empty-dir-skill", "Empty dir skill").getBytes(StandardCharsets.UTF_8));
        zipEntries.put("demo/cache/", new byte[0]);
        MockMultipartFile zipFile = zip("empty-dir-skill.zip", zipEntries);
        when(skillDao.findByTypeAndName(1L, "SKILL", "empty-dir-skill")).thenReturn(null);
        ArgumentCaptor<byte[]> zipStored = ArgumentCaptor.forClass(byte[].class);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10017/skill.zip"), zipStored.capture()))
                .thenAnswer(invocation -> new StoredObject("artifact-bucket/t/1/skills/10017/skill.zip", "md5",
                        zipStored.getValue().length));
        doAnswer(invocation -> {
            invocation.<SkillDO>getArgument(0).setId(10017L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(skillDao.updatePackage(eq(10017L), eq(1L), eq("SKILL"), anyString(), eq("empty-dir-skill"),
                eq("Empty dir skill"), eq("OSS_ZIP"), anyString(), eq("empty-dir-skill.zip"), anyLong(),
                eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO zipSkill = new SkillDO();
        zipSkill.setId(10017L);
        zipSkill.setTenantId(1L);
        zipSkill.setType("SKILL");
        zipSkill.setName("empty-dir-skill");
        zipSkill.setSourceType("OSS_ZIP");
        when(skillDao.findById(10017L)).thenReturn(zipSkill);

        service.createFromPackage(zipFile, 1L, 2L);
        assertTrue(zipEntryNames(zipStored.getValue()).contains("cache/"),
                "zip 空目录应保留: " + zipEntryNames(zipStored.getValue()));

        // tar.gz：demo/cache/ 空目录（type=5）剥壳后保留
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "demo/", new byte[0], '5', null);
            writeTarEntry(gos, "demo/SKILL.md", skillMd("empty-dir-skill", "Empty dir skill").getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "demo/cache/", new byte[0], '5', null);
            gos.write(new byte[1024]);
        }
        MockMultipartFile tarFile = new MockMultipartFile(
                "file", "empty-dir-skill.tar.gz", "application/gzip", baos.toByteArray());
        when(skillDao.findByTypeAndName(1L, "SKILL", "empty-dir-skill")).thenReturn(null);
        ArgumentCaptor<byte[]> tarStored = ArgumentCaptor.forClass(byte[].class);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10018/skill.zip"), tarStored.capture()))
                .thenAnswer(invocation -> new StoredObject("artifact-bucket/t/1/skills/10018/skill.zip", "md5",
                        tarStored.getValue().length));
        doAnswer(invocation -> {
            invocation.<SkillDO>getArgument(0).setId(10018L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(skillDao.updatePackage(eq(10018L), eq(1L), eq("SKILL"), anyString(), eq("empty-dir-skill"),
                eq("Empty dir skill"), eq("OSS_ZIP"), anyString(), eq("empty-dir-skill.tar.gz"), anyLong(),
                eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO tarSkill = new SkillDO();
        tarSkill.setId(10018L);
        tarSkill.setTenantId(1L);
        tarSkill.setType("SKILL");
        tarSkill.setName("empty-dir-skill");
        tarSkill.setSourceType("OSS_ZIP");
        when(skillDao.findById(10018L)).thenReturn(tarSkill);

        service.createFromPackage(tarFile, 1L, 2L);
        Set<String> names = tarGzEntryNames(tarStored.getValue());
        assertTrue(names.contains("SKILL.md") && names.contains("cache/") && !names.contains("demo/"),
                "tar 空目录应保留且 wrapper 移除: " + names);
    }

    @Test
    void wrappedTarGzWithLongPaxDirectoryPathStripsWrapper() throws Exception {
        // 101 字节目录路径：文件分支已有占位名处理，目录此前遗漏同一处理
        String longDir = "references/" + "a".repeat(89) + "/";
        // Python tarfile PAX 形态：真实路径放 PAX 记录，ustar 字段只留截断占位名
        byte[] dirPax = paxRecords(Map.of("path", "demo/" + longDir));
        byte[] filePax = paxRecords(Map.of("path", "demo/" + longDir + "note.md"));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "demo/SKILL.md", skillMd("wrapped-long-dir", "Long dir skill").getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "././@PaxHeader", dirPax, 'x', null);
            writeTarEntry(gos, "truncated-dir", new byte[0], '5', null);
            writeTarEntry(gos, "././@PaxHeader", filePax, 'x', null);
            writeTarEntry(gos, "truncated-file", "note".getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "wrapped-long-dir.tar.gz", "application/gzip", baos.toByteArray());
        when(skillDao.findByTypeAndName(1L, "SKILL", "wrapped-long-dir")).thenReturn(null);
        ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10020/skill.zip"), storedBytes.capture()))
                .thenAnswer(invocation -> new StoredObject("artifact-bucket/t/1/skills/10020/skill.zip", "md5",
                        storedBytes.getValue().length));
        doAnswer(invocation -> {
            invocation.<SkillDO>getArgument(0).setId(10020L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(skillDao.updatePackage(eq(10020L), eq(1L), eq("SKILL"), anyString(), eq("wrapped-long-dir"),
                eq("Long dir skill"), eq("OSS_ZIP"), anyString(), eq("wrapped-long-dir.tar.gz"), anyLong(),
                eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(10020L);
        stored.setTenantId(1L);
        stored.setType("SKILL");
        stored.setName("wrapped-long-dir");
        stored.setSourceType("OSS_ZIP");
        stored.setPackageOssRef("artifact-bucket/t/1/skills/10020/skill.zip");
        stored.setPackageFileName("wrapped-long-dir.tar.gz");
        when(skillDao.findById(10020L)).thenReturn(stored);

        service.createFromPackage(file, 1L, 2L);

        SkillVO storedSkill = new SkillVO();
        storedSkill.setId(10020L);
        storedSkill.setType("SKILL");
        storedSkill.setName("wrapped-long-dir");
        storedSkill.setSourceType("OSS_ZIP");
        storedSkill.setPackageOssRef("artifact-bucket/t/1/skills/10020/skill.zip");
        storedSkill.setPackageFileName("wrapped-long-dir.tar.gz");
        when(storage.get("artifact-bucket/t/1/skills/10020/skill.zip")).thenReturn(storedBytes.getValue());
        // 目录树走真实读取链路（TarCursor 应用 PAX path），占位名不出现
        SkillPackageFilesVO listed = service.listPackageFiles(storedSkill);
        Set<String> paths = listed.getFiles().stream()
                .map(item -> item.getPath()).collect(java.util.stream.Collectors.toSet());
        assertTrue(paths.contains("SKILL.md"), "SKILL.md 应存在: " + paths);
        // 目录路径经 normalizeEntryPath 去尾斜杠；还原名与占位名前 100 字节同形是构造使然
        assertTrue(paths.contains(longDir.substring(0, longDir.length() - 1)),
                "长目录应按完整路径恢复: " + paths);
        assertTrue(paths.contains(longDir + "note.md"), "长目录下文件应按完整路径恢复: " + paths);
        assertFalse(paths.contains("truncated-dir") || paths.contains("truncated-file"),
                "截断占位名不应独立出现: " + paths);
        SkillPackageFileContentVO content = service.readPackageFile(storedSkill, longDir + "note.md");
        assertEquals("note", content.getContent());
    }

    @Test
    void wrappedTarGzPreservesDirectoryMode() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "demo/SKILL.md", skillMd("private-dir-skill", "Private dir skill").getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "demo/private/", new byte[0], '5', null, 0100700);
            writeTarEntry(gos, "demo/private/secret.md", "secret".getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "private-dir-skill.tar.gz", "application/gzip", baos.toByteArray());
        ArgumentCaptor<byte[]> storedBytes = mockUpload(10021L, "private-dir-skill", "Private dir skill", "private-dir-skill.tar.gz");
        SkillDO stored = new SkillDO();
        stored.setId(10021L);

        service.createFromPackage(file, 1L, 2L);

        assertEquals(0100700, tarEntryMode(storedBytes.getValue(), "private/"));
    }

    @Test
    void wrappedUnixZipPreservesExternalAttributes() throws Exception {
        // Unix ZIP：external attrs 携带 0755，剥壳重打后 create_system 与执行位不得丢失
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("demo/SKILL.md", skillMd("unix-zip-skill", "Unix zip skill").getBytes(StandardCharsets.UTF_8));
        contents.put("demo/scripts/run.sh", "#!/bin/sh\necho ok\n".getBytes(StandardCharsets.UTF_8));
        Map<String, int[]> modes = new LinkedHashMap<>();
        modes.put("demo/SKILL.md", new int[]{0100644});
        modes.put("demo/scripts/run.sh", new int[]{0100755});
        byte[] archive = unixZip(modes, contents);
        MockMultipartFile file = new MockMultipartFile(
                "file", "unix-zip-skill.zip", "application/zip", archive);
        ArgumentCaptor<byte[]> storedBytes = mockUpload(10022L, "unix-zip-skill", "Unix zip skill", "unix-zip-skill.zip");
        SkillDO stored = new SkillDO();
        stored.setId(10022L);

        service.createFromPackage(file, 1L, 2L);

        assertEquals(0100755, zipEntryUnixMode(storedBytes.getValue(), "scripts/run.sh"));
        assertEquals(0100644, zipEntryUnixMode(storedBytes.getValue(), "SKILL.md"));
    }

    @Test
    void wrappedUstarPrefixLongPathIsReassembledOnRepack() throws Exception {
        // USTAR 深路径：prefix=demo/references/<110a> + name=demo/note.txt，须拼接后判定外壳
        String deep = "references/" + "a".repeat(110);
        byte[] dirPax = paxRecords(Map.of("path", "demo/" + deep + "/"));
        byte[] filePax = paxRecords(Map.of("path", "demo/" + deep + "/demo/note.txt"));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "demo/SKILL.md", skillMd("ustar-prefix-skill", "USTAR prefix skill").getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "././@PaxHeader", dirPax, 'x', null);
            writeTarEntry(gos, "truncated-dir", new byte[0], '5', null);
            writeTarEntry(gos, "././@PaxHeader", filePax, 'x', null);
            writeTarEntry(gos, "truncated-file", "full path content".getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "ustar-prefix-skill.tar.gz", "application/gzip", baos.toByteArray());
        when(skillDao.findByTypeAndName(1L, "SKILL", "ustar-prefix-skill")).thenReturn(null);
        ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10024/skill.zip"), storedBytes.capture()))
                .thenAnswer(invocation -> new StoredObject("artifact-bucket/t/1/skills/10024/skill.zip", "md5",
                        storedBytes.getValue().length));
        doAnswer(invocation -> {
            invocation.<SkillDO>getArgument(0).setId(10024L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(skillDao.updatePackage(eq(10024L), eq(1L), eq("SKILL"), anyString(), eq("ustar-prefix-skill"),
                eq("USTAR prefix skill"), eq("OSS_ZIP"), anyString(), eq("ustar-prefix-skill.tar.gz"), anyLong(),
                eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(10024L);
        stored.setTenantId(1L);
        stored.setType("SKILL");
        stored.setName("ustar-prefix-skill");
        stored.setSourceType("OSS_ZIP");
        stored.setPackageOssRef("artifact-bucket/t/1/skills/10024/skill.zip");
        stored.setPackageFileName("ustar-prefix-skill.tar.gz");
        when(skillDao.findById(10024L)).thenReturn(stored);

        service.createFromPackage(file, 1L, 2L);

        SkillVO storedSkill = new SkillVO();
        storedSkill.setId(10024L);
        storedSkill.setType("SKILL");
        storedSkill.setName("ustar-prefix-skill");
        storedSkill.setSourceType("OSS_ZIP");
        storedSkill.setPackageOssRef("artifact-bucket/t/1/skills/10024/skill.zip");
        storedSkill.setPackageFileName("ustar-prefix-skill.tar.gz");
        when(storage.get("artifact-bucket/t/1/skills/10024/skill.zip")).thenReturn(storedBytes.getValue());
        SkillPackageFilesVO listed = service.listPackageFiles(storedSkill);
        Set<String> paths = listed.getFiles().stream()
                .map(item -> item.getPath()).collect(java.util.stream.Collectors.toSet());
        assertTrue(paths.contains(deep + "/demo/note.txt"), "USTAR prefix 拼接后深路径应恢复: " + paths);
        assertFalse(paths.contains("demo/note.txt") || paths.contains("note.txt"),
                "错误浅路径不应出现: " + paths);
        SkillPackageFileContentVO content = service.readPackageFile(storedSkill, deep + "/demo/note.txt");
        assertEquals("full path content", content.getContent());
    }

    @Test
    void gnuLongLinkEntriesAreConsumedAsMetadataNotRejected() throws Exception {
        // GNU 长文件名（type=L ././@LongLink）：元数据消费，覆盖下一条目名；短路径对照已覆盖
        String deep = "references/" + "a".repeat(110) + "/note.txt";
        byte[] longName = ("demo/" + deep + "\n").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "demo/SKILL.md", skillMd("gnu-long-skill", "GNU long name skill").getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "././@LongLink", longName, 'L', null);
            writeTarEntry(gos, "placeholder.md", "long name content".getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "gnu-long-skill.tar.gz", "application/gzip", baos.toByteArray());
        when(skillDao.findByTypeAndName(1L, "SKILL", "gnu-long-skill")).thenReturn(null);
        ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10025/skill.zip"), storedBytes.capture()))
                .thenAnswer(invocation -> new StoredObject("artifact-bucket/t/1/skills/10025/skill.zip", "md5",
                        storedBytes.getValue().length));
        doAnswer(invocation -> {
            invocation.<SkillDO>getArgument(0).setId(10025L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(skillDao.updatePackage(eq(10025L), eq(1L), eq("SKILL"), anyString(), eq("gnu-long-skill"),
                eq("GNU long name skill"), eq("OSS_ZIP"), anyString(), eq("gnu-long-skill.tar.gz"), anyLong(),
                eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(10025L);
        stored.setTenantId(1L);
        stored.setType("SKILL");
        stored.setName("gnu-long-skill");
        stored.setSourceType("OSS_ZIP");
        stored.setPackageOssRef("artifact-bucket/t/1/skills/10025/skill.zip");
        stored.setPackageFileName("gnu-long-skill.tar.gz");
        when(skillDao.findById(10025L)).thenReturn(stored);

        SkillVO vo = service.createFromPackage(file, 1L, 2L);

        assertEquals("gnu-long-skill", vo.getName());
        SkillVO storedSkill = new SkillVO();
        storedSkill.setId(10025L);
        storedSkill.setType("SKILL");
        storedSkill.setName("gnu-long-skill");
        storedSkill.setSourceType("OSS_ZIP");
        storedSkill.setPackageOssRef("artifact-bucket/t/1/skills/10025/skill.zip");
        storedSkill.setPackageFileName("gnu-long-skill.tar.gz");
        when(storage.get("artifact-bucket/t/1/skills/10025/skill.zip")).thenReturn(storedBytes.getValue());
        SkillPackageFileContentVO content = service.readPackageFile(storedSkill, deep);
        assertEquals("long name content", content.getContent());
    }

    @Test
    void explicitZeroModeIsPreservedForFileAndDirectory() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "demo/SKILL.md", skillMd("zero-mode-skill", "Zero mode skill").getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "demo/blocked.txt", "blocked".getBytes(StandardCharsets.UTF_8), '0', null, 0);
            writeTarEntry(gos, "demo/blocked/", new byte[0], '5', null, 0);
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "zero-mode-skill.tar.gz", "application/gzip", baos.toByteArray());
        ArgumentCaptor<byte[]> storedBytes = mockUpload(10026L, "zero-mode-skill", "Zero mode skill", "zero-mode-skill.tar.gz");
        SkillDO stored = new SkillDO();
        stored.setId(10026L);

        service.createFromPackage(file, 1L, 2L);

        assertEquals(0, tarEntryMode(storedBytes.getValue(), "blocked.txt"));
        assertEquals(0, tarEntryMode(storedBytes.getValue(), "blocked/"));
    }

    @Test
    void pluginTarWithDangerousEntriesIsRejectedRegardlessOfRootFileSearch() throws Exception {
        // readTarGz(bytes, null)（PLUGIN 路径）此前校验被 rootFileName != null 短路
        char[] dangerous = {'2', '1', '6', '3', '4'};
        for (char type : dangerous) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                writeTarEntry(gos, "plugin.json", "{}".getBytes(StandardCharsets.UTF_8), '0', null);
                writeTarEntry(gos, type == '2' ? "evil-link" : "special", new byte[0], type,
                        type == '2' ? "plugin.json" : null);
                gos.write(new byte[1024]);
            }
            MockMultipartFile file = new MockMultipartFile(
                    "file", "team-tools.tar.gz", "application/gzip", baos.toByteArray());
            String label = "type=" + type;

            BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                    "team-tools.tar.gz", file.getBytes(), "PLUGIN", "team-tools", "desc",
                    List.of("claude"), null, 1L), label);

            assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode(), label);
            verify(storage, never()).put(anyString(), anyString(), any());
        }
    }

    @Test
    void pluginTarWithEscapingPathIsRejectedOnEveryEntry() throws Exception {
        for (String bad : new String[]{"../escape.txt", "/absolute.txt"}) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                writeTarEntry(gos, "plugin.json", "{}".getBytes(StandardCharsets.UTF_8), '0', null);
                writeTarEntry(gos, bad, "x".getBytes(StandardCharsets.UTF_8), '0', null);
                gos.write(new byte[1024]);
            }
            MockMultipartFile file = new MockMultipartFile(
                    "file", "team-tools.tar.gz", "application/gzip", baos.toByteArray());

            BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                    "team-tools.tar.gz", file.getBytes(), "PLUGIN", "team-tools", "desc",
                    List.of("claude"), null, 1L), bad);

            assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode(), bad);
        }
    }

    @Test
    void tarWithOversizedPaxMetadataIsRejectedAtStreamBoundary() throws Exception {
        // 压缩输入很小、PAX comment 声明 101MB：库在 getNextTarEntry 内部消费，
        // 只能在 GZIP 解压边界统一计量，超 100MB 上限即拒绝
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 101 * 1024 * 1024 / 10; i++) {
            huge.append("0123456789");
        }
        byte[] meta = paxRecords(Map.of("comment", huge.toString()));
        assertTrue(meta.length > SkillPackageService.MAX_PACKAGE_SIZE, String.valueOf(meta.length));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "././@PaxHeader", meta, 'x', null);
            writeTarEntry(gos, "SKILL.md", skillMd("pax-bomb", "desc").getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "pax-bomb.tar.gz", "application/gzip", baos.toByteArray());
        assertTrue(file.getSize() < 1024 * 1024, "压缩输入应很小");

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                "pax-bomb.tar.gz", file.getBytes(), "SKILL", null, null, null, null, 1L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("解压后大小超过上限"), ex.getMessage());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void paxOriginalPathWithLeadingSlashIsRejectedByRawValue() throws Exception {
        // 库应用 PAX path 时 setName 去掉前导斜杠，校验 getName() 看不到原始绝对路径；
        // 须按 PAX payload 原始值拒绝，SKILL（root 原样存储）与 PLUGIN 都不得放行
        byte[] meta = paxRecords(Map.of("path", "/review-absolute.txt"));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "././@PaxHeader", meta, 'x', null);
            writeTarEntry(gos, "review-absolute.txt", "x".getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "SKILL.md", skillMd("abs-skill", "Abs path skill").getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        byte[] archive = baos.toByteArray();
        String label = "pax absolute path";

        BizException skill = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                "abs-skill.tar.gz", archive, "SKILL", null, null, null, null, 1L), label);
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), skill.getCode(), label);

        BizException plugin = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                "team-tools.tar.gz", archive, "PLUGIN", "team-tools", "desc",
                List.of("claude"), null, 1L), label);
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), plugin.getCode(), label);
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void gnuLongNameWithLeadingSlashIsRejected() throws Exception {
        // GNU LongName 指定 /<110a>/review.txt：库同样对 getName() 去前导斜杠
        String absolute = "/" + "a".repeat(110) + "/review.txt";
        byte[] longName = (absolute + "\n").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "SKILL.md", skillMd("gnu-abs-skill", "GNU abs skill").getBytes(StandardCharsets.UTF_8), '0', null);
            writeTarEntry(gos, "././@LongLink", longName, 'L', null);
            writeTarEntry(gos, "placeholder.txt", "x".getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        byte[] archive = baos.toByteArray();

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                "gnu-abs-skill.tar.gz", archive, "SKILL", null, null, null, null, 1L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void trailingSlashLinkEntriesAreRejectedNotTreatedAsDirectories() throws Exception {
        // 名称带尾斜杠的符号/硬链接：isDirectory() 宽容判定（尾斜杠即目录）会绕过类型排除
        for (char type : new char[]{'2', '1'}) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                writeTarEntry(gos, "SKILL.md", skillMd("slash-link-skill", "desc").getBytes(StandardCharsets.UTF_8), '0', null);
                writeTarEntry(gos, "special/", new byte[0], type, "SKILL.md");
                gos.write(new byte[1024]);
            }
            byte[] archive = baos.toByteArray();
            String label = "trailing-slash link type=" + type;

            BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                    "slash-link-skill.tar.gz", archive, "SKILL", null, null, null, null, 1L), label);
            assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode(), label);

            BizException plugin = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                    "team-tools.tar.gz", archive, "PLUGIN", "team-tools", "desc",
                    List.of("claude"), null, 1L), label);
            assertEquals(ErrorCode.PARAM_INVALID.getCode(), plugin.getCode(), label);
        }
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void longChainOfSmallPaxHeadersIsRejectedByHeaderRecordCount() throws Exception {
        // 小体积连续 PAX 头链：库对连续 PAX 头逐层递归 getNextEntry，
        // 物理头记录数超 MAX_ENTRIES 须受控拒绝（防 StackOverflowError）
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            byte[] meta = paxRecords(Map.of("comment", "c"));
            for (int i = 0; i < 501; i++) {
                writeTarEntry(gos, "././@PaxHeader", meta, 'x', null);
            }
            writeTarEntry(gos, "SKILL.md", skillMd("chain-skill", "desc").getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        byte[] archive = baos.toByteArray();
        assertTrue(archive.length < 100 * 1024, "压缩输入应很小: " + archive.length);

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                "chain-skill.tar.gz", archive, "SKILL", null, null, null, null, 1L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("条目数超过上限"), ex.getMessage());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void uppercasePaxHeaderChainsAreRejectedLikeLowercase() throws Exception {
        // X（SunOS tar -E）与 x 同被库的 isPaxHeader() 识别并递归消费：
        // 深链与超限同样须受控拒绝，且单个 X 头的正常包保持可读
        byte[] meta = paxRecords(Map.of("comment", "c"));
        for (int count : new int[]{501, 20000}) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                for (int i = 0; i < count; i++) {
                    writeTarEntry(gos, "././@PaxHeader", meta, 'X', null);
                }
                writeTarEntry(gos, "SKILL.md", skillMd("x-chain-skill", "desc").getBytes(StandardCharsets.UTF_8), '0', null);
                gos.write(new byte[1024]);
            }
            byte[] archive = baos.toByteArray();
            String label = count + " uppercase-X headers";

            BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                    "x-chain-skill.tar.gz", archive, "SKILL", null, null, null, null, 1L), label);

            assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode(), label);
            assertTrue(ex.getMessage().contains("条目数超过上限"), label + ": " + ex.getMessage());
            verify(storage, never()).put(anyString(), anyString(), any());
        }

        // 单个 X 头携带合法元数据 + 根 SKILL.md 的正常包不受影响
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "././@PaxHeader", meta, 'X', null);
            writeTarEntry(gos, "SKILL.md", skillMd("x-normal-skill", "desc").getBytes(StandardCharsets.UTF_8), '0', null);
            gos.write(new byte[1024]);
        }
        SkillPackageInspectVO vo = service.inspect(new MockMultipartFile(
                "file", "x-normal-skill.tar.gz", "application/gzip", baos.toByteArray()));
        assertEquals("x-normal-skill", vo.getName());
    }

    @Test
    void tarHeaderCountDoesNotConsumeEntryQuotaWithEofBlocks() throws Exception {
        // 500 上限须容纳满额逻辑条目：两个全零结束块不计入头记录计数
        for (int fileCount : new int[]{498, 499, 500}) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                writeTarEntry(gos, "SKILL.md", skillMd("quota-skill", "desc").getBytes(StandardCharsets.UTF_8), '0', null);
                for (int i = 0; i < fileCount - 1; i++) {
                    writeTarEntry(gos, "f" + i + ".txt", "x".getBytes(StandardCharsets.UTF_8), '0', null);
                }
                gos.write(new byte[1024]);
            }
            byte[] archive = baos.toByteArray();
            String label = fileCount + " entries";

            SkillPackageInspectVO vo = service.inspect(new MockMultipartFile(
                    "file", "quota-skill.tar.gz", "application/gzip", archive));

            assertEquals("quota-skill", vo.getName(), label);
        }
    }

    @Test
    void repackedTarWithPaxHeadersForLongPathsReadsBackWithinQuota() throws Exception {
        // 251 个逻辑文件（USTAR prefix 编码）剥壳后重打包生成 250 个 PAX 头：
        // 输出 501 个物理头，读取自身输出不得报条目超限
        String deepDir = "references/" + "a".repeat(85) + "/";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/SKILL.md", skillMd("pax-quota-skill", "desc").getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 250; i++) {
            entries.put("demo/" + deepDir + "note-" + i + ".md", ("n" + i).getBytes(StandardCharsets.UTF_8));
        }
        when(skillDao.findByTypeAndName(1L, "SKILL", "pax-quota-skill")).thenReturn(null);
        ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10030/skill.zip"), storedBytes.capture()))
                .thenAnswer(invocation -> new StoredObject("artifact-bucket/t/1/skills/10030/skill.zip", "md5",
                        storedBytes.getValue().length));
        doAnswer(invocation -> {
            invocation.<SkillDO>getArgument(0).setId(10030L);
            return null;
        }).when(skillDao).insert(any(SkillDO.class));
        when(skillDao.updatePackage(eq(10030L), eq(1L), eq("SKILL"), anyString(), eq("pax-quota-skill"),
                eq("desc"), eq("OSS_ZIP"), anyString(), eq("pax-quota-skill.tar.gz"), anyLong(),
                eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(10030L);
        stored.setTenantId(1L);
        stored.setType("SKILL");
        stored.setName("pax-quota-skill");
        stored.setSourceType("OSS_ZIP");
        when(skillDao.findById(10030L)).thenReturn(stored);

        // 构造 USTAR prefix 编码的源包：长路径放 prefix 字段
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            writeTarEntry(gos, "demo/SKILL.md", entries.get("demo/SKILL.md"), '0', null);
            for (int i = 0; i < 250; i++) {
                String full = "demo/" + deepDir + "note-" + i + ".md";
                // name 字段放不下整路径时用 prefix+name 拆分（每段 <100 字节）
                String name;
                String prefix;
                if (full.length() <= 100) {
                    name = full;
                    prefix = null;
                } else {
                    int split = full.lastIndexOf('/', 99);
                    if (split < 0) {
                        throw new IllegalStateException("路径段超长: " + full);
                    }
                    name = full.substring(split + 1);
                    prefix = full.substring(0, split);
                }
                writeTarEntryWithPrefix(gos, full, name, prefix, entries.get(full));
            }
            gos.write(new byte[1024]);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "pax-quota-skill.tar.gz", "application/gzip", baos.toByteArray());

        service.createFromPackage(file, 1L, 2L);

        // 存储的规范包（250 个 PAX 头 + 251 个逻辑条目）经真实读取链路列目录
        SkillVO storedSkill = new SkillVO();
        storedSkill.setId(10030L);
        storedSkill.setType("SKILL");
        storedSkill.setName("pax-quota-skill");
        storedSkill.setSourceType("OSS_ZIP");
        storedSkill.setPackageOssRef("artifact-bucket/t/1/skills/10030/skill.zip");
        storedSkill.setPackageFileName("pax-quota-skill.tar.gz");
        when(storage.get("artifact-bucket/t/1/skills/10030/skill.zip")).thenReturn(storedBytes.getValue());
        SkillPackageFilesVO listed = service.listPackageFiles(storedSkill);
        long noteCount = listed.getFiles().stream()
                .map(item -> item.getPath())
                .filter(p -> p.startsWith("references/" + "a".repeat(85) + "/note-")).count();
        assertEquals(250, noteCount, "全部长路径条目应可读回");
    }

    @Test
    void wrappedZipWithCorruptedEntryIsRejectedInsteadOfRepacked() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/SKILL.md", skillMd("crc-skill", "CRC skill").getBytes(StandardCharsets.UTF_8));
        entries.put("demo/references/note.txt", "reference payload".getBytes(StandardCharsets.UTF_8));
        byte[] archive = corruptedStoredZip(entries, "demo/references/note.txt");
        MockMultipartFile file = new MockMultipartFile("file", "crc-skill.zip", "application/zip", archive);

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                "crc-skill.zip", archive, "SKILL", null, null, null, null, 1L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void rootLayoutZipWithCorruptedEntryIsRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("SKILL.md", skillMd("crc-skill", "CRC skill").getBytes(StandardCharsets.UTF_8));
        entries.put("references/note.txt", "reference payload".getBytes(StandardCharsets.UTF_8));
        byte[] archive = corruptedStoredZip(entries, "references/note.txt");
        MockMultipartFile file = new MockMultipartFile("file", "crc-skill.zip", "application/zip", archive);

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                "crc-skill.zip", archive, "SKILL", null, null, null, null, 1L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void wrappedZipWithLegalZeroCrcFileUploads() throws Exception {
        // 9d0ad96d 的 CRC32 恰为 0：非空合法零 CRC 文件不得被误判或跳过校验
        byte[] zeroCrc = new byte[]{(byte) 0x9d, 0x0a, (byte) 0xd9, 0x6d};
        assertEquals(0L, new java.util.zip.CRC32() {{
            update(zeroCrc);
        }}.getValue());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/SKILL.md", skillMd("zero-crc-skill", "Zero CRC skill").getBytes(StandardCharsets.UTF_8));
        entries.put("demo/references/data.bin", zeroCrc);
        entries.put("demo/references/empty.txt", new byte[0]);
        byte[] archive = storedZip(entries);
        MockMultipartFile file = new MockMultipartFile("file", "zero-crc-skill.zip", "application/zip", archive);
        ArgumentCaptor<byte[]> storedBytes = mockUpload(10023L, "zero-crc-skill", "Zero CRC skill", "zero-crc-skill.zip");
        SkillDO stored = new SkillDO();
        stored.setId(10023L);

        SkillVO vo = service.createFromPackage(file, 1L, 2L);

        assertEquals("zero-crc-skill", vo.getName());
        // 非空零 CRC 文件与空文件（CRC 亦为 0）同包并存且均保持合法
        Set<String> names = zipEntryNames(storedBytes.getValue());
        assertTrue(names.contains("references/data.bin"), names.toString());
        assertTrue(names.contains("references/empty.txt"), names.toString());
    }

    @Test
    void wrappedZipWithCorruptedZeroCrcFileIsRejected() throws Exception {
        // 合法零 CRC 数据 9d0ad96d 翻转首字节为 9c0ad96d（实际 CRC 0xb8bc6765），
        // 本地头/中央目录声明值保持 0，不得因声明值为 0 跳过校验
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/SKILL.md", skillMd("zero-crc-skill", "Zero CRC skill").getBytes(StandardCharsets.UTF_8));
        entries.put("demo/references/data.bin", new byte[]{(byte) 0x9d, 0x0a, (byte) 0xd9, 0x6d});
        byte[] archive = storedZip(entries);
        byte[] needle = {(byte) 0x9c};
        // 定位 payload（STORED 原始字节）中 9d 开头的 4 字节序列并翻转首字节
        outer:
        for (int i = 0; i < archive.length - 4; i++) {
            if (archive[i] == (byte) 0x9d && archive[i + 1] == 0x0a
                    && archive[i + 2] == (byte) 0xd9 && archive[i + 3] == 0x6d) {
                archive[i] = (byte) (archive[i] ^ 0x01);
                break outer;
            }
        }
        MockMultipartFile file = new MockMultipartFile("file", "zero-crc-skill.zip", "application/zip", archive);

        BizException ex = assertThrows(BizException.class, () -> service.uploadMcpPackage(
                "zero-crc-skill.zip", archive, "SKILL", null, null, null, null, 1L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(storage, never()).put(anyString(), anyString(), any());
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
        verify(skillDao, never()).releaseSoftDeletedName(anyLong(), anyString(), anyString());
    }

    @Test
    void createUploadedSkillReleasesSoftDeletedNameBeforeInsert() throws Exception {
        MockMultipartFile file = skillZip("revived-skill", "Revived after delete");
        when(skillDao.findByTypeAndName(1L, "SKILL", "revived-skill")).thenReturn(null);
        doAnswer(invocation -> { invocation.<SkillDO>getArgument(0).setId(10003L); return null; })
                .when(skillDao).insert(any(SkillDO.class));
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10003/skill.zip"), any()))
                .thenReturn(new StoredObject("artifact-bucket/t/1/skills/10003/skill.zip", "md5", file.getSize()));
        when(skillDao.updatePackage(eq(10003L), eq(1L), eq("SKILL"), anyString(), eq("revived-skill"),
                eq("Revived after delete"), eq("OSS_ZIP"), anyString(), eq("revived-skill.zip"),
                eq(file.getSize()), eq("md5"), eq(0), eq(2L))).thenReturn(1);
        SkillDO stored = new SkillDO();
        stored.setId(10003L);
        stored.setTenantId(1L);
        stored.setType("SKILL");
        stored.setName("revived-skill");
        stored.setSourceType("OSS_ZIP");
        when(skillDao.findById(10003L)).thenReturn(stored);

        SkillVO vo = service.createFromPackage(file, 1L, 2L);

        assertEquals("revived-skill", vo.getName());
        InOrder inOrder = inOrder(skillDao);
        inOrder.verify(skillDao).releaseSoftDeletedName(1L, "SKILL", "revived-skill");
        inOrder.verify(skillDao).insert(any(SkillDO.class));
    }

    @Test
    void createUploadedSkillDoesNotReleasePlaceholderWhenActiveDuplicateExists() throws Exception {
        MockMultipartFile file = skillZip("duplicate-skill", "Updated description");
        SkillDO duplicate = new SkillDO();
        duplicate.setId(10001L);
        duplicate.setPackageMd5("other-md5");
        when(skillDao.findByTypeAndName(1L, "SKILL", "duplicate-skill")).thenReturn(duplicate);

        BizException ex = assertThrows(BizException.class, () -> service.createFromPackage(file, 1L, 2L));

        assertEquals(ErrorCode.SKILL_DUPLICATE_NAME.getCode(), ex.getCode());
        verify(skillDao, never()).releaseSoftDeletedName(anyLong(), anyString(), anyString());
        verify(skillDao, never()).insert(any());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void updateUploadedSkillReleasesSoftDeletedNameBeforeUploadingBytes() throws Exception {
        MockMultipartFile file = skillZip("revived-skill", "Revived after delete");
        SkillDO existing = new SkillDO();
        existing.setId(10000L);
        existing.setTenantId(1L);
        existing.setType("SKILL");
        existing.setName("custom-skill");
        existing.setVersion(3);
        when(skillDao.findById(10000L)).thenReturn(existing);
        when(skillDao.findByTypeAndName(1L, "SKILL", "revived-skill")).thenReturn(null);
        when(storage.put(eq("artifact-bucket"), eq("t/1/skills/10000/skill.zip"), any()))
                .thenReturn(new StoredObject("artifact-bucket/t/1/skills/10000/skill.zip", "md5", file.getSize()));
        when(skillDao.updatePackage(eq(10000L), eq(1L), eq("SKILL"), anyString(), eq("revived-skill"),
                eq("Revived after delete"), eq("OSS_ZIP"), anyString(), eq("revived-skill.zip"),
                eq(file.getSize()), eq("md5"), eq(3), eq(2L))).thenReturn(1);

        service.updatePackage(10000L, file, 1L, 2L);

        InOrder inOrder = inOrder(skillDao, storage);
        inOrder.verify(skillDao).releaseSoftDeletedName(1L, "SKILL", "revived-skill");
        inOrder.verify(storage).put(eq("artifact-bucket"), eq("t/1/skills/10000/skill.zip"), any());
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
                if (!entry.getKey().endsWith("/")) {
                    zos.write(entry.getValue());
                }
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
                writeTarEntry(gos, entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8), '0', null);
            }
            gos.write(new byte[1024]);
        }
        return new MockMultipartFile("file", fileName, "application/gzip", baos.toByteArray());
    }

    private static void writeTarEntry(GZIPOutputStream gos, String name, byte[] content, char type,
                                      String linkName) throws Exception {
        writeTarEntry(gos, name, content, type, linkName, 0100777);
    }

    private static void writeTarEntry(GZIPOutputStream gos, String name, byte[] content, char type,
                                      String linkName, int mode) throws Exception {
        byte[] header = new byte[512];
        writeTarString(header, 0, 100, name);
        writeTarString(header, 100, 8, String.format("%07o", mode));
        writeTarString(header, 108, 8, "0000000");
        writeTarString(header, 116, 8, "0000000");
        writeTarString(header, 124, 12, String.format("%011o", content.length));
        writeTarString(header, 136, 12, "00000000000");
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = (byte) type;
        if (linkName != null) {
            writeTarString(header, 157, 100, linkName);
        }
        writeTarString(header, 257, 6, "ustar");
        int checksum = 0;
        for (byte value : header) {
            checksum += value & 0xff;
        }
        writeTarString(header, 148, 8, String.format("%06o ", checksum));
        gos.write(header);
        gos.write(content);
        int padding = (int) ((512 - (content.length % 512)) % 512);
        gos.write(new byte[padding]);
    }

    /** USTAR prefix 编码写条目：长路径拆 prefix(345-499) + name(0-99)。 */
    private static void writeTarEntryWithPrefix(GZIPOutputStream gos, String fullPath, String name,
                                                String prefix, byte[] content) throws Exception {
        byte[] header = new byte[512];
        writeTarString(header, 0, 100, name);
        writeTarString(header, 100, 8, "0000644");
        writeTarString(header, 108, 8, "0000000");
        writeTarString(header, 116, 8, "0000000");
        writeTarString(header, 124, 12, String.format("%011o", content.length));
        writeTarString(header, 136, 12, "00000000000");
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = '0';
        if (prefix != null) {
            writeTarString(header, 345, 155, prefix);
        }
        writeTarString(header, 257, 6, "ustar");
        writeTarString(header, 263, 2, "00");
        int checksum = 0;
        for (byte value : header) {
            checksum += value & 0xff;
        }
        writeTarString(header, 148, 8, String.format("%06o ", checksum));
        gos.write(header);
        gos.write(content);
        int padding = (int) ((512 - (content.length % 512)) % 512);
        gos.write(new byte[padding]);
    }

    /** PAX 记录格式 "%d key=value\n"，%d 为含自身的整条 UTF-8 字节长度。 */
    private static byte[] paxRecords(Map<String, String> records) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : records.entrySet()) {
            int valueBytes = (e.getKey() + "=" + e.getValue()).getBytes(StandardCharsets.UTF_8).length;
            String record = null;
            int digits = 1;
            while (record == null) {
                int total = digits + 1 + valueBytes + 1;
                if (String.valueOf(total).length() == digits) {
                    record = total + " " + e.getKey() + "=" + e.getValue() + "\n";
                } else {
                    digits = String.valueOf(total).length();
                }
            }
            sb.append(record);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void writeTarString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > length) {
            throw new IllegalArgumentException("tar header field overflow: " + value);
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    private static String skillMd(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + name + "\n";
    }

    /** 合法 ZIP_STORED 构造（正确 CRC），供零 CRC 与损坏对照复用。 */
    private static byte[] storedZip(Map<String, byte[]> entries) throws Exception {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zip =
                new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(baos);
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            org.apache.commons.compress.archivers.zip.ZipArchiveEntry entry =
                    new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(e.getKey());
            entry.setMethod(org.apache.commons.compress.archivers.zip.ZipArchiveEntry.STORED);
            entry.setSize(e.getValue().length);
            crc.reset();
            crc.update(e.getValue());
            entry.setCrc(crc.getValue());
            zip.putArchiveEntry(entry);
            zip.write(e.getValue());
            zip.closeArchiveEntry();
        }
        zip.finish();
        zip.close();
        return baos.toByteArray();
    }

    /** 合法 STORED 构造后翻转指定条目 payload 单字节，声明 CRC 不动，模拟传输损坏。 */
    private static byte[] corruptedStoredZip(Map<String, byte[]> entries, String corruptEntry) throws Exception {
        byte[] archive = storedZip(entries);
        byte[] needle = entries.get(corruptEntry);
        outer:
        for (int i = 0; i < archive.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (archive[i + j] != needle[j]) {
                    continue outer;
                }
            }
            archive[i] = (byte) (archive[i] ^ 0x01);
            return archive;
        }
        throw new AssertionError("payload not found in archive");
    }

    /** Unix ZIP 构造：条目携带 create_system=3 与指定 mode（external attrs 高 16 位）。 */
    private static byte[] unixZip(Map<String, int[]> nameToModeAndSize, Map<String, byte[]> contents) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zip =
                new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(baos);
        for (Map.Entry<String, int[]> spec : nameToModeAndSize.entrySet()) {
            org.apache.commons.compress.archivers.zip.ZipArchiveEntry entry =
                    new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(spec.getKey());
            entry.setUnixMode(spec.getValue()[0]);
            byte[] content = contents.getOrDefault(spec.getKey(), new byte[0]);
            entry.setSize(content.length);
            zip.putArchiveEntry(entry);
            zip.write(content);
            zip.closeArchiveEntry();
        }
        zip.finish();
        zip.close();
        return baos.toByteArray();
    }

    /** 读取 Unix ZIP 条目的 mode（external attrs 高 16 位）。 */
    private static int zipEntryUnixMode(byte[] archive, String targetPath) throws Exception {
        try (org.apache.commons.compress.archivers.zip.ZipFile zip =
                new org.apache.commons.compress.archivers.zip.ZipFile(
                        new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(archive))) {
            java.util.Enumeration<org.apache.commons.compress.archivers.zip.ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                org.apache.commons.compress.archivers.zip.ZipArchiveEntry entry = entries.nextElement();
                if (entry.getName().equals(targetPath)) {
                    return entry.getUnixMode();
                }
            }
        }
        throw new AssertionError("zip entry not found: " + targetPath);
    }

    private static Set<String> zipEntryNames(byte[] archive) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static int tarEntryMode(byte[] archive, String targetPath) throws Exception {
        try (TarArchiveInputStream tin = new TarArchiveInputStream(
                new GZIPInputStream(new java.io.ByteArrayInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tin.getNextTarEntry()) != null) {
                if (entry.getName().equals(targetPath)) {
                    return entry.getMode();
                }
            }
        }
        throw new AssertionError("entry not found: " + targetPath);
    }

    private static Set<String> tarGzEntryNames(byte[] archive) throws Exception {
        Set<String> names = new HashSet<>();
        try (TarArchiveInputStream tin = new TarArchiveInputStream(
                new GZIPInputStream(new java.io.ByteArrayInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tin.getNextTarEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
