package com.aliyun.autowonder.spacedashboard;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 空间级看板：一次请求返回数字员工、工单交付与成本分析三个版块，只读。
 * <p>
 * 刻意不挂 {@code @RequireWorkspaceAccess}：该切面校验 token 绑定的当前空间，而本看板与当前空间无关
 * （调用者可能同时管理多个空间，当前空间也可能刚被停用）。授权在
 * {@link SpaceDashboardService} 内按可管理空间集合判定。
 */
@RestController
@RequestMapping("/api/space-dashboard")
public class SpaceDashboardController {

    private final SpaceDashboardService service;

    public SpaceDashboardController(SpaceDashboardService service) {
        this.service = service;
    }

    /**
     * 窗口参数按契约必填，但缺失时由 {@link #parseDate} 统一判成 {@code PARAM_INVALID}：
     * 交给 Spring 的 {@code MissingServletRequestParameterException} 会落入全局 unexpected 路径，
     * 返回 200/10000 并记 ERROR 日志，与实现计划 §2.2 的「缺参 → PARAM_INVALID」不符。
     */
    @GetMapping("/overview")
    public Result<SpaceDashboardVO> overview(
            @RequestParam(value = "deliveryStart", required = false) String deliveryStart,
            @RequestParam(value = "deliveryEnd", required = false) String deliveryEnd,
            @RequestParam(value = "workTypes", required = false) String workTypes,
            @RequestParam(value = "costStart", required = false) String costStart,
            @RequestParam(value = "costEnd", required = false) String costEnd) {
        return Result.ok(service.overview(currentUserId(),
                parseDate(deliveryStart), parseDate(deliveryEnd), workTypes,
                parseDate(costStart), parseDate(costEnd)));
    }

    private static long currentUserId() {
        Long userId = AutoWonderContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException malformed) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }
}
