package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.insights.dto.RequirementDeliveryVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 需求交付指标（吞吐、时长、周目标完成比例）。处理时长目标复用用户级偏好接口
 * {@code /api/users/me/settings}，本控制器只读。
 */
@RestController
@RequestMapping("/api/insights/requirement-delivery")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看需求交付指标", actionKey = "access.action.view_requirement_delivery_metrics")
public class RequirementDeliveryController {

    private final RequirementDeliveryService service;

    public RequirementDeliveryController(RequirementDeliveryService service) {
        this.service = service;
    }

    @GetMapping
    public Result<RequirementDeliveryVO> get(@RequestParam("start_date") String startDate,
                                             @RequestParam("end_date") String endDate) {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return Result.ok(service.get(workspaceId, AutoWonderContext.get().getUserId(),
                parseDate(startDate), parseDate(endDate)));
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException malformed) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }
}
