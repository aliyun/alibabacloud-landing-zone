package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.dto.RuntimeTraceVO;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/dispatches")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看调度运行轨迹")
public class RuntimeTraceController {

    private final RuntimeTraceService traceService;
    private final RuntimeTraceArtifactService artifactService;

    public RuntimeTraceController(RuntimeTraceService traceService, RuntimeTraceArtifactService artifactService) {
        this.traceService = traceService;
        this.artifactService = artifactService;
    }

    @GetMapping("/{id}/runtime-trace")
    public Result<RuntimeTraceVO> get(@PathVariable("id") long id,
                                      @RequestParam(value = "afterSeq", required = false) Long afterSeq) {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        RuntimeTraceVO completed = artifactService.loadOutlineIfPresent(orgId, id);
        return Result.ok(completed == null ? traceService.get(orgId, id, afterSeq) : completed);
    }

    @GetMapping("/{id}/runtime-trace/turns/{traceId}")
    public Result<RuntimeTraceVO.Turn> getTurn(@PathVariable("id") long id,
                                               @PathVariable("traceId") String traceId) {
        return Result.ok(artifactService.loadTurn(currentOrgId(), id, traceId));
    }

    @GetMapping("/{id}/runtime-trace/observations/{observationId}")
    public Result<RuntimeTraceVO.Observation> getObservation(@PathVariable("id") long id,
                                                             @PathVariable("observationId") String observationId) {
        return Result.ok(artifactService.loadObservation(currentOrgId(), id, observationId));
    }

    @GetMapping("/{id}/runtime-trace/context")
    public ResponseEntity<byte[]> getContext(@PathVariable("id") long id,
                                              @RequestParam("ref") String contentRef) {
        RuntimeTraceArtifactService.ContextContent content = artifactService.loadContext(currentOrgId(), id, contentRef);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}
