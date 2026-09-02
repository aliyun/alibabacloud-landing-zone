package com.aliyun.autowonder.skill;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.SkillConnectionTestVO;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class SkillConnectionTestService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkillConnectionTestService.class);

    private final SkillDao skillDao;
    private final RuntimeMcpConnectionTestService runtimeTestService;
    private SecretCrypto secretCrypto;

    @Autowired
    public SkillConnectionTestService(SkillDao skillDao, RuntimeMcpConnectionTestService runtimeTestService) {
        this.skillDao = skillDao;
        this.runtimeTestService = runtimeTestService;
    }

    public SkillConnectionTestService(SkillDao skillDao, RuntimeMcpConnectionTestService runtimeTestService,
                                      SecretCrypto secretCrypto) {
        this(skillDao, runtimeTestService);
        this.secretCrypto = secretCrypto;
    }

    @Autowired(required = false)
    void setSecretCrypto(SecretCrypto secretCrypto) { this.secretCrypto = secretCrypto; }

    SkillConnectionTestService(SkillDao skillDao) {
        this.skillDao = skillDao;
        this.runtimeTestService = null;
        this.secretCrypto = null;
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
        String transport = "unknown";
        try {
            JSONObject config = parseConfig(skill.getInstallSpec());
            transport = config.getString("transport");
            if (transport == null || transport.isBlank()) {
                transport = "http";
            }
            if (executorId == null) {
                return failure(startedAt, "请选择在线 Runtime 测试 MCP");
            }
            if (runtimeTestService == null) {
                return failure(startedAt, "Runtime MCP 测试服务不可用");
            }
			Map<String, String> headers = headers(config);
			Map<String, String> env = env(config);
			RuntimeMcpConnectionTestService.SkillConnectionTestResult result = env.isEmpty()
					? runtimeTestService.test(tenantId, executorId, transport, config.getString("command"), args(config), config.getString("url"), headers, timeoutSeconds(config))
					: runtimeTestService.test(tenantId, executorId, transport, config.getString("command"), args(config), config.getString("url"), headers, env, timeoutSeconds(config));
            SkillConnectionTestVO vo = failure(startedAt, result.message);
            vo.setSuccess(result.success);
            vo.setDurationMs(result.durationMs == null ? elapsedMillis(startedAt) : result.durationMs);
            vo.setTools(result.tools);
            return vo;
        } catch (Exception e) {
            // Do not log installSpec, headers or env: they may contain MCP credentials.
            if (e instanceof IllegalArgumentException) {
                LOGGER.warn("MCP connection test rejected skillId={} tenantId={} executorId={} transport={} errorType={} message={}",
                        id, tenantId, executorId, transport, e.getClass().getName(), e.getMessage());
            } else {
                LOGGER.error("MCP connection test failed skillId={} tenantId={} executorId={} transport={} errorType={}",
                        id, tenantId, executorId, transport, e.getClass().getName(), e);
            }
            return failure(startedAt, normalizeError(e));
        }
    }

    public SkillConnectionTestVO test(long id, long tenantId) {
        return test(id, tenantId, null);
    }

    private List<String> args(JSONObject config) {
        return config.getJSONArray("args") == null ? List.of() : config.getJSONArray("args").toJavaList(String.class);
    }

    private Map<String, String> headers(JSONObject config) {
		return resolveValues(config.getJSONObject("headers"));
	}

	private Map<String, String> env(JSONObject config) {
		return resolveValues(config.getJSONObject("env"));
	}

	private Map<String, String> resolveValues(JSONObject source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof Map && "secretRef".equals(String.valueOf(((Map<?, ?>) value).get("kind")))) {
				if (secretCrypto == null) throw new IllegalStateException("密文存储未配置，无法测试私密 MCP 配置");
				headers.put(entry.getKey(), secretCrypto.decrypt(String.valueOf(((Map<?, ?>) value).get("ref"))));
			} else headers.put(entry.getKey(), String.valueOf(value));
        }
        return headers;
    }

    private int timeoutSeconds(JSONObject config) {
        Integer timeout = config.getInteger("timeoutSeconds");
        return timeout == null ? 60 : timeout;
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
