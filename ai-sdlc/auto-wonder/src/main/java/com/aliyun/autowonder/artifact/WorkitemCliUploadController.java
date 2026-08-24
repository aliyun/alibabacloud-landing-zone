package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.mcp.WorkitemCliUploadTokenService;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
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
 * Upload-only endpoint for the {@code autowonder workitem upload} CLI command.
 * Bypassed by the session AuthFilter (exact POST path only); authentication uses
 * the scoped awupload_ token and every request re-checks live write membership.
 */
@RestController
@RequestMapping("/api/cli")
public class WorkitemCliUploadController {

    private final WorkitemCliUploadTokenService tokenService;
    private final WorkitemDao workitemDao;
    private final RequirementDocumentService requirementDocumentService;

    public WorkitemCliUploadController(WorkitemCliUploadTokenService tokenService,
                                       WorkitemDao workitemDao,
                                       RequirementDocumentService requirementDocumentService) {
        this.tokenService = tokenService;
        this.workitemDao = workitemDao;
        this.requirementDocumentService = requirementDocumentService;
    }

    @PostMapping(value = "/workitems/{workitemId}/requirement-documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<List<ArtifactVO>>> upload(
            @PathVariable("workitemId") long workitemId,
            @RequestParam("files") MultipartFile[] files,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            long userId = authenticate(authorization);
            WorkitemDO workitem = workitemDao.findById(workitemId);
            if (workitem == null || workitem.getTenantId() == null) {
                throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
            }
            tokenService.requireWriteMembership(workitem.getTenantId(), userId);
            return ResponseEntity.ok(Result.ok(
                    requirementDocumentService.uploadCli(workitemId, files,
                            workitem.getTenantId(), userId)));
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
        if (ErrorCode.WORKITEM_NOT_FOUND.getCode().equals(code)
                || ErrorCode.ARTIFACT_NOT_FOUND.getCode().equals(code)) {
            return HttpStatus.NOT_FOUND;
        }
        if (ErrorCode.CONFLICT.getCode().equals(code)) {
            return HttpStatus.CONFLICT;
        }
        if (ErrorCode.PARAM_INVALID.getCode().equals(code)) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
