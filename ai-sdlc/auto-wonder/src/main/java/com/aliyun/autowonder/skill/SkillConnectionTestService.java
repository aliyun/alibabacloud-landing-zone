package com.aliyun.autowonder.skill;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.SkillConnectionTestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class SkillConnectionTestService {

    private final SkillDao skillDao;
    private final RuntimeMcpConnectionTestService runtimeTestService;

    @Autowired
    public SkillConnectionTestService(SkillDao skillDao, RuntimeMcpConnectionTestService runtimeTestService) {
        this.skillDao = skillDao;
        this.runtimeTestService = runtimeTestService;
    }

    SkillConnectionTestService(SkillDao skillDao) {
        this.skillDao = skillDao;
        this.runtimeTestService = null;
    }

    public SkillConnectionTestVO test(long id, long tenantId, Long executorId) {
        SkillDO skill = skillDao.findById(id);
        if (skill == null || skill.getTenantId() == null || skill.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND);
        }
        if (!"MCP".equalsIgnoreCase(skill.getType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅 MCP 类型能力支持连接测试");
        }
        long startedAt = System.nanoTime();
        try {
            JSONObject config = parseConfig(skill.getInstallSpec());
            String transport = config.getString("transport");
            if (transport == null || transport.isBlank()) {
                transport = "http";
            }
            if (executorId == null) {
                return failure(startedAt, "请选择在线 Runtime 测试 MCP");
            }
            if (runtimeTestService == null) {
                return failure(startedAt, "Runtime MCP 测试服务不可用");
            }
            RuntimeMcpConnectionTestService.SkillConnectionTestResult result = runtimeTestService.test(
                    tenantId, executorId, transport, config.getString("command"), args(config), config.getString("url"));
            SkillConnectionTestVO vo = failure(startedAt, result.message);
            vo.setSuccess(result.success);
            vo.setDurationMs(result.durationMs == null ? elapsedMillis(startedAt) : result.durationMs);
            return vo;
        } catch (Exception e) {
            return failure(startedAt, normalizeError(e));
        }
    }

    public SkillConnectionTestVO test(long id, long tenantId) {
        return test(id, tenantId, null);
    }

    private List<String> args(JSONObject config) {
        return config.getJSONArray("args") == null ? List.of() : config.getJSONArray("args").toJavaList(String.class);
    }

    private JSONObject parseConfig(String installSpec) {
        if (installSpec == null || installSpec.isBlank()) {
            throw new IllegalArgumentException("MCP 配置为空");
        }
        try {
            return JSON.parseObject(installSpec);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("MCP 配置不是有效 JSON");
        }
    }

    private SkillConnectionTestVO failure(long startedAt, String message) {
        SkillConnectionTestVO vo = new SkillConnectionTestVO();
        vo.setSuccess(false);
        vo.setMessage(message == null || message.isBlank() ? "连接失败" : message);
        vo.setDurationMs(elapsedMillis(startedAt));
        return vo;
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private String normalizeError(Exception e) {
        if (e instanceof java.util.concurrent.TimeoutException) {
            return "连接超时";
        }
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
