package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.category.CategoryService;
import com.aliyun.autowonder.category.dto.BatchSkillCategoryResultVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.skill.dto.CreateSkillRequest;
import com.aliyun.autowonder.skill.dto.SkillConnectionTestVO;
import com.aliyun.autowonder.skill.dto.SkillPackageFileContentVO;
import com.aliyun.autowonder.skill.dto.SkillPackageFilesVO;
import com.aliyun.autowonder.skill.dto.SkillPackageInspectVO;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.skill.dto.UpdateSkillRequest;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/skills")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看技能")
public class SkillController {

    private final SkillService skillService;
    private final SkillPackageService skillPackageService;
    private final SkillConnectionTestService skillConnectionTestService;
    private final CategoryService categoryService;

    public SkillController(SkillService skillService, SkillPackageService skillPackageService,
                           SkillConnectionTestService skillConnectionTestService,
                           CategoryService categoryService) {
        this.skillService = skillService;
        this.skillPackageService = skillPackageService;
        this.skillConnectionTestService = skillConnectionTestService;
        this.categoryService = categoryService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建技能")
    public Result<SkillVO> create(@RequestBody CreateSkillRequest req) {
        return Result.ok(skillService.create(req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/package/inspect")
    public Result<SkillPackageInspectVO> inspectPackage(@RequestParam("file") MultipartFile file) {
        return Result.ok(skillPackageService.inspect(file));
    }

    @PostMapping("/package")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "从技能包创建技能")
    public Result<SkillVO> createFromPackage(@RequestParam("file") MultipartFile file,
			@RequestParam(value = "type", defaultValue = "SKILL") String type,
			@RequestParam(value = "name", required = false) String name,
			@RequestParam(value = "description", required = false) String description,
			@RequestParam(value = "providers", required = false) List<String> providers) {
        return Result.ok(skillPackageService.createFromPackage(file, type, name, description, providers, currentWorkspaceId(), currentUserId()));
    }

    @GetMapping("/{id}")
    public Result<SkillVO> get(@PathVariable("id") Long id) {
        return Result.ok(skillService.get(id));
    }

    // 以下三个包内容端点不加方法级 @RequireWorkspaceAccess：
    // 刻意继承类级 READ_ONLY，使"能看到技能详情"与"能看到包内容"的可见性完全一致。

    @GetMapping("/{id}/package/files")
    public Result<SkillPackageFilesVO> packageFiles(@PathVariable("id") Long id) {
        return Result.ok(skillPackageService.listPackageFiles(skillService.get(id)));
    }

    @GetMapping("/{id}/package/file")
    public Result<SkillPackageFileContentVO> packageFile(@PathVariable("id") Long id,
            @RequestParam("path") String path) {
        return Result.ok(skillPackageService.readPackageFile(skillService.get(id), path));
    }

    @GetMapping("/{id}/package/download")
    public ResponseEntity<byte[]> downloadPackage(@PathVariable("id") Long id) {
        try {
            SkillPackageService.PackageDownload download = skillPackageService.loadPackage(skillService.get(id));
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(download.fileName(), StandardCharsets.UTF_8)
                    .build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(disposition);
            headers.setContentType(packageMediaType(download.format()));
            headers.set("X-Content-Type-Options", "nosniff");
            return new ResponseEntity<>(download.bytes(), headers, HttpStatus.OK);
        } catch (BizException ex) {
            // 响应体是裸字节而非 Result，全局异常处理器给出的 JSON 拿不到正确的 Content-Type，
            // 前端用原生 fetch 下载时必须能读到 message，所以这里自己组装错误体。
            byte[] body = JSON.toJSONString(Result.fail(ex.getCode(), ex.getMessage()))
                    .getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(downloadStatusFor(ex.getCode()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(body);
        }
    }

    private static MediaType packageMediaType(String format) {
        return SkillPackageService.FORMAT_TAR_GZ.equals(format)
                ? MediaType.valueOf("application/gzip")
                : MediaType.valueOf("application/zip");
    }

    private static HttpStatus downloadStatusFor(String code) {
        if (ErrorCode.SKILL_NOT_FOUND.getCode().equals(code)) {
            return HttpStatus.NOT_FOUND;
        }
        // 未登录/越权由 @Around 切面在方法体之外抛出，进不了 downloadPackage 的 catch，
        // 因此这里无需处理 UNAUTHORIZED——它由 GlobalExceptionHandler 统一响应。
        return HttpStatus.BAD_REQUEST;
    }

    @PostMapping("/{id}/connection-test")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "测试技能连接")
    public Result<SkillConnectionTestVO> testConnection(@PathVariable("id") Long id,
            @RequestParam(value = "executorId", required = false) Long executorId) {
        return Result.ok(skillConnectionTestService.test(id, currentWorkspaceId(), executorId));
    }

    @GetMapping
    public Result<PageResult<SkillVO>> list(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "includeDescendants", defaultValue = "true") boolean includeDescendants,
            @RequestParam(value = "uncategorized", defaultValue = "false") boolean uncategorized,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(skillService.listPage(currentWorkspaceId(), type, categoryId,
                includeDescendants, uncategorized, page, size));
    }

    // 打标是独立于技能内容的关联操作：请求体必须显式携带 categoryId 字段，
    // JSON null 表示取消打标，字段缺失视为参数错误（与 MCP set_skill_category 语义一致）
    @PutMapping("/{id}/category")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "设置能力分类")
    public Result<Long> setCategory(@PathVariable("id") Long id, @RequestBody JsonNode body) {
        if (body == null || !body.has("categoryId")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 categoryId 参数");
        }
        Long categoryId = requireCategoryId(body);
        return Result.ok(categoryService.setSkillCategory(id, categoryId, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/category/batch")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "批量设置能力分类")
    public Result<List<BatchSkillCategoryResultVO>> batchSetCategory(@RequestBody JsonNode body) {
        if (body == null || !body.has("skillIds") || !body.get("skillIds").isArray()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 skillIds 参数");
        }
        List<Long> skillIds = new ArrayList<>();
        for (JsonNode node : body.get("skillIds")) {
            if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "skillIds 必须是数字数组");
            }
            skillIds.add(node.asLong());
        }
        Long categoryId = requireCategoryId(body);
        return Result.ok(categoryService.batchSetSkillCategory(skillIds, categoryId,
                currentWorkspaceId(), currentUserId()));
    }

    private Long requireCategoryId(JsonNode body) {
        if (body == null || !body.has("categoryId")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 categoryId 参数");
        }
        JsonNode node = body.get("categoryId");
        if (node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "categoryId 必须是正整数或 null");
        }
        return node.asLong();
    }

    @PutMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新技能")
    public Result<SkillVO> update(@PathVariable("id") Long id, @RequestBody UpdateSkillRequest req) {
        return Result.ok(skillService.update(id, req, currentWorkspaceId(), currentUserId()));
    }

    @PutMapping("/{id}/package")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新技能包")
    public Result<SkillVO> updatePackage(@PathVariable("id") Long id, @RequestParam("file") MultipartFile file,
			@RequestParam(value = "name", required = false) String name,
			@RequestParam(value = "description", required = false) String description,
			@RequestParam(value = "providers", required = false) List<String> providers) {
        return Result.ok(skillPackageService.updatePackage(id, file, name, description, providers, currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除技能")
    public Result<Void> delete(@PathVariable("id") Long id) {
        skillService.delete(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}
