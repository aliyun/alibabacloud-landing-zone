package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.mcp.WorkitemCliUploadTokenService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDao;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Upload-only endpoint for the {@code autowonder scheduled-task upload} CLI command.
 * Bypassed by the session AuthFilter (exact POST path only); authentication uses
 * the scoped awupload_ token minted by {@code workitem_cli_upload_token} and every
 * request re-checks live write membership in the task's workspace.
 */
@RestController
@RequestMapping("/api/cli")
public class ScheduledTaskCliUploadController {

    private final WorkitemCliUploadTokenService tokenService;
    private final ScheduledTaskDao scheduledTaskDao;
    private final RequirementDocumentService requirementDocumentService;

    public ScheduledTaskCliUploadController(WorkitemCliUploadTokenService tokenService,
                                            ScheduledTaskDao scheduledTaskDao,
                                            RequirementDocumentService requirementDocumentService) {
        this.tokenService = tokenService;
        this.scheduledTaskDao = scheduledTaskDao;
        this.requirementDocumentService = requirementDocumentService;
    }

    @PostMapping(value = "/scheduled-tasks/{taskId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<List<ArtifactVO>>> upload(
            @PathVariable("taskId") long taskId,
            @RequestParam("files") MultipartFile[] files,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            long userId = authenticate(authorization);
            ScheduledTaskDO task = scheduledTaskDao.findAnyById(taskId);
            if (task == null || task.getWorkspaceId() == null) {
                throw new BizException(ErrorCode.SCHEDULED_TASK_NOT_FOUND);
            }
            tokenService.requireWriteMembership(task.getWorkspaceId(), userId);
            return ResponseEntity.ok(Result.ok(
                    requirementDocumentService.uploadCli(
                            new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, taskId),
                            files, task.getWorkspaceId(), userId)));
        } catch (BizException ex) {
            return ResponseEntity.status(statusFor(ex.getCode()))
                    .body(Result.fail(ex.getCode(), ex.getMessage()));
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.fail(ErrorCode.PARAM_INVALID));
        }
    }

    private long authenticate(String authorization) {
        String scheme = "Bearer ";
        if (authorization == null || authorization.length() < scheme.length()
                || !authorization.regionMatches(true, 0, scheme, 0, scheme.length())) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return tokenService.authenticate(authorization.substring(scheme.length()).trim());
    }

    private HttpStatus statusFor(String code) {
        if (ErrorCode.UNAUTHORIZED.getCode().equals(code)) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (ErrorCode.NO_PERMISSION.getCode().equals(code)
                || ErrorCode.WORKSPACE_NOT_MEMBER.getCode().equals(code)) {
            return HttpStatus.FORBIDDEN;
        }
        if (ErrorCode.SCHEDULED_TASK_NOT_FOUND.getCode().equals(code)
                || ErrorCode.ARTIFACT_NOT_FOUND.getCode().equals(code)) {
            return HttpStatus.NOT_FOUND;
        }
        if (ErrorCode.CONFLICT.getCode().equals(code)
                || ErrorCode.SCHEDULED_TASK_INVALID_STATE.getCode().equals(code)) {
            return HttpStatus.CONFLICT;
        }
        if (ErrorCode.PARAM_INVALID.getCode().equals(code)) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
