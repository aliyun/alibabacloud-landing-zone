package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.integration.aone.AoneIntegrationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationCapabilityController {

    private final AoneIntegrationProperties properties;

    public IntegrationCapabilityController(AoneIntegrationProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/capabilities")
    public Result<Map<String, Boolean>> capabilities() {
        return Result.ok(Map.of("aoneEnabled", properties.isEnabled()));
    }
}
