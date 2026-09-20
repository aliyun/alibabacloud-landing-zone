package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.category.CategoryService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.SkillPackageFileContentVO;
import com.aliyun.autowonder.skill.dto.SkillPackageFilesVO;
import com.aliyun.autowonder.skill.dto.SkillVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 覆盖 SkillController 三个包内容端点的透传与下载响应组装（Content-Disposition、媒体类型、错误体与状态码映射）。
 */
class SkillControllerPackageContentTest {

    private static final byte[] PACKAGE_BYTES = {0x50, 0x4b, 0x03, 0x04, 0x05};

    private SkillService skillService;
    private SkillPackageService skillPackageService;
    private SkillController controller;
    private SkillVO skill;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        skillPackageService = mock(SkillPackageService.class);
        controller = new SkillController(skillService, skillPackageService, mock(SkillConnectionTestService.class),
                mock(CategoryService.class));
        skill = new SkillVO();
        skill.setId(7L);
        skill.setSourceType("OSS_ZIP");
        skill.setPackageOssRef("skills/10002/custom-skill.zip");
        when(skillService.get(7L)).thenReturn(skill);
    }

    @Test
    void packageFilesDelegatesToServiceWithResolvedSkill() {
        SkillPackageFilesVO files = new SkillPackageFilesVO(List.of(), "zip");
        when(skillPackageService.listPackageFiles(skill)).thenReturn(files);

        assertSame(files, controller.packageFiles(7L).getData());
        verify(skillPackageService).listPackageFiles(skill);
    }

    @Test
    void packageFilePassesPathThroughToService() {
        SkillPackageFileContentVO content = new SkillPackageFileContentVO("SKILL.md", "SKILL.md", "# s");
        when(skillPackageService.readPackageFile(skill, "references/guide.md")).thenReturn(content);

        assertSame(content, controller.packageFile(7L, "references/guide.md").getData());
        verify(skillPackageService).readPackageFile(eq(skill), eq("references/guide.md"));
    }

    @Test
    void downloadPackageReturnsOriginalBytesAsZipAttachment() {
        when(skillPackageService.loadPackage(skill))
                .thenReturn(new SkillPackageService.PackageDownload("custom-skill.zip", "zip", PACKAGE_BYTES));

        ResponseEntity<byte[]> response = controller.downloadPackage(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(PACKAGE_BYTES, response.getBody());
        assertEquals("application/zip", response.getHeaders().getContentType().toString());
        assertEquals("attachment", response.getHeaders().getContentDisposition().getType());
        assertEquals("custom-skill.zip", response.getHeaders().getContentDisposition().getFilename());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    }

    @Test
    void downloadPackageUsesGzipMediaTypeForTarGz() {
        when(skillPackageService.loadPackage(skill))
                .thenReturn(new SkillPackageService.PackageDownload("custom-skill.tar.gz", "tar.gz", PACKAGE_BYTES));

        ResponseEntity<byte[]> response = controller.downloadPackage(7L);

        assertEquals("application/gzip", response.getHeaders().getContentType().toString());
        assertEquals("custom-skill.tar.gz", response.getHeaders().getContentDisposition().getFilename());
    }

    @Test
    void downloadPackageEncodesNonAsciiFileName() {
        when(skillPackageService.loadPackage(skill))
                .thenReturn(new SkillPackageService.PackageDownload("技能包.zip", "zip", PACKAGE_BYTES));

        ResponseEntity<byte[]> response = controller.downloadPackage(7L);

        String disposition = response.getHeaders().getContentDisposition().toString();
        // 中文名等非 ASCII 文件名须按 RFC 5987 走 filename*，不能直接塞进 header
        assertTrue(disposition.contains("filename*="), disposition);
        assertFalse(disposition.contains("技能包"), disposition);
        assertEquals("技能包.zip", response.getHeaders().getContentDisposition().getFilename());
    }

    @Test
    void downloadPackageMapsBusinessErrorsToJsonBodyWithMatchingStatus() {
        assertDownloadError(ErrorCode.SKILL_NOT_FOUND, HttpStatus.NOT_FOUND);
        assertDownloadError(ErrorCode.PARAM_INVALID, HttpStatus.BAD_REQUEST);
        // 未登录由 @Around 切面在方法体之外抛出，进不了 downloadPackage 的 catch，
        // 所以这里不再为 UNAUTHORIZED 单开分支，落到兜底即可（真实响应由 GlobalExceptionHandler 给出）。
        assertDownloadError(ErrorCode.UNAUTHORIZED, HttpStatus.BAD_REQUEST);
    }

    private void assertDownloadError(ErrorCode code, HttpStatus expected) {
        when(skillPackageService.loadPackage(any())).thenThrow(new BizException(code, "出错了"));

        ResponseEntity<byte[]> response = controller.downloadPackage(7L);

        assertEquals(expected, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().getContentType().toString());
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains(code.getCode()), body);
        assertTrue(body.contains("出错了"), body);
        assertTrue(body.contains("\"success\":false"), body);

        reset(skillPackageService);
    }
}
