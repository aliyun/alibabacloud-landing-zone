package com.aliyun.autowonder.insights.delivery;

import com.aliyun.autowonder.access.*;
import com.aliyun.autowonder.common.error.*;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/insights/delivery-analysis")
@RequireWorkspaceAccess(value=WorkspaceAccessLevel.READ_ONLY,action="查看洞察",actionKey="access.action.view_insights")
public class DeliveryAnalyticsController {
    private final DeliveryAnalyticsService service;
    public DeliveryAnalyticsController(DeliveryAnalyticsService service){this.service=service;}
    @GetMapping public Result<DeliveryAnalyticsService.Response> get(@RequestParam(defaultValue="90") int days){
        var tenant=AutoWonderContext.get().getCurrentWorkspaceId();
        if(tenant==null)throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        if(!Set.of(30,60,90).contains(days))throw new BizException(ErrorCode.PARAM_INVALID);
        return Result.ok(service.get(tenant,days));
    }
}
