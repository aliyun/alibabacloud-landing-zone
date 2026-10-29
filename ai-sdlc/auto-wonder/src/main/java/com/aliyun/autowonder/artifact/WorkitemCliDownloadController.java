package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.access.WorkspaceDomain;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.mcp.WorkitemCliDownloadTokenService;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Read-only endpoints for the {@code autowonder workitem download} CLI command.
 * Bypassed by the session AuthFilter (exact GET paths only); authentication uses the scoped
 * awdownload_ token and every request re-checks live read membership, matching the visibility
 * of {@code list_workitem_documents}. The index endpoint lets the CLI resolve {@code --file}
 * names to artifact ids and enumerate all documents; the content endpoint streams raw bytes.
 */
@RestController
@RequestMapping("/api/cli")
public class WorkitemCliDownloadController {

    private final WorkitemCliDownloadTokenService tokenService;
    private final WorkitemDao workitemDao;
    private final RequirementDocumentService requirementDocumentService;

    public WorkitemCliDownloadController(WorkitemCliDownloadTokenService tokenService,
                                         WorkitemDao workitemDao,
                                         RequirementDocumentService requirementDocumentService) {
        this.tokenService = tokenService;
        this.workitemDao = workitemDao;
        this.requirementDocumentService = requirementDocumentService;
    }

    @GetMapping("/workitems/{workitemId}/requirement-documents/index")
    public ResponseEntity<Result<List<ArtifactVO>>> index(
            @PathVariable("workitemId") long workitemId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        com.aliyun.autowonder.mcp.McpAccessTokenService.Principal principal = null;
        try {
            principal = authenticate(authorization, workitemId);
            WorkitemDO workitem = authorizedWorkitem(workitemId, principal.userId());
            var result = requirementDocumentService.list(workitemId, workitem.getTenantId());
            tokenService.auditUse(principal, workitemId, "CLI_DOWNLOAD_INDEX", null);
            return ResponseEntity.ok(Result.ok(result));
        } catch (BizException ex) {
            tokenService.auditUse(principal, workitemId, "CLI_DOWNLOAD_INDEX", ex);
            return ResponseEntity.status(statusFor(ex.getCode()))
                    .body(Result.fail(ex));
        } catch (RuntimeException ex) {
            tokenService.auditUse(principal, workitemId, "CLI_DOWNLOAD_INDEX", ex);
            throw ex;
        }
    }

    @GetMapping("/workitems/{workitemId}/requirement-documents/{artifactId}/content")
    public ResponseEntity<byte[]> content(
            @PathVariable("workitemId") long workitemId,
            @PathVariable("artifactId") long artifactId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        com.aliyun.autowonder.mcp.McpAccessTokenService.Principal principal = null;
        try {
            principal = authenticate(authorization, workitemId);
            WorkitemDO workitem = authorizedWorkitem(workitemId, principal.userId());
            RequirementDocumentService.DocumentContent content =
                    requirementDocumentService.readDocument(workitemId, artifactId, workitem.getTenantId());
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(content.filename(), StandardCharsets.UTF_8)
                    .build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(disposition);
            headers.setContentType(MediaType.valueOf(content.contentType()));
            headers.set("X-Content-Type-Options", "nosniff");
            tokenService.auditUse(principal, workitemId, "CLI_DOWNLOAD_CONTENT", null);
            return new ResponseEntity<>(content.bytes(), headers, HttpStatus.OK);
        } catch (BizException ex) {
            tokenService.auditUse(principal, workitemId, "CLI_DOWNLOAD_CONTENT", ex);
            byte[] body = JSON.toJSONString(Result.fail(ex))
                    .getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(statusFor(ex.getCode()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(body);
        } catch (RuntimeException ex) {
            tokenService.auditUse(principal, workitemId, "CLI_DOWNLOAD_CONTENT", ex);
            throw ex;
        }
    }

    private WorkitemDO authorizedWorkitem(long workitemId, long userId) {
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null || workitem.getTenantId() == null) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        tokenService.requireReadMembership(workitem.getTenantId(), userId, WorkspaceDomain.ARTIFACT);
        return workitem;
    }

    private com.aliyun.autowonder.mcp.McpAccessTokenService.Principal authenticate(String authorization, long workitemId) {
        String scheme = "Bearer ";
        if (authorization == null || authorization.length() < scheme.length()
                || !authorization.regionMatches(true, 0, scheme, 0, scheme.length())) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return tokenService.authenticateForWorkitem(authorization.substring(scheme.length()).trim(), workitemId);
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
        if (ErrorCode.PARAM_INVALID.getCode().equals(code)) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
