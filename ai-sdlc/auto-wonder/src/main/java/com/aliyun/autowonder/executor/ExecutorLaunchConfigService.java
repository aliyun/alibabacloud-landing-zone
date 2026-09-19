package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchConfigVO;
import com.aliyun.autowonder.executor.dto.SelectOptionVO;
import com.aliyun.autowonder.executor.dto.UpdateExecutorLaunchConfigRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Server-side persistence for the executor launch config, the single source of truth the page's 启动命令 dialog, the
 * page's command generation and every MCP executor tool share. One JSON row per executor in executor.launch_config
 * guarded by an optimistic-lock version in executor.config_version. Nothing here ever reads a browser preference or
 * backfills a system default into a stored config: an incomplete config is reported as incomplete.
 */
@Service
public class ExecutorLaunchConfigService {

    public static final int DEFAULT_MAX_CONCURRENT_DISPATCHES = 5;

    private static final Logger log = LoggerFactory.getLogger(ExecutorLaunchConfigService.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final ExecutorDao executorDao;
    private final ExecutorLaunchOptionsService options;

    public ExecutorLaunchConfigService(ExecutorDao executorDao, ExecutorLaunchOptionsService options) {
        this.executorDao = executorDao;
        this.options = options;
    }

    /**
     * Page read path. A missing config is not an error: it returns every field null at version 1 so the page can show
     * 「未配置」 and the first PUT can carry version=1. A stored model the live catalog no longer offers is cleared here
     * and best-effort written back, so the page prompts the operator to pick again instead of launching a dead id; the
     * write-back never blocks the read and, when it lands, the returned version already reflects the bump.
     */
    public ExecutorLaunchConfigVO getConfig(long id, long tenantId, long userId) {
        ExecutorDO executor = requireExecutor(id, tenantId);
        int version = versionOf(executor);
        LaunchConfig stored = parse(executor.getLaunchConfig());
        String provider = ExecutorLaunchOptionsService.resolveProvider(executor.getClientKind());
        if (!ExecutorLaunchOptionsService.isQoderFamily(provider)) {
            return toVo(null, null, null, stored.memoryMode, stored.maxConcurrentDispatches, version);
        }
        String model = stored.model;
        if (isNotBlank(model) && !modelIds(provider).contains(model)) {
            stored.model = null;
            Integer bumped = writeBackCleared(executor, stored, version, tenantId, userId);
            if (bumped != null) {
                version = bumped;
            }
            model = null;
        }
        return toVo(model, stored.reasoningEffort, stored.contextWindow, stored.memoryMode, stored.maxConcurrentDispatches, version);
    }

    /**
     * Read-only view of exactly what the database holds, for the MCP config query tool. Never writes back and never
     * re-resolves a rotated-out model, so a caller learns the truth instead of a silently repaired value; an
     * executor that does not exist in this workspace is an error, not an empty config.
     */
    public ExecutorLaunchConfigVO readStoredConfig(long id, long tenantId) {
        ExecutorDO executor = requireExecutor(id, tenantId);
        LaunchConfig stored = parse(executor.getLaunchConfig());
        int version = versionOf(executor);
        String provider = ExecutorLaunchOptionsService.resolveProvider(executor.getClientKind());
        if (!ExecutorLaunchOptionsService.isQoderFamily(provider)) {
            return toVo(null, null, null, stored.memoryMode, stored.maxConcurrentDispatches, version);
        }
        return toVo(stored.model, stored.reasoningEffort, stored.contextWindow, stored.memoryMode, stored.maxConcurrentDispatches, version);
    }

    /**
     * Page and MCP write path. Validates every value against the same source of truth the create form uses; a model
     * the catalog no longer offers is rejected so a dead id is never persisted, and a stale version is a conflict the
     * page turns into "刷新后重试". The returned VO is what the database now holds.
     */
    public ExecutorLaunchConfigVO updateConfig(long id, long tenantId, UpdateExecutorLaunchConfigRequest req,
            long userId) {
        ExecutorDO executor = requireExecutor(id, tenantId);
        if (req == null || req.getVersion() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少启动配置版本号");
        }
        int expectedVersion = req.getVersion();
        LaunchConfig stored = resolve(executor.getClientKind(), req.getMemoryMode(), req.getModel(),
                req.getReasoningEffort(), req.getContextWindow(), true);
        stored.maxConcurrentDispatches = resolveMaxConcurrentDispatches(req.getMaxConcurrentDispatches() != null
                ? req.getMaxConcurrentDispatches() : parse(executor.getLaunchConfig()).maxConcurrentDispatches);
        int rows = executorDao.updateLaunchConfig(id, tenantId, toJson(stored), expectedVersion, userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_VERSION_CONFLICT);
        }
        return toVo(stored.model, stored.reasoningEffort, stored.contextWindow, stored.memoryMode, stored.maxConcurrentDispatches,
                expectedVersion + 1);
    }

    /**
     * Resolves the config a brand new executor is created with, so create and update validate identically and the row
     * is command-ready the moment it exists. An omitted value takes the same default the create dialog pre-fills; an
     * explicitly passed model the catalog no longer offers is rejected rather than silently replaced.
     */
    public LaunchConfig resolveForCreate(CreateExecutorRequest req) {
        boolean explicitModel = req != null && isNotBlank(req.getModel());
        // An omitted model takes the same catalog-derived default the create dialog pre-fills; an explicitly passed
        // one must still be offered, so a dead id is never persisted behind the operator's back.
        LaunchConfig config = resolve(req == null ? null : req.getClientKind(),
                req == null ? null : req.getMemoryMode(),
                explicitModel ? req.getModel() : null,
                req == null ? null : req.getReasoningEffort(),
                req == null ? null : req.getContextWindow(),
                explicitModel);
        config.maxConcurrentDispatches = resolveMaxConcurrentDispatches(req == null ? null : req.getMaxConcurrentDispatches());
        return config;
    }

    /**
     * The config command generation must use. An incomplete config (a row created before launch config persistence, or
     * one cleared because its model rotated out) is refused instead of being padded with system defaults, and a model
     * the catalog no longer offers must be re-saved before a command may be generated again.
     */
    public LaunchConfig requireCompleteConfig(long id, long tenantId) {
        ExecutorDO executor = requireExecutor(id, tenantId);
        LaunchConfig stored = parse(executor.getLaunchConfig());
        String provider = ExecutorLaunchOptionsService.resolveProvider(executor.getClientKind());
        if (!isNotBlank(stored.memoryMode)) {
            throw incomplete();
        }
        if (!ExecutorLaunchOptionsService.isQoderFamily(provider)) {
            stored.model = null;
            stored.reasoningEffort = null;
            stored.contextWindow = null;
            return stored;
        }
        if (!isNotBlank(stored.model) || !isNotBlank(stored.reasoningEffort)
                || !isNotBlank(stored.contextWindow)) {
            throw incomplete();
        }
        if (!modelIds(provider).contains(stored.model)) {
            throw new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_MODEL_INVALID,
                    "已保存的模型 " + stored.model + " 已不可用，请重新选择并保存启动配置");
        }
        return stored;
    }

    public String toJson(LaunchConfig config) {
        try {
            return JSON_MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException exception) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "启动配置序列化失败");
        }
    }

    /**
     * Shared create/update validation. {@code requireKnownModel} rejects a model id the catalog does not offer; when
     * it is false the caller is asking for a default and the id is demoted to a usable one instead.
     */
    private LaunchConfig resolve(String clientKind, String memoryMode, String model, String reasoningEffort,
            String contextWindow, boolean requireKnownModel) {
        String provider = ExecutorLaunchOptionsService.resolveProvider(clientKind);
        LaunchConfig config = new LaunchConfig();
        config.memoryMode = options.resolveMemoryMode(memoryMode);
        if (!ExecutorLaunchOptionsService.isQoderFamily(provider)) {
            return config;
        }
        String requested = model == null ? null : model.trim();
        if (isNotBlank(requested) && modelIds(provider).contains(requested)) {
            config.model = requested;
        } else if (requireKnownModel || isNotBlank(requested)) {
            throw new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_MODEL_INVALID);
        } else {
            config.model = ExecutorLaunchOptionsService.chooseModel(options.models(provider), requested);
        }
        config.reasoningEffort = options.resolveReasoningEffort(config.model, reasoningEffort);
        config.contextWindow = options.resolveContextWindow(contextWindow);
        return config;
    }

    private static BizException incomplete() {
        return new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_INCOMPLETE);
    }

    private ExecutorDO requireExecutor(long id, long tenantId) {
        ExecutorDO executor = executorDao.findById(id);
        if (executor == null || executor.getTenantId() == null || executor.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.EXECUTOR_NOT_FOUND);
        }
        return executor;
    }

    private Integer writeBackCleared(ExecutorDO executor, LaunchConfig cleared, int expectedVersion, long tenantId,
            long userId) {
        try {
            int rows = executorDao.updateLaunchConfig(executor.getId(), tenantId, toJson(cleared), expectedVersion,
                    userId);
            return rows == 1 ? expectedVersion + 1 : null;
        } catch (RuntimeException exception) {
            log.warn("executor launch config cleanup write-back failed executorId={}", executor.getId(), exception);
            return null;
        }
    }

    private List<String> modelIds(String provider) {
        return options.models(provider).stream().map(SelectOptionVO::getValue).toList();
    }

    private static int versionOf(ExecutorDO executor) {
        return executor.getConfigVersion() == null ? 1 : executor.getConfigVersion();
    }

    private static LaunchConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return new LaunchConfig();
        }
        try {
            LaunchConfig parsed = JSON_MAPPER.readValue(json, LaunchConfig.class);
            if (parsed == null) return new LaunchConfig();
            if (parsed.maxConcurrentDispatches == null && isNotBlank(parsed.memoryMode)) {
                parsed.maxConcurrentDispatches = DEFAULT_MAX_CONCURRENT_DISPATCHES;
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            log.warn("executor launch config is not valid JSON, treating it as empty");
            return new LaunchConfig();
        }
    }

    private static ExecutorLaunchConfigVO toVo(String model, String reasoningEffort, String contextWindow,
            String memoryMode, Integer maxConcurrentDispatches, int version) {
        ExecutorLaunchConfigVO vo = new ExecutorLaunchConfigVO();
        vo.setModel(model);
        vo.setReasoningEffort(reasoningEffort);
        vo.setContextWindow(contextWindow);
        vo.setMemoryMode(memoryMode);
        vo.setMaxConcurrentDispatches(maxConcurrentDispatches);
        vo.setVersion(version);
        return vo;
    }

    public static int resolveMaxConcurrentDispatches(Integer value) {
        if (value == null) return DEFAULT_MAX_CONCURRENT_DISPATCHES;
        if (value < 1 || value > 10) {
            throw new BizException(ErrorCode.PARAM_INVALID, "最大并发任务数必须为 1 到 10 的整数");
        }
        return value;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Persisted shape of executor.launch_config; public fields mirror the repo's JSON-column POJO style. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class LaunchConfig {
        public String model;
        public String reasoningEffort;
        public String contextWindow;
        public String memoryMode;
        public Integer maxConcurrentDispatches;
    }
}
