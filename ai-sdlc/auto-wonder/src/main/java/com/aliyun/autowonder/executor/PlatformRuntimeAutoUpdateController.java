package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.setting.dto.RuntimeAutoUpdateVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-wide automatic executor upgrade switch. The switch is a deployment setting read from
 * {@code application.yml}, so this endpoint only exposes its current state (open to any signed-in
 * user — the panel shows it to everyone) and has no write side.
 */
@RestController
@RequestMapping("/api/platform/runtime-auto-update")
public class PlatformRuntimeAutoUpdateController {

    private final ExecutorUpdateService updateService;

    public PlatformRuntimeAutoUpdateController(ExecutorUpdateService updateService) {
        this.updateService = updateService;
    }

    @GetMapping
    public Result<RuntimeAutoUpdateVO> view() {
        return Result.ok(updateService.runtimeAutoUpdateView());
    }
}
