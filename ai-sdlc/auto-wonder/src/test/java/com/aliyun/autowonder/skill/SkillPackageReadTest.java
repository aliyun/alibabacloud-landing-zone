package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.SkillPackageFileContentVO;
import com.aliyun.autowonder.skill.dto.SkillPackageFileVO;
import com.aliyun.autowonder.skill.dto.SkillPackageFilesVO;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 覆盖需求 #53214「SKILL 技能上传后无法看到详细内容」的只读包内容能力：
 * FR-1 目录树、FR-2 单文件在线预览、FR-3 原始包下载、FR-4 可见性与路径防护、FR-5 上传侧限额复用。
 */
class SkillPackageReadTest {

    private static final String OSS_REF = "skills/10002/custom-skill.zip";
    /** 末段为空：packageFileName 缺失时回退出的文件名也是空白，只能靠魔数判定格式。 */
    private static final String REF_WITHOUT_NAME = "skills/10002/";
    /** 与 SkillPackageService.MAX_ENTRIES 对齐：501 个条目即超限。 */
    private static final int TOO_MANY_ENTRIES = 501;

    private ObjectStorage storage;
    private SkillPackageService service;

    @BeforeEach
    void setUp() {
        SkillDao skillDao = mock(SkillDao.class);
        storage = mock(ObjectStorage.class);
        SkillService skillService = new SkillService(skillDao, mock(AgentSkillDao.class), mock(UserDao.class));
        service = new SkillPackageService(skillDao, skillService, storage, "artifact-bucket");
    }

    // ------------------------------------------------------------------
    // FR-1 / AC-1：目录树
    // ------------------------------------------------------------------

    @Test
    void listPackageFilesReturnsZipEntriesWithImplicitDirectoriesAndKinds() throws Exception {
        byte[] bytes = zip(Map.of(
                "SKILL.md", text("---\nname: custom-skill\n---\n# 技能说明\n"),
                "references/guide.md", text("参考文档"),
                "assets/logo.png", binary(),
                "notes", text("没有扩展名的纯文本"),
                "bin/tool", new byte[]{0x7f, 'E', 'L', 'F', 0x00, 0x01}));
        SkillVO skill = ossZipSkill("custom-skill.zip", bytes);

        SkillPackageFilesVO vo = service.listPackageFiles(skill);

        assertEquals("zip", vo.getFormat());
        Map<String, SkillPackageFileVO> byPath = index(vo.getFiles());

        // 隐式目录：由 references/guide.md、assets/logo.png、bin/tool 反推补出
        assertDir(byPath, "references");
        assertDir(byPath, "assets");
        assertDir(byPath, "bin");

        SkillPackageFileVO skillMd = byPath.get("SKILL.md");
        assertFalse(skillMd.getDir());
        assertEquals("SKILL.md", skillMd.getName());
        assertEquals("TEXT", skillMd.getKind());
        assertEquals((long) text("---\nname: custom-skill\n---\n# 技能说明\n").length, skillMd.getSize().longValue());

        assertEquals("IMAGE", byPath.get("assets/logo.png").getKind());
        assertEquals("logo.png", byPath.get("assets/logo.png").getName());
        assertEquals((long) binary().length, byPath.get("assets/logo.png").getSize().longValue());

        // 无扩展名条目走内容嗅探：可见文本判 TEXT，含 NUL 判 BINARY
        assertEquals("TEXT", byPath.get("notes").getKind());
        assertEquals("BINARY", byPath.get("bin/tool").getKind());

        assertEquals(8, vo.getFiles().size(), "5 个文件条目 + 3 个隐式补出的目录");
    }

    @Test
    void listPackageFilesDoesNotDuplicateExplicitDirectoryEntries() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            putZipEntry(zos, "references/", new byte[0]);
            putZipEntry(zos, "references/guide.md", text("参考文档"));
            putZipEntry(zos, "SKILL.md", text("# s"));
        }
        SkillVO skill = ossZipSkill("custom-skill.zip", baos.toByteArray());

        SkillPackageFilesVO vo = service.listPackageFiles(skill);

        long dirRecords = vo.getFiles().stream()
                .filter(file -> "references".equals(file.getPath()))
                .count();
        assertEquals(1, dirRecords, "显式目录条目与隐式补出的目录不应重复");
        assertEquals(3, vo.getFiles().size());
    }

    @Test
    void listPackageFilesReadsTarGzEntries() throws Exception {
        byte[] bytes = tarGz(Map.of(
                "SKILL.md", "---\nname: custom-skill\n---\n# 技能说明\n",
                "references/guide.md", "参考文档"));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        SkillPackageFilesVO vo = service.listPackageFiles(skill);

        assertEquals("tar.gz", vo.getFormat());
        Map<String, SkillPackageFileVO> byPath = index(vo.getFiles());
        assertDir(byPath, "references");
        assertEquals("TEXT", byPath.get("references/guide.md").getKind());
        assertEquals(12L, byPath.get("references/guide.md").getSize().longValue());
        assertEquals(3, vo.getFiles().size());
    }

    @Test
    void listPackageFilesFallsBackToGzipMagicWhenFileNameSuffixUnknown() throws Exception {
        // 存量行的 packageFileName 可能没有可识别后缀，需按魔数判定真实格式
        byte[] bytes = tarGz(Map.of("SKILL.md", "# s"));
        SkillVO skill = ossZipSkill("custom-skill", bytes);

        SkillPackageFilesVO vo = service.listPackageFiles(skill);

        assertEquals("tar.gz", vo.getFormat());
        assertTrue(index(vo.getFiles()).containsKey("SKILL.md"));
    }

    // ------------------------------------------------------------------
    // FR-2 / AC-2：单文件在线预览
    // ------------------------------------------------------------------

    @Test
    void readPackageFileReturnsFullUtf8TextWithoutTruncation() throws Exception {
        String original = "# 技能说明\n" + "内容行内容行内容行内容行\n".repeat(3000);
        byte[] bytes = zip(Map.of("SKILL.md", text(original), "other.md", text("另一个文件")));
        SkillVO skill = ossZipSkill("custom-skill.zip", bytes);
        assertTrue(original.getBytes(StandardCharsets.UTF_8).length > 8192, "样本须超出嗅探窗口才能证明不截断");

        SkillPackageFileContentVO vo = service.readPackageFile(skill, "SKILL.md");

        assertEquals(original, vo.getContent());
        assertEquals("SKILL.md", vo.getPath());
        assertEquals("SKILL.md", vo.getFileName());
        assertEquals(Boolean.FALSE, vo.getBinary());
        // 单次预览只回源一次，且不会把同包其他文件的内容带出来
        verify(storage, times(1)).get(OSS_REF);
        assertFalse(vo.getContent().contains("另一个文件"));
    }

    @Test
    void readPackageFileNormalizesDotPrefixedRequestPath() throws Exception {
        byte[] bytes = zip(Map.of("references/guide.md", text("参考文档")));
        SkillVO skill = ossZipSkill("custom-skill.zip", bytes);

        SkillPackageFileContentVO vo = service.readPackageFile(skill, "./references/guide.md");

        assertEquals("参考文档", vo.getContent());
        assertEquals("references/guide.md", vo.getPath());
        assertEquals("guide.md", vo.getFileName());
    }

    @Test
    void readPackageFileReadsTarGzEntry() throws Exception {
        byte[] bytes = tarGz(Map.of("references/guide.md", "参考文档"));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        SkillPackageFileContentVO vo = service.readPackageFile(skill, "references/guide.md");

        assertEquals("参考文档", vo.getContent());
    }

    @Test
    void listAndReadTarGzWithPaxMetadataHeaderAndPathOverride() throws Exception {
        // Python tarfile 默认 PAX 格式：././@PaxHeader 携带 mtime 元数据；
        // 长名/非 ASCII 名经 path 记录覆盖，ustar 字段只留截断名
        byte[] meta = paxRecords(Map.of("mtime", "1760000000.123456789"));
        byte[] path = paxRecords(Map.of("path", "references/长文件名说明.md"));
        byte[] bytes = tarGzEntries(List.of(
                new TarEntry("././@PaxHeader", 'x', String.format("%011o", meta.length), meta),
                TarEntry.file("SKILL.md", "---\nname: pax-skill\n---\n# 说明\n"),
                new TarEntry("././@PaxHeader", 'x', String.format("%011o", path.length), path),
                TarEntry.file("truncated.md", "参考文档")));
        SkillVO skill = ossZipSkill("pax-skill.tar.gz", bytes);

        SkillPackageFilesVO vo = service.listPackageFiles(skill);
        Map<String, SkillPackageFileVO> byPath = index(vo.getFiles());
        assertEquals("tar.gz", vo.getFormat());
        assertNotNull(byPath.get("SKILL.md"));
        // path 记录生效：以真实路径列出，截断名与 PAX 头本身都不出现
        assertNotNull(byPath.get("references/长文件名说明.md"));
        assertNull(byPath.get("truncated.md"));
        assertNull(byPath.get("@PaxHeader"));

        SkillPackageFileContentVO content = service.readPackageFile(skill, "references/长文件名说明.md");
        assertEquals("参考文档", content.getContent());
    }

    @Test
    void readPackageFileReplacesInvalidUtf8BytesInsteadOfFailing() throws Exception {
        byte[] body = new byte[]{'#', ' ', 't', (byte) 0xc3, 0x28, '\n'};
        byte[] bytes = zip(Map.of("SKILL.md", body));
        SkillVO skill = ossZipSkill("custom-skill.zip", bytes);

        SkillPackageFileContentVO vo = service.readPackageFile(skill, "SKILL.md");

        assertTrue(vo.getContent().contains("\uFFFD"), "非法字节应以替换字符呈现，而不是让接口失败");
        assertEquals(new String(body, StandardCharsets.UTF_8), vo.getContent());
    }

    @Test
    void readPackageFileRejectsImageAndBinaryEntries() throws Exception {
        byte[] bytes = zip(Map.of(
                "assets/logo.png", binary(),
                "bin/tool", new byte[]{0x7f, 'E', 'L', 'F', 0x00}));
        SkillVO skill = ossZipSkill("custom-skill.zip", bytes);

        // 图片与二进制只暴露元信息，不下发内容
        BizException image = assertThrows(BizException.class, () -> service.readPackageFile(skill, "assets/logo.png"));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), image.getCode());
        assertEquals("该文件不支持在线预览", image.getMessage());

        BizException binary = assertThrows(BizException.class, () -> service.readPackageFile(skill, "bin/tool"));
        assertEquals("该文件不支持在线预览", binary.getMessage());
    }

    @Test
    void readPackageFileRejectsDirectoryAndMissingPaths() throws Exception {
        byte[] bytes = zip(Map.of("references/guide.md", text("参考文档")));
        SkillVO skill = ossZipSkill("custom-skill.zip", bytes);

        BizException dir = assertThrows(BizException.class, () -> service.readPackageFile(skill, "references"));
        assertTrue(dir.getMessage().contains("包内不存在该文件"));

        BizException missing = assertThrows(BizException.class, () -> service.readPackageFile(skill, "nope.md"));
        assertTrue(missing.getMessage().contains("包内不存在该文件"));
    }

    @Test
    void readPackageFileRejectsTraversalAbsoluteAndBackslashPathsBeforeTouchingStorage() {
        byte[] bytes = new byte[]{0x50, 0x4b, 0x03, 0x04};
        SkillVO skill = ossZipSkill("custom-skill.zip", bytes);

        List<String> rejected = List.of(
                "../SKILL.md",
                "references/../../SKILL.md",
                "/etc/passwd",
                "..",
                "references\\guide.md");

        for (String path : rejected) {
            BizException ex = assertThrows(BizException.class, () -> service.readPackageFile(skill, path),
                    "应拒绝路径: " + path);
            assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        }
        assertThrows(BizException.class, () -> service.readPackageFile(skill, "  "));
        // 非法路径不得触发任何 OSS 回源
        verify(storage, never()).get(any());
    }

    // ------------------------------------------------------------------
    // FR-3 / AC-3：原始包下载
    // ------------------------------------------------------------------

    @Test
    void loadPackageReturnsOriginalZipBytesAndFileName() throws Exception {
        byte[] bytes = zip(Map.of("SKILL.md", text("# s")));
        SkillVO skill = ossZipSkill("custom-skill.zip", bytes);

        SkillPackageService.PackageDownload download = service.loadPackage(skill);

        assertArrayEquals(bytes, download.bytes(), "下载必须是原始字节，不得重新打包");
        assertEquals("custom-skill.zip", download.fileName());
        assertEquals("zip", download.format());
    }

    @Test
    void loadPackageReturnsOriginalTarGzBytesAndFileName() throws Exception {
        byte[] bytes = tarGz(Map.of("SKILL.md", "# s"));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        SkillPackageService.PackageDownload download = service.loadPackage(skill);

        assertArrayEquals(bytes, download.bytes());
        assertEquals("custom-skill.tar.gz", download.fileName());
        assertEquals("tar.gz", download.format());
    }

    @Test
    void loadPackageFallsBackToOssRefFileNameWhenPackageFileNameBlank() throws Exception {
        byte[] bytes = zip(Map.of("SKILL.md", text("# s")));
        SkillVO skill = ossZipSkill("  ", bytes);

        SkillPackageService.PackageDownload download = service.loadPackage(skill);

        assertEquals("custom-skill.zip", download.fileName());
    }

    // ------------------------------------------------------------------
    // FR-4 / AC-4、AC-6：可见性与前置条件
    // ------------------------------------------------------------------

    @Test
    void readApisRejectSkillWithoutUploadedPackage() {
        SkillVO installSpec = new SkillVO();
        installSpec.setId(8L);
        installSpec.setType("SKILL");
        installSpec.setSourceType("INSTALL_SPEC");
        installSpec.setInstallSpec("{\"command\":\"npx\"}");

        assertNoPackage(installSpec);

        SkillVO blankRef = new SkillVO();
        blankRef.setId(9L);
        blankRef.setSourceType("OSS_ZIP");
        blankRef.setPackageOssRef("   ");
        assertNoPackage(blankRef);

        SkillVO nullSourceType = new SkillVO();
        nullSourceType.setId(10L);
        nullSourceType.setPackageOssRef(OSS_REF);
        assertNoPackage(nullSourceType);

        verify(storage, never()).get(any());
    }

    @Test
    void readApisRejectPackageMissingInStorage() {
        SkillVO skill = new SkillVO();
        skill.setId(11L);
        skill.setSourceType("OSS_ZIP");
        skill.setPackageOssRef(OSS_REF);
        skill.setPackageFileName("custom-skill.zip");
        when(storage.get(OSS_REF)).thenReturn(null);

        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.listPackageFiles(skill)).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.readPackageFile(skill, "SKILL.md")).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.loadPackage(skill)).getCode());
    }

    @Test
    void readApisRejectEmptyPackageBytes() throws Exception {
        SkillVO skill = ossZipSkill("custom-skill.zip", new byte[0]);

        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.listPackageFiles(skill)).getCode());
    }

    // ------------------------------------------------------------------
    // FR-5 / AC-5：复用上传侧限额
    // ------------------------------------------------------------------

    @Test
    void listPackageFilesRejectsTooManyEntries() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < TOO_MANY_ENTRIES; i++) {
            entries.put("f" + i + ".txt", text("x"));
        }
        SkillVO skill = ossZipSkill("custom-skill.zip", zip(entries));

        BizException ex = assertThrows(BizException.class, () -> service.listPackageFiles(skill));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("条目数超过上限"), ex.getMessage());
    }

    @Test
    void readPackageFileRejectsTooManyEntries() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < TOO_MANY_ENTRIES; i++) {
            entries.put("f" + i + ".txt", text("x"));
        }
        SkillVO skill = ossZipSkill("custom-skill.zip", zip(entries));

        // 目标不存在时读取器必须遍历全部条目，才会撞上条目数上限
        BizException ex = assertThrows(BizException.class, () -> service.readPackageFile(skill, "missing.txt"));
        assertTrue(ex.getMessage().contains("条目数超过上限"), ex.getMessage());
    }

    @Test
    void listPackageFilesRejectsOversizedInflatedContent() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            putZipEntry(zos, "SKILL.md", text("# s"));
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
        SkillVO skill = ossZipSkill("custom-skill.zip", baos.toByteArray());

        BizException ex = assertThrows(BizException.class, () -> service.listPackageFiles(skill));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("解压后大小超过上限"), ex.getMessage());
        assertEquals("The unpacked skill package exceeds the 100MB limit", com.aliyun.autowonder.common.i18n.SystemMessages.render(ex.getMessageKey(),ex.getMessageArgs(),ex.getMessage(),java.util.Locale.US));
    }

    // ------------------------------------------------------------------
    // 分类判定
    // ------------------------------------------------------------------

    @Test
    void resolveKindPrefersExtensionOverContentSniffing() {
        byte[] textBytes = text("plain").clone();
        byte[] binaryBytes = {0x00, 0x01, 0x02};

        assertEquals("TEXT", SkillPackageService.resolveKind("README.md", textBytes));
        assertEquals("IMAGE", SkillPackageService.resolveKind("assets/logo.PNG", textBytes), "扩展名判定应大小写不敏感");
        // .gitignore 这类点文件没有"主名.扩展名"结构，仍须按扩展名命中 TEXT
        assertEquals("TEXT", SkillPackageService.resolveKind(".gitignore", binaryBytes));
        assertEquals("TEXT", SkillPackageService.resolveKind("LICENSE", textBytes));
        assertEquals("BINARY", SkillPackageService.resolveKind("LICENSE", binaryBytes));
        assertEquals("TEXT", SkillPackageService.resolveKind("empty-file", new byte[0]), "空文件按文本处理");
    }

    @Test
    void resolveKindToleratesMultiByteCharacterTruncatedAtSniffWindowBoundary() {
        // 嗅探窗口正好把一个三字节汉字截断，不能因此把合法文本误判为二进制
        byte[] head = new byte[8192];
        java.util.Arrays.fill(head, 0, 8191, (byte) 'a');
        head[8191] = (byte) 0xe4;

        assertEquals("TEXT", SkillPackageService.resolveKind("notes", head));
    }

    @Test
    void resolveKindRejectsInvalidUtf8ForUnknownExtension() {
        byte[] head = new byte[]{'a', 'b', (byte) 0xc3, 0x28};

        assertEquals("BINARY", SkillPackageService.resolveKind("notes", head));
    }

    // ------------------------------------------------------------------
    // QA-B1：tar.gz 与 zip 同款的资源防护与条目形态
    // ------------------------------------------------------------------

    @Test
    void listPackageFilesRejectsTooManyTarGzEntries() throws Exception {
        List<TarEntry> entries = new ArrayList<>();
        for (int i = 0; i < TOO_MANY_ENTRIES; i++) {
            entries.add(TarEntry.file("f" + i + ".txt", "x"));
        }
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", tarGzEntries(entries));

        BizException ex = assertThrows(BizException.class, () -> service.listPackageFiles(skill));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("条目数超过上限"), ex.getMessage());
    }

    @Test
    void readPackageFileRejectsTooManyTarGzEntries() throws Exception {
        List<TarEntry> entries = new ArrayList<>();
        for (int i = 0; i < TOO_MANY_ENTRIES; i++) {
            entries.add(TarEntry.file("f" + i + ".txt", "x"));
        }
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", tarGzEntries(entries));

        BizException ex = assertThrows(BizException.class, () -> service.readPackageFile(skill, "missing.txt"));
        assertTrue(ex.getMessage().contains("条目数超过上限"), ex.getMessage());
    }

    @Test
    void listPackageFilesRejectsTarGzEntryDeclaringOversizedInflatedContent() throws Exception {
        byte[] bytes = tarGzEntries(List.of(
                TarEntry.declared("SKILL.md", SkillPackageService.MAX_PACKAGE_SIZE + 1L, text("# s"))));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        BizException ex = assertThrows(BizException.class, () -> service.listPackageFiles(skill));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("解压后大小超过上限"), ex.getMessage());
        assertEquals("The unpacked skill package exceeds the 100MB limit", com.aliyun.autowonder.common.i18n.SystemMessages.render(ex.getMessageKey(),ex.getMessageArgs(),ex.getMessage(),java.util.Locale.US));
    }

    @Test
    void listPackageFilesRejectsTarGzEntryWithNegativeDeclaredSize() throws Exception {
        // size 字段写成 "-1"：八进制解析得到负数，必须在消费 payload 之前被拒绝
        byte[] bytes = tarGzEntries(List.of(TarEntry.rawSize("SKILL.md", "-1", text("# s"))));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.listPackageFiles(skill)).getCode());
    }

    @Test
    void readPackageFileRejectsTarGzEntryWithOutOfRangeDeclaredSize() throws Exception {
        // 两个夹具必须用不同 ref：共用 OSS_REF 会让后一次 when(storage.get(...)) 静默覆盖前一次，两条断言都读到同一个包而假通过
        String oversizedRef = "skills/10002/oversized.tar.gz";
        String negativeRef = "skills/10002/negative.tar.gz";
        SkillVO oversized = ossZipSkill(oversizedRef, "custom-skill.tar.gz", tarGzEntries(List.of(
                TarEntry.declared("SKILL.md", SkillPackageService.MAX_PACKAGE_SIZE + 1L, text("# s")))));
        SkillVO negative = ossZipSkill(negativeRef, "custom-skill.tar.gz", tarGzEntries(List.of(
                TarEntry.rawSize("SKILL.md", "-1", text("# s")))));

        // invalid() 不带自定义消息，两个夹具抛出的异常无从区分，只能用 verify 证明各自读到自己的包
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.readPackageFile(oversized, "SKILL.md")).getCode());
        verify(storage).get(oversizedRef);
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.readPackageFile(negative, "SKILL.md")).getCode());
        verify(storage).get(negativeRef);
    }

    @Test
    void listPackageFilesRejectsTarGzSymlinkEntries() throws Exception {
        byte[] bytes = tarGzEntries(List.of(
                TarEntry.symlink("etc/passwd"), TarEntry.file("SKILL.md", "# s")));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.listPackageFiles(skill)).getCode());
    }

    @Test
    void readPackageFileRejectsTarGzSymlinkEntries() throws Exception {
        // 符号链接放在目标之前：读取器必须在遍历到目标前就中止，不能跟随链接
        byte[] bytes = tarGzEntries(List.of(
                TarEntry.symlink("etc/passwd"), TarEntry.file("SKILL.md", "# s")));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.readPackageFile(skill, "SKILL.md")).getCode());
    }

    @Test
    void listPackageFilesKeepsExplicitTarGzDirectoryEntriesWithoutDuplicating() throws Exception {
        byte[] bytes = tarGzEntries(List.of(
                TarEntry.directory("references"),
                TarEntry.file("references/guide.md", "参考文档"),
                TarEntry.file("SKILL.md", "# s")));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        SkillPackageFilesVO vo = service.listPackageFiles(skill);

        Map<String, SkillPackageFileVO> byPath = index(vo.getFiles());
        assertDir(byPath, "references");
        assertEquals(3, vo.getFiles().size());
        assertEquals(1, vo.getFiles().stream().filter(f -> "references".equals(f.getPath())).count(),
                "显式目录条目与隐式补出的目录不应重复");
    }

    @Test
    void readPackageFileSkipsTarGzDirectoryEntries() throws Exception {
        byte[] bytes = tarGzEntries(List.of(
                TarEntry.directory("references"),
                TarEntry.file("references/guide.md", "参考文档")));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        SkillPackageFileContentVO vo = service.readPackageFile(skill, "references/guide.md");

        assertEquals("参考文档", vo.getContent());
    }

    @Test
    void readPackageFileReadsNonFirstTarGzEntryAcrossPaddingBoundaries() throws Exception {
        // 1 / 513 / 1023 字节三种长度覆盖 512 对齐补位的三类边界，验证跳过逻辑不错位
        byte[] bytes = tarGzEntries(List.of(
                TarEntry.file("a.txt", "A"),
                TarEntry.file("b.txt", "B".repeat(513)),
                TarEntry.file("c.txt", "C".repeat(1023)),
                TarEntry.file("references/target.md", "# 目标文件\n")));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        SkillPackageFileContentVO vo = service.readPackageFile(skill, "references/target.md");

        assertEquals("# 目标文件\n", vo.getContent());
        assertEquals("references/target.md", vo.getPath());
        assertEquals("target.md", vo.getFileName());
        verify(storage, times(1)).get(OSS_REF);
    }

    @Test
    void readPackageFileRejectsMissingTarGzEntry() throws Exception {
        byte[] bytes = tarGzEntries(List.of(TarEntry.file("SKILL.md", "# s")));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        BizException ex = assertThrows(BizException.class, () -> service.readPackageFile(skill, "nope.md"));
        assertTrue(ex.getMessage().contains("包内不存在该文件"), ex.getMessage());
    }

    @Test
    void listPackageFilesSniffsUnknownExtensionEntriesInTarGz() throws Exception {
        byte[] bytes = tarGzEntries(List.of(
                TarEntry.file("notes", "没有扩展名的纯文本"),
                TarEntry.file("bin/tool", new byte[]{0x7f, 'E', 'L', 'F', 0x00, 0x01}),
                TarEntry.file("empty", new byte[0])));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        Map<String, SkillPackageFileVO> byPath = index(service.listPackageFiles(skill).getFiles());

        assertEquals("TEXT", byPath.get("notes").getKind());
        assertEquals("BINARY", byPath.get("bin/tool").getKind());
        assertEquals("TEXT", byPath.get("empty").getKind());
        assertEquals(0L, byPath.get("empty").getSize().longValue());
        assertDir(byPath, "bin");
    }

    // ------------------------------------------------------------------
    // QA-B2 / QA-B3：畸形包与规范化后为空的路径
    // ------------------------------------------------------------------

    @Test
    void readApisRejectEncryptedZipBytes() throws Exception {
        byte[] bytes = encryptedZip(zip(Map.of("SKILL.md", text("# s"))));
        SkillVO skill = ossZipSkill("custom-skill.zip", bytes);

        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.listPackageFiles(skill)).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.readPackageFile(skill, "SKILL.md")).getCode());
    }

    @Test
    void readApisRejectNonGzipBytesNamedTarGz() {
        byte[] bytes = new byte[]{0x50, 0x4b, 0x03, 0x04};
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.listPackageFiles(skill)).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.readPackageFile(skill, "SKILL.md")).getCode());
    }

    @Test
    void readPackageFileRejectsTarGzEntryWhosePayloadIsShorterThanDeclared() throws Exception {
        // 声明 4096 但实际只有 4 字节，流里剩余 1536 字节不足以补齐
        byte[] bytes = tarGzEntries(List.of(TarEntry.declared("SKILL.md", 4096L, text("# s"))));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.listPackageFiles(skill)).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.readPackageFile(skill, "SKILL.md")).getCode());
    }

    @Test
    void listPackageFilesSkipsEntriesWhoseNormalizedPathIsEmpty() throws Exception {
        ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBaos)) {
            putZipEntry(zos, "./", new byte[0]);
            putZipEntry(zos, "SKILL.md", text("# s"));
        }
        SkillPackageFilesVO zipVo = service.listPackageFiles(ossZipSkill("custom-skill.zip", zipBaos.toByteArray()));
        assertFalse(index(zipVo.getFiles()).containsKey(""), "空路径条目不得进入清单");
        assertEquals(1, zipVo.getFiles().size());

        byte[] tarBytes = tarGzEntries(List.of(TarEntry.directory("./"), TarEntry.file("SKILL.md", "# s")));
        SkillPackageFilesVO tarVo = service.listPackageFiles(ossZipSkill("custom-skill.tar.gz", tarBytes));
        assertFalse(index(tarVo.getFiles()).containsKey(""), "空路径条目不得进入清单");
        assertEquals(1, tarVo.getFiles().size());
    }

    @Test
    void readPackageFileRejectsPathsThatNormalizeToEmptyBeforeTouchingStorage() throws Exception {
        SkillVO skill = ossZipSkill("custom-skill.zip", zip(Map.of("SKILL.md", text("# s"))));

        for (String path : List.of("./", "././", " ./ ")) {
            BizException ex = assertThrows(BizException.class, () -> service.readPackageFile(skill, path),
                    "应拒绝路径: " + path);
            assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        }
        // 规范化后为空同样属于非法路径，不得触发 OSS 回源
        verify(storage, never()).get(any());
    }

    // ------------------------------------------------------------------
    // QA-B4 / QA-B5：控制字节分类与格式魔数回落
    // ------------------------------------------------------------------

    @Test
    void resolveKindClassifiesControlBytesForUnknownExtensions() {
        for (int control : new int[]{0x01, 0x08, 0x0e, 0x11, 0x1f}) {
            assertEquals("BINARY", SkillPackageService.resolveKind("notes", new byte[]{'a', (byte) control}),
                    "控制字节 0x" + Integer.toHexString(control) + " 应判为二进制");
        }
        for (int allowed : new int[]{0x09, 0x0a, 0x0d, 0x1b}) {
            assertEquals("TEXT", SkillPackageService.resolveKind("notes", new byte[]{'a', (byte) allowed}),
                    "白名单控制字节 0x" + Integer.toHexString(allowed) + " 应判为文本");
        }
        assertEquals("TEXT", SkillPackageService.resolveKind("notes", null), "无嗅探窗口时按文本处理");
    }

    @Test
    void loadPackageFallsBackToZipWhenMagicIsNotGzip() throws Exception {
        byte[] bytes = zip(Map.of("SKILL.md", text("# s")));
        SkillVO skill = ossZipSkill("custom-skill", bytes);

        SkillPackageService.PackageDownload download = service.loadPackage(skill);

        assertEquals("zip", download.format());
        assertArrayEquals(bytes, download.bytes());
    }

    @Test
    void loadPackageDetectsFormatByMagicWhenPackageFileNameMissing() throws Exception {
        byte[] tarBytes = tarGz(Map.of("SKILL.md", "# s"));
        byte[] zipBytes = zip(Map.of("SKILL.md", text("# s")));

        for (String fileName : new String[]{null, "  "}) {
            // ref 末段为空时回退出的文件名也是空白，只能靠魔数判定格式
            assertEquals("tar.gz", service.loadPackage(
                    ossZipSkill(REF_WITHOUT_NAME, fileName, tarBytes)).format(), "fileName=" + fileName);
            assertEquals("zip", service.loadPackage(
                    ossZipSkill(REF_WITHOUT_NAME, fileName, zipBytes)).format(), "fileName=" + fileName);
        }
    }

    @Test
    void loadPackageTreatsUndersizedBytesAsZipWithoutThrowing() {
        for (byte[] bytes : new byte[][]{{0x50}, {0x1f, 0x00}, {0x00, (byte) 0x8b}}) {
            SkillVO skill = ossZipSkill("custom-skill", bytes);
            assertEquals("zip", service.loadPackage(skill).format(), "魔数不足两位时不得抛异常");
        }
    }

    // ------------------------------------------------------------------
    // QA-B6：前置守卫
    // ------------------------------------------------------------------

    @Test
    void readApisRejectNullSkill() {
        assertNoPackage(null);
        verify(storage, never()).get(any());
    }

    @Test
    void readApisRejectStoredObjectLargerThanLimit() {
        byte[] bytes = new byte[(int) SkillPackageService.MAX_PACKAGE_SIZE + 1];
        SkillVO skill = new SkillVO();
        skill.setId(12L);
        skill.setSourceType("OSS_ZIP");
        skill.setPackageOssRef(OSS_REF);
        skill.setPackageFileName("custom-skill.zip");
        when(storage.get(OSS_REF)).thenReturn(bytes);

        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.listPackageFiles(skill)).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.readPackageFile(skill, "SKILL.md")).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.loadPackage(skill)).getCode());
    }

    // ------------------------------------------------------------------
    // QA-N1：zip 侧零散缺口；CR NB-3：读取路径的全局解压上限
    // ------------------------------------------------------------------

    @Test
    void readPackageFileSkipsExplicitZipDirectoryEntries() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            putZipEntry(zos, "references/", new byte[0]);
            putZipEntry(zos, "references/guide.md", text("参考文档"));
        }
        SkillVO skill = ossZipSkill("custom-skill.zip", baos.toByteArray());

        SkillPackageFileContentVO vo = service.readPackageFile(skill, "references/guide.md");

        assertEquals("参考文档", vo.getContent());
    }

    @Test
    void addDirectoryIgnoresNullAndEmptyPaths() {
        List<SkillPackageFileVO> files = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();

        SkillPackageService.addDirectory(files, listed, null);
        SkillPackageService.addDirectory(files, listed, "");
        assertTrue(files.isEmpty(), "空目录路径不应产出记录");
        assertTrue(listed.isEmpty());

        SkillPackageService.addDirectory(files, listed, "a/b");
        assertEquals(List.of("a", "a/b"), paths(files));

        // 已登记的祖先不再重复产出，只补新增的一层
        SkillPackageService.addDirectory(files, listed, "a/b/c");
        assertEquals(List.of("a", "a/b", "a/b/c"), paths(files));
        assertTrue(files.stream().allMatch(SkillPackageFileVO::getDir));
        assertTrue(files.stream().allMatch(f -> "DIR".equals(f.getKind())));
    }

    @Test
    void readPackageFileRejectsZipWhoseSkippedEntriesExceedInflatedLimit() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            putZipEntry(zos, "SKILL.md", text("# s"));
            putZipZeros(zos, "assets/large.bin", SkillPackageService.MAX_PACKAGE_SIZE + 1L);
            putZipEntry(zos, "target.txt", text("目标"));
        }
        SkillVO skill = ossZipSkill("custom-skill.zip", baos.toByteArray());

        // 目标在被跳过的超大条目之后：跳过路径同样要计入全局解压总量
        BizException ex = assertThrows(BizException.class, () -> service.readPackageFile(skill, "target.txt"));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("解压后大小超过上限"), ex.getMessage());
        assertEquals("The unpacked skill package exceeds the 100MB limit", com.aliyun.autowonder.common.i18n.SystemMessages.render(ex.getMessageKey(),ex.getMessageArgs(),ex.getMessage(),java.util.Locale.US));
    }

    @Test
    void readPackageFileRejectsZipWhenTargetEntryPushesTotalOverInflatedLimit() throws Exception {
        // 各占全局上限的 3/5：单条目都不触发 readEntryBytes 的上限，累加后才越全局上限
        long share = SkillPackageService.MAX_PACKAGE_SIZE * 3 / 5;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            putZipZeros(zos, "assets/skipped.bin", share);
            putZipZeros(zos, "target.txt", share);
        }
        SkillVO skill = ossZipSkill("custom-skill.zip", baos.toByteArray());

        BizException ex = assertThrows(BizException.class, () -> service.readPackageFile(skill, "target.txt"));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("解压后大小超过上限"), ex.getMessage());
        assertEquals("The unpacked skill package exceeds the 100MB limit", com.aliyun.autowonder.common.i18n.SystemMessages.render(ex.getMessageKey(),ex.getMessageArgs(),ex.getMessage(),java.util.Locale.US));
    }

    /** 分块写入零填充条目：构造上百 MB 的 fixture 时不必单独申请等长大数组。 */
    private static void putZipZeros(ZipOutputStream zos, String name, long size) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        byte[] chunk = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int len = (int) Math.min(chunk.length, remaining);
            zos.write(chunk, 0, len);
            remaining -= len;
        }
        zos.closeEntry();
    }

    @Test
    void readPackageFileRejectsTarGzWhoseSkippedEntriesExceedInflatedLimit() throws Exception {
        byte[] bytes = tarGzEntries(List.of(
                TarEntry.file("a.txt", "A"),
                TarEntry.declared("b.bin", SkillPackageService.MAX_PACKAGE_SIZE, text("B")),
                TarEntry.file("target.txt", "目标")));
        SkillVO skill = ossZipSkill("custom-skill.tar.gz", bytes);

        BizException ex = assertThrows(BizException.class, () -> service.readPackageFile(skill, "target.txt"));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("解压后大小超过上限"), ex.getMessage());
        assertEquals("The unpacked skill package exceeds the 100MB limit", com.aliyun.autowonder.common.i18n.SystemMessages.render(ex.getMessageKey(),ex.getMessageArgs(),ex.getMessage(),java.util.Locale.US));
    }

    private static List<String> paths(List<SkillPackageFileVO> files) {
        return files.stream().map(SkillPackageFileVO::getPath).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private void assertNoPackage(SkillVO skill) {
        BizException files = assertThrows(BizException.class, () -> service.listPackageFiles(skill));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), files.getCode());
        assertEquals("该技能无上传包", files.getMessage());

        assertEquals("该技能无上传包",
                assertThrows(BizException.class, () -> service.readPackageFile(skill, "SKILL.md")).getMessage());
        assertEquals("该技能无上传包",
                assertThrows(BizException.class, () -> service.loadPackage(skill)).getMessage());
    }

    private SkillVO ossZipSkill(String fileName, byte[] bytes) {
        return ossZipSkill(OSS_REF, fileName, bytes);
    }

    private SkillVO ossZipSkill(String ossRef, String fileName, byte[] bytes) {
        SkillVO skill = new SkillVO();
        skill.setId(7L);
        skill.setType("SKILL");
        skill.setName("custom-skill");
        skill.setSourceType("OSS_ZIP");
        skill.setPackageOssRef(ossRef);
        skill.setPackageFileName(fileName);
        skill.setPackageSize((long) bytes.length);
        when(storage.get(ossRef)).thenReturn(bytes);
        return skill;
    }

    private static void assertDir(Map<String, SkillPackageFileVO> byPath, String path) {
        SkillPackageFileVO dir = byPath.get(path);
        assertNotNull(dir, "缺少目录记录: " + path);
        assertTrue(dir.getDir());
        assertEquals("DIR", dir.getKind());
        assertEquals(0L, dir.getSize().longValue());
        assertEquals(path.substring(path.lastIndexOf('/') + 1), dir.getName());
    }

    private static Map<String, SkillPackageFileVO> index(List<SkillPackageFileVO> files) {
        return files.stream().collect(Collectors.toMap(
                SkillPackageFileVO::getPath, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** PNG 魔数 + 若干不可打印字节，既能被扩展名判定也能被嗅探判定为二进制。 */
    private static byte[] binary() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01, 0x02};
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                putZipEntry(zos, entry.getKey(), entry.getValue());
            }
        }
        return baos.toByteArray();
    }

    private static void putZipEntry(ZipOutputStream zos, String name, byte[] content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content);
        zos.closeEntry();
    }

    private static byte[] tarGz(Map<String, String> entries) throws Exception {
        List<TarEntry> list = new ArrayList<>(entries.size());
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            list.add(TarEntry.file(entry.getKey(), entry.getValue()));
        }
        return tarGzEntries(list);
    }

    private static byte[] tarGzEntries(List<TarEntry> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            for (TarEntry entry : entries) {
                byte[] header = new byte[512];
                writeTarString(header, 0, 100, entry.name);
                writeTarString(header, 100, 8, "0000777");
                writeTarString(header, 108, 8, "0000000");
                writeTarString(header, 116, 8, "0000000");
                writeTarString(header, 124, 12, entry.sizeField);
                writeTarString(header, 136, 12, "00000000000");
                for (int i = 148; i < 156; i++) {
                    header[i] = ' ';
                }
                header[156] = (byte) entry.type;
                writeTarString(header, 257, 6, "ustar");
                int checksum = 0;
                for (byte value : header) {
                    checksum += value & 0xff;
                }
                writeTarString(header, 148, 8, String.format("%06o ", checksum));
                gos.write(header);
                gos.write(entry.content);
                gos.write(new byte[(int) ((512 - (entry.content.length % 512)) % 512)]);
            }
            gos.write(new byte[1024]);
        }
        return baos.toByteArray();
    }

    private static void writeTarString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
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

    /** 把 zip 通用标志位置为"已加密"，ZipInputStream 会确定性地抛 ZipException。 */
    private static byte[] encryptedZip(byte[] zipBytes) {
        byte[] tampered = zipBytes.clone();
        tampered[6] = (byte) (tampered[6] | 0x01);
        return tampered;
    }

    /** tar 条目描述：允许声明与真实 payload 不一致的 size、目录与符号链接类型，用来构造畸形包。 */
    private static final class TarEntry {
        private final String name;
        private final char type;
        private final String sizeField;
        private final byte[] content;

        private TarEntry(String name, char type, String sizeField, byte[] content) {
            this.name = name;
            this.type = type;
            this.sizeField = sizeField;
            this.content = content;
        }

        static TarEntry file(String name, String content) {
            return file(name, content.getBytes(StandardCharsets.UTF_8));
        }

        static TarEntry file(String name, byte[] content) {
            return new TarEntry(name, '0', octalSize(content.length), content);
        }

        static TarEntry directory(String name) {
            return new TarEntry(name, '5', octalSize(0), new byte[0]);
        }

        static TarEntry symlink(String name) {
            return new TarEntry(name, '2', octalSize(0), new byte[0]);
        }

        /** 声明的 size 与真实 payload 不一致，用于构造超限与截断包。 */
        static TarEntry declared(String name, long declaredSize, byte[] content) {
            return new TarEntry(name, '0', octalSize(declaredSize), content);
        }

        /** 直接写 size 字段的原始文本，用于构造负数等无法用八进制表达的畸形头。 */
        static TarEntry rawSize(String name, String sizeField, byte[] content) {
            return new TarEntry(name, '0', sizeField, content);
        }

        private static String octalSize(long size) {
            return String.format("%011o", size);
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"missing,'包内不存在该文件: <raw>.txt','The package does not contain this file: <raw>.txt'", "binary,'该文件不支持在线预览','This file does not support online preview'", "package,'该技能无上传包','This skill has no uploaded package'", "entries,'技能包条目数超过上限 500','The skill package exceeds the limit of 500 entries'"})
    void packageOwnedFailureKeepsRawArchiveContract(String branch, String raw, String en) throws Exception {
        Map<String,byte[]> entries = new LinkedHashMap<>(); entries.put("SKILL.md",text("# raw")); entries.put("binary.png",binary());
        if(branch.equals("entries")) for(int i=0;i<500;i++) entries.put("raw-"+i+".txt",text("raw"));
        SkillVO skill = branch.equals("package") ? new SkillVO() : ossZipSkill("raw.zip",zip(entries));
        BizException error = assertThrows(BizException.class, () -> {
            switch(branch) { case "missing" -> service.readPackageFile(skill,"<raw>.txt");
                case "binary" -> service.readPackageFile(skill,"binary.png"); default -> service.listPackageFiles(skill); }
        });
        assertEquals(raw,error.getMessage());assertEquals(BizException.class,error.getClass());assertNull(error.getCause());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),error.getCode());
        assertEquals(en,com.aliyun.autowonder.common.i18n.SystemMessages.render(error.getMessageKey(),error.getMessageArgs(),raw,java.util.Locale.US));
    }

}
