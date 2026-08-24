package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看工作项文档")
public class ArtifactController {

    private final ArtifactService artifactService;
    private final RequirementDocumentService requirementDocumentService;

    public ArtifactController(ArtifactService artifactService,
                              RequirementDocumentService requirementDocumentService) {
        this.artifactService = artifactService;
        this.requirementDocumentService = requirementDocumentService;
    }

    @GetMapping("/workitems/{id}/artifacts")
    public Result<List<ArtifactVO>> listByWorkitem(@PathVariable("id") Long id) {
        return Result.ok(artifactService.listByWorkitem(id, currentWorkspaceId()));
    }

    @GetMapping("/workitems/{id}/requirement-documents")
    public Result<List<ArtifactVO>> listRequirementDocuments(@PathVariable("id") Long id) {
        return Result.ok(requirementDocumentService.list(id, currentWorkspaceId()));
    }

    @PostMapping("/workitems/{id}/requirement-documents")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "上传需求文档")
    public Result<List<ArtifactVO>> uploadRequirementDocuments(@PathVariable("id") Long id,
                                                               @RequestParam("files") MultipartFile[] files) throws Exception {
        return Result.ok(requirementDocumentService.uploadWeb(id, files, currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/workitems/{id}/requirement-documents/{artifactId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除需求文档")
    public Result<Boolean> deleteRequirementDocument(@PathVariable("id") Long id,
                                                     @PathVariable("artifactId") Long artifactId) {
        requirementDocumentService.delete(id, artifactId, currentWorkspaceId(), currentUserId());
        return Result.ok(true);
    }

    @GetMapping("/artifacts/{id}/download")
    public Result<String> download(@PathVariable("id") Long id) {
        return Result.ok(artifactService.getDownloadUrl(id, currentWorkspaceId()));
    }

    @GetMapping("/artifacts/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable("id") Long id) {
        try {
            ArtifactService.PreviewContent content = artifactService.getPreviewContent(id, currentWorkspaceId());
            return ResponseEntity.ok()
                    .contentType(contentType(content.getName()))
                    .header("X-Content-Type-Options", "nosniff")
                    .body(content.getBytes());
        } catch (BizException ex) {
            return ResponseEntity.status(statusFor(ex.getCode()))
                    .contentType(MediaType.TEXT_PLAIN)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(ex.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }

    private long currentUserId() {
        Long userId = AutoWonderContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private MediaType contentType(String name) {
        String ext = extension(name);
        switch (ext) {
            case "md":
            case "markdown":
                return MediaType.valueOf("text/markdown;charset=UTF-8");
            case "json":
                return MediaType.APPLICATION_JSON;
            case "jsonl":
                return MediaType.valueOf("application/x-ndjson;charset=UTF-8");
            case "csv":
                return MediaType.valueOf("text/csv;charset=UTF-8");
            case "txt":
            case "log":
                return MediaType.TEXT_PLAIN;
            case "png":
                return MediaType.IMAGE_PNG;
            case "jpg":
            case "jpeg":
                return MediaType.IMAGE_JPEG;
            case "gif":
                return MediaType.IMAGE_GIF;
            case "webp":
                return MediaType.valueOf("image/webp");
            case "mp4":
            case "m4v":
                return MediaType.valueOf("video/mp4");
            case "webm":
                return MediaType.valueOf("video/webm");
            case "ogg":
            case "ogv":
                return MediaType.valueOf("video/ogg");
            case "mov":
                return MediaType.valueOf("video/quicktime");
            default:
                return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private HttpStatus statusFor(String code) {
        if (ErrorCode.UNAUTHORIZED.getCode().equals(code)) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (ErrorCode.ARTIFACT_NOT_FOUND.getCode().equals(code)) {
            return HttpStatus.NOT_FOUND;
        }
        return HttpStatus.BAD_REQUEST;
    }

    private String extension(String name) {
        if (name == null) {
            return "";
        }
        int query = name.indexOf('?');
        String clean = query >= 0 ? name.substring(0, query) : name;
        int hash = clean.indexOf('#');
        clean = hash >= 0 ? clean.substring(0, hash) : clean;
        int dot = clean.lastIndexOf('.');
        return dot >= 0 ? clean.substring(dot + 1).toLowerCase() : "";
    }
}
