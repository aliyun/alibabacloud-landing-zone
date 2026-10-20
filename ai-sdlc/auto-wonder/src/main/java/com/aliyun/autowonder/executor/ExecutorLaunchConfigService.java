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
        return toVo(stored, version, ExecutorLaunchOptionsService.isQoderFamily(provider));
    }

    /**
     * Page and MCP write path. Validates every value against the same source of truth the create form uses; a model
     * the catalog no longer offers is rejected so a dead id is never persisted, and a stale version is a conflict the
     * page turns into "刷新后重试". The returned VO is what the database now holds.
     *
     * <p>Executors created before client kinds were enforced (client_kind NULL/blank) recover here: the caller must
     * pick QODER_CLI/QODER_CN_CLI once and the kind is persisted with the config in the same guarded write, so the
     * row is command-ready afterwards. An executor that already has a kind never has it changed.
     */
    public ExecutorLaunchConfigVO updateConfig(long id, long tenantId, UpdateExecutorLaunchConfigRequest req,
            long userId) {
        ExecutorDO executor = requireExecutor(id, tenantId);
        if (req == null || req.getVersion() == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.config.version_required", null, "缺少启动配置版本号");
        }
        int expectedVersion = req.getVersion();
        String kindToPersist = resolveRecoveryClientKind(executor.getClientKind(), req.getClientKind());
        LaunchConfig stored = resolveUpdatedConfig(executor, req,
                kindToPersist != null ? kindToPersist : executor.getClientKind());
        int rows = kindToPersist != null
                ? executorDao.updateLaunchConfigWithClientKind(id, tenantId, toJson(stored), kindToPersist,
                        expectedVersion, userId)
                : executorDao.updateLaunchConfig(id, tenantId, toJson(stored), expectedVersion, userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_VERSION_CONFLICT);
        }
        return toVo(stored, expectedVersion + 1, ExecutorLaunchOptionsService.isQoderFamily(
                ExecutorLaunchOptionsService.resolveProvider(kindToPersist != null ? kindToPersist : executor.getClientKind())));
    }

    public LaunchConfig previewConfig(long id, long tenantId, UpdateExecutorLaunchConfigRequest request) {
        ExecutorDO executor = requireExecutor(id, tenantId);
        if (request == null || request.getVersion() == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.config.version_required", null, "缺少启动配置版本号");
        }
        if (request.getVersion() != versionOf(executor)) {
            throw new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_VERSION_CONFLICT);
        }
        String recoveredKind = resolveRecoveryClientKind(executor.getClientKind(), request.getClientKind());
        return resolveUpdatedConfig(executor, request,
                recoveredKind != null ? recoveredKind : executor.getClientKind());
    }

    private LaunchConfig resolveUpdatedConfig(ExecutorDO executor, UpdateExecutorLaunchConfigRequest req, String clientKind) {
        LaunchConfig previous = parse(executor.getLaunchConfig());
        LaunchConfig stored = resolve(clientKind,
                req.getMemoryMode(), req.getModel(),
                req.getReasoningEffort(), req.getContextWindow(), true);
        stored.maxConcurrentDispatches = resolveMaxConcurrentDispatches(req.getMaxConcurrentDispatches() != null
                ? req.getMaxConcurrentDispatches() : previous.maxConcurrentDispatches);
        stored.modelUpgradePolicy = req.getModelUpgradePolicy() == null
                ? previous.modelUpgradePolicy : validatePolicy(req.getModelUpgradePolicy(),
                        clientKind);
        stored.runtimeVersion = req.getRuntimeVersion() == null ? previous.runtimeVersion : runtimeVersion(req.getRuntimeVersion());
        stored.workspaceRoot = req.getWorkspaceRoot() == null ? previous.workspaceRoot : launchText(req.getWorkspaceRoot(), 4096);
        stored.daemonName = req.getDaemonName() == null ? previous.daemonName : launchText(req.getDaemonName(), 200);
        stored.debug = req.getDebug() == null ? previous.debug : req.getDebug();
        return stored;
    }

    /** Policy-only write: preserve every launch field, including legacy and retired model values. */
    public ExecutorLaunchConfigVO updateModelUpgradePolicy(long id, long tenantId,
            UpdateExecutorLaunchConfigRequest request, long userId) {
        ExecutorDO executor = requireExecutor(id, tenantId);
        if (request == null || request.getVersion() == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.config.version_required", null, "缺少启动配置版本号");
        }
        if (request.getModelUpgradePolicy() == null) throw invalidPolicy();
        LaunchConfig stored = parse(executor.getLaunchConfig());
        stored.modelUpgradePolicy = validatePolicy(request.getModelUpgradePolicy(), executor.getClientKind());
        if (executorDao.updateLaunchConfig(id, tenantId, mergePolicy(executor.getLaunchConfig(), stored.modelUpgradePolicy), request.getVersion(), userId) == 0)
            throw new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_VERSION_CONFLICT);
        return toVo(stored, request.getVersion() + 1, ExecutorLaunchOptionsService.isQoderFamily(
                ExecutorLaunchOptionsService.resolveProvider(executor.getClientKind())));
    }

    private String mergePolicy(String json, ModelUpgradePolicy policy) {
        try {
            var root = json == null || json.isBlank() ? JSON_MAPPER.createObjectNode() : JSON_MAPPER.readTree(json);
            if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.config.serialization_failed", null, "Invalid stored launch config");
            }
            object.set("modelUpgradePolicy", JSON_MAPPER.valueToTree(policy));
            return JSON_MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException error) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.config.serialization_failed", null, "Invalid stored launch config");
        }
    }

    /**
     * Resolves the client kind a legacy-kindless executor should be recovered with: null means the executor already
     * has one and this write must not touch it. A kindless executor must pick a creatable Qoder kind in the same
     * save, because a config saved without a kind still cannot produce a command; a typed executor asked for a
     * different kind is refused instead of silently rewritten.
     */
    private static String resolveRecoveryClientKind(String currentKind, String requestedKind) {
        if (isNotBlank(currentKind)) {
            if (isNotBlank(requestedKind) && !currentKind.trim().equalsIgnoreCase(requestedKind.trim())) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.config.client_kind_immutable",
                        new Object[]{currentKind}, "执行器客户端类型已存在（" + currentKind + "），不允许通过启动配置修改");
            }
            return null;
        }
        if (!isNotBlank(requestedKind)) {
            throw BizException.keyed(ErrorCode.EXECUTOR_CLIENT_KIND_MISSING, "executor.config.client_kind_required", null,
                    "该执行器缺少客户端类型，请在保存启动配置时选择客户端类型");
        }
        String canonical = ExecutorLaunchOptionsService.canonicalClientKind(requestedKind);
        if (!ExecutorLaunchOptionsService.isCreatableClientKind(canonical)) {
            throw new BizException(ErrorCode.EXECUTOR_CLIENT_KIND_INVALID);
        }
        return canonical;
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
        return requireCompleteConfig(id, tenantId, null);
    }

    public LaunchConfig requireCompleteConfig(long id, long tenantId, Integer expectedVersion) {
        ExecutorDO executor = requireExecutor(id, tenantId);
        if (expectedVersion != null && expectedVersion != versionOf(executor)) {
            throw new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_VERSION_CONFLICT);
        }
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
            throw BizException.keyed(ErrorCode.EXECUTOR_LAUNCH_CONFIG_MODEL_INVALID, "executor.config.saved_model_unavailable",
                    new Object[]{stored.model}, "已保存的模型 " + stored.model + " 已不可用，请重新选择并保存启动配置");
        }
        return stored;
    }

    public String toJson(LaunchConfig config) {
        try {
            return JSON_MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException exception) {
            throw BizException.keyed(ErrorCode.SYSTEM_ERROR, "executor.config.serialization_failed", null, "启动配置序列化失败");
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


    private List<String> modelIds(String provider) {
        return options.models(provider).stream().map(SelectOptionVO::getValue).toList();
    }

    private static int versionOf(ExecutorDO executor) {
        return executor.getConfigVersion() == null ? 1 : executor.getConfigVersion();
    }

    public static LaunchConfig parse(String json) {
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

    private static ExecutorLaunchConfigVO toVo(LaunchConfig config, int version, boolean qoder) {
        ExecutorLaunchConfigVO vo = new ExecutorLaunchConfigVO();
        vo.setModel(qoder ? config.model : null);
        vo.setReasoningEffort(qoder ? config.reasoningEffort : null);
        vo.setContextWindow(qoder ? config.contextWindow : null);
        vo.setMemoryMode(config.memoryMode);
        vo.setMaxConcurrentDispatches(config.maxConcurrentDispatches);
        vo.setModelUpgradePolicy(config.modelUpgradePolicy);
        vo.setRuntimeVersion(config.runtimeVersion);
        vo.setWorkspaceRoot(config.workspaceRoot);
        vo.setDaemonName(config.daemonName);
        vo.setDebug(config.debug);
        vo.setVersion(version);
        return vo;
    }

    private static String runtimeVersion(String value) {
        String version = launchText(value, 64);
        if (version != null && (!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
                || RuntimeVersion.compare(version, version).isEmpty())) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.command.version_invalid", null,
                    "执行器版本必须是 X.Y.Z 格式的稳定发布版本");
        }
        return version;
    }

    private static String launchText(String value, int maxLength) {
        if (value.isBlank()) return null;
        if (value.length() > maxLength || value.chars().anyMatch(Character::isISOControl)) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.command.parameter_invalid", null,
                    "启动参数过长或包含控制字符");
        }
        return value;
    }

    public static int resolveMaxConcurrentDispatches(Integer value) {
        if (value == null) return DEFAULT_MAX_CONCURRENT_DISPATCHES;
        if (value < 1 || value > 10) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.config.concurrency_invalid", null, "最大并发任务数必须为 1 到 10 的整数");
        }
        return value;
    }

    private ModelUpgradePolicy validatePolicy(ModelUpgradePolicy policy, String clientKind) {
        if (policy.getLevels() == null) policy.setLevels(List.of());
        if (policy.isEnabled() && policy.getLevels().isEmpty()) throw invalidPolicy();
        if (policy.getLevels().size() > 20) throw invalidPolicy();
        int previous = 1;
        java.util.Set<String> models = new java.util.HashSet<>();
        String provider = ExecutorLaunchOptionsService.resolveProvider(clientKind);
        for (ModelUpgradePolicy.Level level : policy.getLevels()) {
            if (level == null || level.getEntryCount() == null || level.getEntryCount() <= previous
                    || level.getModel() == null || level.getModel().isBlank()
                    || level.getModel().length() > 200 || !level.getModel().matches("[A-Za-z0-9_.:/+-]+")
                    || !models.add(level.getModel())) throw invalidPolicy();
            if (policy.isEnabled() && ExecutorLaunchOptionsService.isQoderFamily(provider) && !modelIds(provider).contains(level.getModel()))
                throw new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_MODEL_INVALID);
            previous = level.getEntryCount();
        }
        return policy;
    }

    private static BizException invalidPolicy() {
        return BizException.keyed(ErrorCode.PARAM_INVALID, "executor.config.upgrade_policy_invalid", null,
                "模型升级策略必须包含递增且不小于 2 的进入次数，以及不重复的有效模型 ID");
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Persisted shape of executor.launch_config; public fields mirror the repo's JSON-column POJO style. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class LaunchConfig {
        public ModelUpgradePolicy modelUpgradePolicy;
        public String model;
        public String reasoningEffort;
        public String contextWindow;
        public String memoryMode;
        public Integer maxConcurrentDispatches;
        public String runtimeVersion;
        public String workspaceRoot;
        public String daemonName;
        public Boolean debug;
    }
}
