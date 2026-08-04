package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.integration.dto.ExternalWorkitemImportRecordVO;
import com.aliyun.autowonder.integration.dto.ExternalWorkitemImportRequest;
import com.aliyun.autowonder.integration.dto.ExternalWorkitemImportResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/external/workitems")
public class ExternalWorkitemImportController {

    private final ExternalWorkitemImportService importService;

    public ExternalWorkitemImportController(ExternalWorkitemImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/import")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "导入外部工单")
    public Result<ExternalWorkitemImportResult> importWorkitem(@RequestBody ExternalWorkitemImportRequest req) {
        return Result.ok(importService.importWorkitem(req, currentOrgId(), currentUserId()));
    }

    @GetMapping("/import-records")
    @RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看外部工单导入记录")
    public Result<List<ExternalWorkitemImportRecordVO>> listRecords(
            @RequestParam(value = "sourceSystem", required = false) String sourceSystem,
            @RequestParam(value = "externalWorkitemId", required = false) String externalWorkitemId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(importService.listRecords(sourceSystem, externalWorkitemId, status,
                currentOrgId(), page, size));
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}
