package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.ExecutorLaunchOptionsService;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogItemVO;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogVO;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板初始化预检：校验模板、客户端与角色引用，按实时模型目录冻结执行器配置，
 * 然后写入 30 分钟有效的快照。目录缺失或模型族无法解析时不生成快照，也不猜测版本。
 */
@Service
public class HarnessPreviewService {

    private static final int SNAPSHOT_TTL_MINUTES = 30;

    private final WorkspaceOnboardingService onboardingService;
    private final WorkspaceOnboardingDao dao;
    private final HarnessTemplateCatalog catalog;
    private final ExecutorLaunchOptionsService launchOptions;

    public HarnessPreviewService(WorkspaceOnboardingService onboardingService, WorkspaceOnboardingDao dao,
            HarnessTemplateCatalog catalog, ExecutorLaunchOptionsService launchOptions) {
        this.onboardingService = onboardingService;
        this.dao = dao;
        this.catalog = catalog;
        this.launchOptions = launchOptions;
    }

    public Map<String, Object> preview(long workspaceId, long userId, Map<String, Object> args) {
        long onboardingId = required(WorkspaceOnboardingService.longArg(args, "onboardingId"), "onboardingId");
        Integer expectedVersion = WorkspaceOnboardingService.intArg(args, "expectedVersion");
        WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, onboardingId);
        if (expectedVersion != null && !expectedVersion.equals(onboarding.getVersion())) {
            throw new BizException(ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT);
        }
        String clientKind = launchOptions.requireCreatableClientKind(
                requiredText(WorkspaceOnboardingService.stringArg(args, "clientKind"), "clientKind"));
        String templateKey = firstNonNull(WorkspaceOnboardingService.stringArg(args, "templateId"),
                onboarding.getTemplateKey());
        HarnessTemplate template = catalog.require(templateKey,
                firstNonNull(WorkspaceOnboardingService.stringArg(args, "version"), onboarding.getTemplateVersion()),
                firstNonNull(WorkspaceOnboardingService.stringArg(args, "sha256"), onboarding.getTemplateSha256()));

        if (args.get("variableValues") == null) {
            throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
        }
        Map<String, String> variableValues = variableValues(template, args.get("variableValues"));
        Map<String, Object> overrides = overrides(args);
        String provider = ExecutorLaunchOptionsService.resolveProvider(clientKind);
        ProviderModelCatalogVO liveCatalog = launchOptions.liveCatalog(workspaceId, provider);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        List<Map<String, Object>> executors = new ArrayList<>();
        HarnessTemplateCatalog.ExecutorDefaults defaults = catalog.manifest().executorDefaults();
        String resolvedAt = Instant.now().toString();

        for (HarnessAgent agent : template.getAgents()) {
            String familyName = catalog.familyForRole(agent.roleCode());
            HarnessModelFamily family = catalog.modelFamily(familyName);
            if (family == null) {
                conflicts.add(conflict("MODEL_FAMILY_UNKNOWN", agent.roleCode(), familyName,
                        "发布清单未声明模型族 " + familyName));
                continue;
            }
            Map<String, Object> override = overrideFor(overrides, agent.roleCode());
            if (liveCatalog == null) {
                conflicts.add(conflict("MODEL_CATALOG_UNAVAILABLE", agent.roleCode(), familyName,
                        "当前空间尚无实时模型目录"));
                continue;
            }
            ProviderModelCatalogItemVO selected = null;
            String source = "LIVE_CATALOG";
            if (override != null) {
                String modelId = String.valueOf(override.get("model"));
                selected = find(liveCatalog, modelId);
                source = "MODEL_OVERRIDE";
                if (selected == null) {
                    conflicts.add(conflict("MODEL_OVERRIDE_UNAVAILABLE", agent.roleCode(), familyName,
                            "覆盖模型 " + modelId + " 不在实时目录中"));
                    continue;
                }
            } else {
                selected = newestMatch(liveCatalog, family);
                if (selected == null) {
                    conflicts.add(conflict("MODEL_UNAVAILABLE", agent.roleCode(), familyName,
                            "实时目录中没有 " + familyName + " 的可用版本"));
                    continue;
                }
            }
            executors.add(executor(agent.roleCode(), agent.name(), clientKind, familyName, selected, source,
                    resolvedAt, defaults));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("templateKey", template.getKey());
        summary.put("templateName", template.getName());
        summary.put("templateVersion", template.getVersion());
        summary.put("templateSha256", template.getSha256());
        summary.put("schemaVersion", template.getSchemaVersion());
        summary.put("clientKind", clientKind);
        summary.put("squadName", template.getSquadName());
        summary.put("variables", template.getVariables().stream()
                .map(variable -> Map.of("token", variable.token(), "label", variable.label(),
                        "requiredWhen", variable.requiredWhen() == null ? "" : variable.requiredWhen()))
                .toList());
        summary.put("skillKeys", template.getSkills().stream().map(HarnessSkill::key).toList());
        summary.put("sdlcRoles", template.getAgents().stream().filter(agent -> agent.sdlc() != null)
                .map(HarnessAgent::roleCode).toList());
        summary.put("noSdlcRoles", template.getAgents().stream().filter(agent -> agent.sdlc() == null)
                .map(HarnessAgent::roleCode).toList());
        summary.put("executors", executors);
        summary.put("repoPermissionRoles", catalog.manifest().repoPermissionRoles());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("conflicts", conflicts);
        if (!conflicts.isEmpty()) {
            result.put("snapshotId", null);
            result.put("snapshotHash", null);
            result.put("expiresAt", null);
            return result;
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("onboardingId", onboarding.getId());
        content.put("onboardingVersion", onboarding.getVersion());
        content.put("templateKey", template.getKey());
        content.put("templateVersion", template.getVersion());
        content.put("templateSha256", template.getSha256());
        content.put("clientKind", clientKind);
        if (variableValues != null) {
            content.put("variableValues", variableValues);
        }
        content.put("executors", executors);
        content.put("squadName", template.getSquadName());
        content.put("squadDescription", template.getSquadDescription());
        String contentJson = JSON.toJSONString(content);
        String contentHash = sha256(contentJson);

        HarnessSnapshotDO snapshot = new HarnessSnapshotDO();
        snapshot.setTenantId(workspaceId);
        snapshot.setWorkspaceId(workspaceId);
        snapshot.setOnboardingId(onboarding.getId());
        snapshot.setSnapshotType(HarnessInitializationService.SNAPSHOT_INITIALIZATION);
        snapshot.setOnboardingVersion(onboarding.getVersion());
        snapshot.setTemplateKey(template.getKey());
        snapshot.setTemplateVersion(template.getVersion());
        snapshot.setTemplateSha256(template.getSha256());
        snapshot.setContentJson(contentJson);
        snapshot.setContentSha256(contentHash);
        snapshot.setExpiresAt(Date.from(Instant.now().plus(SNAPSHOT_TTL_MINUTES, ChronoUnit.MINUTES)));
        snapshot.setCreatorId(userId);
        dao.insertSnapshot(snapshot);

        result.put("snapshotId", String.valueOf(snapshot.getId()));
        result.put("snapshotHash", contentHash);
        result.put("expiresAt", snapshot.getExpiresAt().toInstant().toString());
        return result;
    }

    /** 完整实例化值进入冻结快照；旧快照不带该字段时保留原兼容路径。 */
    static Map<String, String> variableValues(HarnessTemplate template, Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> values) || values.size() != template.variableTokens().size()) {
            throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String token : template.variableTokens()) {
            Object value = values.get(token);
            if (!(value instanceof String text) || text.isBlank() || text.contains("{{")) {
                throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
            }
            normalized.put(token, text);
        }
        return normalized;
    }

    /**
     * 校验快照归属、摘要与有效期。已消费的快照不能重放；恢复使用原批次而不是再次消费。
     */
    public HarnessSnapshotDO requireSnapshot(long workspaceId, long snapshotId, String contentHash, String type) {
        HarnessSnapshotDO snapshot = dao.findSnapshot(snapshotId, workspaceId);
        if (snapshot == null || !type.equals(snapshot.getSnapshotType())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (contentHash != null && !contentHash.isBlank()
                && !sha256Equals(contentHash, snapshot.getContentSha256())) {
            throw BizException.keyed(ErrorCode.HARNESS_SNAPSHOT_EXPIRED, "harness.snapshot.hash_mismatch",
                    null, "快照摘要不匹配");
        }
        if (snapshot.getConsumedAt() != null || snapshot.getExpiresAt().before(new Date())) {
            throw BizException.keyed(ErrorCode.HARNESS_SNAPSHOT_EXPIRED, "harness.snapshot.consumed",
                    null, "快照已过期或已使用");
        }
        return snapshot;
    }

    public HarnessSnapshotDO consumeSnapshot(HarnessSnapshotDO snapshot) {
        int rows = dao.consumeSnapshot(snapshot.getId(), snapshot.getTenantId(), snapshot.getContentSha256());
        if (rows == 0) {
            throw BizException.keyed(ErrorCode.HARNESS_SNAPSHOT_EXPIRED, "harness.snapshot.consumed",
                    null, "快照已过期或已使用");
        }
        snapshot.setConsumedAt(new Date());
        return snapshot;
    }

    public JSONObject content(HarnessSnapshotDO snapshot) {
        return JSON.parseObject(snapshot.getContentJson());
    }

    private Map<String, Object> executor(String roleCode, String agentName, String clientKind, String familyName,
            ProviderModelCatalogItemVO model, String source, String resolvedAt,
            HarnessTemplateCatalog.ExecutorDefaults defaults) {
        Map<String, Object> executor = new LinkedHashMap<>();
        executor.put("roleCode", roleCode);
        executor.put("agentName", agentName);
        executor.put("name", agentName + defaults.nameSuffix());
        executor.put("clientKind", clientKind);
        executor.put("model", model.getId());
        executor.put("modelLabel", model.getName());
        executor.put("modelFamily", familyName);
        executor.put("modelSource", source);
        executor.put("modelResolvedAt", resolvedAt);
        executor.put("contextWindow", defaults.contextWindow());
        executor.put("reasoningEffort", defaults.reasoningEffort());
        executor.put("maxConcurrentDispatches", defaults.maxConcurrentDispatches());
        executor.put("memoryMode", defaults.memoryMode());
        return executor;
    }

    /**
     * 「最新」由模型标签/ID 中的版本号决定，而不是实时目录返回顺序；版本相同再按 ID 稳定排序。
     */
    private ProviderModelCatalogItemVO newestMatch(ProviderModelCatalogVO liveCatalog, HarnessModelFamily family) {
        if (liveCatalog.getModels() == null) {
            return null;
        }
        return liveCatalog.getModels().stream()
                .filter(model -> family.matches(model.getId(), model.getName()))
                .sorted((left, right) -> {
                    int byVersion = compareVersions(versionText(right), versionText(left));
                    return byVersion != 0 ? byVersion : right.getId().compareTo(left.getId());
                })
                .findFirst().orElse(null);
    }

    private static String versionText(ProviderModelCatalogItemVO model) {
        String label = model.getName();
        return label == null || label.isBlank() ? String.valueOf(model.getId()) : label;
    }

    /** 逐段比较文本中的数字段，缺失段按 0 处理；非数字部分按字典序兜底。 */
    static int compareVersions(String left, String right) {
        String[] leftParts = segments(left);
        String[] rightParts = segments(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            String leftPart = index < leftParts.length ? leftParts[index] : "0";
            String rightPart = index < rightParts.length ? rightParts[index] : "0";
            boolean leftNumeric = leftPart.chars().allMatch(Character::isDigit);
            boolean rightNumeric = rightPart.chars().allMatch(Character::isDigit);
            if (leftNumeric && rightNumeric) {
                int compared = new java.math.BigInteger(leftPart).compareTo(new java.math.BigInteger(rightPart));
                if (compared != 0) {
                    return compared;
                }
            } else {
                int compared = leftPart.compareTo(rightPart);
                if (compared != 0) {
                    return compared;
                }
            }
        }
        return 0;
    }

    private static String[] segments(String value) {
        if (value == null) {
            return new String[0];
        }
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean digits = false;
        for (char character : value.toCharArray()) {
            boolean isDigit = Character.isDigit(character);
            if (current.length() > 0 && isDigit != digits) {
                segments.add(current.toString());
                current.setLength(0);
            }
            digits = isDigit;
            if (isDigit || Character.isLetter(character)) {
                current.append(character);
            } else if (current.length() > 0) {
                segments.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments.toArray(new String[0]);
    }

    private ProviderModelCatalogItemVO find(ProviderModelCatalogVO liveCatalog, String modelId) {
        if (liveCatalog.getModels() == null) {
            return null;
        }
        return liveCatalog.getModels().stream().filter(model -> modelId.equals(model.getId()))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> overrides(Map<String, Object> args) {
        Map<String, Object> raw = WorkspaceOnboardingService.mapArg(args, "modelOverrides");
        return raw == null ? Map.of() : raw;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> overrideFor(Map<String, Object> overrides, String roleCode) {
        Object value = overrides.get(roleCode);
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Object model = map.get("model");
            if (model == null || String.valueOf(model).isBlank()) {
                throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
            }
            return (Map<String, Object>) map;
        }
        if (String.valueOf(value).isBlank()) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return java.util.Map.<String, Object>of("model", String.valueOf(value));
    }

    private Map<String, Object> conflict(String code, String roleCode, String family, String message) {
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("code", code);
        conflict.put("roleCode", roleCode);
        conflict.put("modelFamily", family);
        conflict.put("message", message);
        return conflict;
    }

    private String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return value;
    }

    private long required(Long value, String field) {
        if (value == null) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return value;
    }

    private String firstNonNull(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static boolean sha256Equals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.toLowerCase().getBytes(StandardCharsets.UTF_8),
                right.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
