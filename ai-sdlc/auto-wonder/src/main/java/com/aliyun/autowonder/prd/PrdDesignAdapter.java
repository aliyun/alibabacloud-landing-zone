package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.access.WorkspaceDomain;
import com.aliyun.autowonder.access.WorkspaceDomainGuard;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.ai.adapter.SceneAdapter;
import com.aliyun.autowonder.clarification.ClarificationDO;
import com.aliyun.autowonder.clarification.ClarificationDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.frontendproject.FrontendProjectProfileDO;
import com.aliyun.autowonder.frontendproject.FrontendProjectProfileDao;
import com.aliyun.autowonder.prd.dto.PrdDesignVersionVO;
import com.aliyun.autowonder.prd.dto.SaveDesignRequest;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Generation scene for HTML PRDs, running on the existing {@code ai_session} queue so a run is
 * durable, survives a refresh, is swept when its node dies, and streams progress to the workbench.
 *
 * <p>The model returns structured design data only — never HTML. The document is rendered afterwards
 * by {@link PrdHtmlRenderer} from the parsed model, so an untrusted or malformed HTML fragment from
 * the model cannot reach a stored version. That is also what makes the spec's "禁止分别生成后产生内容
 * 偏差" requirement structural rather than a convention.
 *
 * <p>Tools are disabled for this scene (see {@code AiWorkerPool.allowedToolsFor}): repository access
 * happens in the separate probe scene, and the design is derived from the frozen profile snapshot so
 * the same inputs produce the same design.
 */
@Component
public class PrdDesignAdapter implements SceneAdapter {

    private static final Logger log = LoggerFactory.getLogger(PrdDesignAdapter.class);

    private static final int MAX_CONTEXT_CHARS = 24_000;

    private final PrdDesignDao designDao;
    private final PrdDesignVersionDao versionDao;
    private final PrdDesignService designService;
    private final FrontendProjectProfileDao profileDao;
    private final WorkitemDao workitemDao;
    private final ClarificationDao clarificationDao;
    private final WorkspaceDomainGuard domainGuard;

    public PrdDesignAdapter(PrdDesignDao designDao,
                            PrdDesignVersionDao versionDao,
                            PrdDesignService designService,
                            FrontendProjectProfileDao profileDao,
                            WorkitemDao workitemDao,
                            ClarificationDao clarificationDao,
                            WorkspaceDomainGuard domainGuard) {
        this.designDao = designDao;
        this.versionDao = versionDao;
        this.designService = designService;
        this.profileDao = profileDao;
        this.workitemDao = workitemDao;
        this.clarificationDao = clarificationDao;
        this.domainGuard = domainGuard;
    }

    @Override
    public String scene() {
        return AiConstants.Scene.PRD_DESIGN;
    }

    @Override
    public String buildSystemPrompt(AiSessionDO session) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深产品设计师，负责为 AutoWonder 平台的工单编写 PRD 设计。\n\n");
        sb.append("硬性规则，必须遵守：\n");
        sb.append("1. 仅进行需求澄清与设计，不修改仓库或发起交付。可以读取本次会话提供的截图和附件作为设计参考；目标项目技术事实以用户配置及已有上下文为准，不臆测仓库内容。\n");
        sb.append("2. 最终答案只输出一个 JSON 对象，不要输出解释、前言、后记或 Markdown 代码块标记。\n");
        sb.append("3. 顶层输出结构化 JSON。pages[].mockHtml 必须包含可直接预览的 HTML/CSS 原型，使用模拟数据，禁止外部请求；不要用文字描述代替页面。系统会在隔离预览中渲染它。优先输出 pages 以便用户尽早看到原型。\n");
        sb.append("4. 需求项必须带稳定编号 key（形如 REQ-001）和至少一条 acceptanceCriteria。");
        sb.append("修改既有版本时，沿用原 key，不要重新编号。\n");
        sb.append("5. 画像中标记为「未知项」的内容不得写成既有能力；涉及尚未实现的接口，status 必须填 PLANNED 或 TO_BE_CONFIRMED，");
        sb.append("只有画像证据支持已存在的接口才填 EXISTING。\n");
        sb.append("6. 无法确定的事项写入 openQuestions；会阻塞开发或验收的标记 blocking=true，不要臆测答案。\n");
        sb.append("7. 用户自行编写的名称与文案保持原样，不要翻译或改写。\n\n");
        sb.append("生成内容默认中文；用户指定英文时使用英文并设置 language=en-US。修改版本时保留原 language，除非用户要求切换。\n");
        sb.append("JSON Schema：\n").append(schema());
        return sb.toString();
    }

    @Override
    public String buildUserPrompt(AiSessionDO session, String userInput) {
        Long workitemId = session.getBizRefId();
        StringBuilder sb = new StringBuilder();

        WorkitemDO workitem = workitemId == null ? null : workitemDao.findById(workitemId);
        if (workitem == null || !java.util.Objects.equals(workitem.getTenantId(), session.getTenantId())) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        if (workitem != null) {
            sb.append("【工单需求】\n标题：").append(nz(workitem.getTitle())).append("\n")
              .append(truncate(workitem.getContentMd())).append("\n\n");
        }
        if (workitemId != null) {
            ClarificationDO clarification = clarificationDao.findByWorkitem(workitemId);
            if (clarification != null && clarification.getContentMd() != null
                    && !clarification.getContentMd().isBlank()) {
                sb.append("【需求澄清结论】\n").append(truncate(clarification.getContentMd())).append("\n\n");
            }
        }

        appendTargetProfile(sb, session.getTenantId(), workitemId);
        appendCurrentDesign(sb, session.getTenantId(), workitemId);

        if (userInput != null && !userInput.isBlank()) {
            sb.append("【本次修改意见】\n").append(truncate(userInput)).append("\n\n");
            sb.append("请在当前版本基础上按修改意见产出新的完整 JSON。未涉及的部分保持不变，需求 key 不要重新编号。");
        } else {
            sb.append("请依据上述需求与项目画像，产出完整 PRD 设计 JSON。");
        }
        return sb.toString();
    }

    private void appendTargetProfile(StringBuilder sb, Long workspaceId, Long workitemId) {
        if (workspaceId == null || workitemId == null) {
            return;
        }
        PrdDesignDO design = designDao.findByWorkitem(workspaceId, workitemId);
        if (design != null && design.getTargetConfigJson() != null) {
            PrdProjectSnapshot config = designService.targetSnapshot(design);
            sb.append("【用户配置的目标前端项目】\n")
              .append("仓库 ID：").append(config.getRepoId()).append("\n")
              .append("应用目录：").append(config.getAppRoot()).append("\n")
              .append("项目说明（用户原文）：\n").append(nz(config.getProjectNotes())).append("\n")
              .append("这些信息由用户填写，未经过仓库探测。不得将未提供的信息或接口写成已核实事实。")
              .append("交付沿用仓库现有分支配置。\n\n");
            return;
        }
        if (design == null || design.getTargetProfileId() == null) {
            sb.append("【目标前端项目画像】\n未选定目标项目。本次为概念设计：");
            sb.append("所有页面与接口依赖必须标记为 PLANNED 或 TO_BE_CONFIRMED，并在 openQuestions 中列出")
              .append("blocking=true 的「目标项目未确定」问题。\n\n");
            return;
        }
        FrontendProjectProfileDO profile = profileDao.findById(design.getTargetProfileId(), workspaceId);
        if (profile == null) {
            sb.append("【目标前端项目画像】\n画像不可读取，按未选定目标项目处理。\n\n");
            return;
        }
        sb.append("【目标前端项目画像】\n")
          .append("仓库 ID：").append(profile.getRepoId()).append("\n")
          .append("应用根目录：").append(nz(profile.getAppRoot())).append("\n")
          .append("提交：").append(nz(profile.getCommitSha())).append("\n")
          .append("探测器版本：").append(nz(profile.getDetectorVersion())).append("\n")
          .append(truncate(profile.getProfileJson())).append("\n")
          .append("画像中 facts 为已核实事实，inferences 为推断，unknowns 为未知项。")
          .append("unknowns 不得写成既有能力。\n\n");
    }

    private void appendCurrentDesign(StringBuilder sb, Long workspaceId, Long workitemId) {
        if (workspaceId == null || workitemId == null) {
            return;
        }
        PrdDesignDO design = designDao.findByWorkitem(workspaceId, workitemId);
        if (design == null) {
            return;
        }
        PrdDesignVersionDO latest = versionDao.findLatestByDesign(workspaceId, design.getId());
        if (latest == null || latest.getDesignJson() == null) {
            return;
        }
        sb.append("【当前设计版本 v").append(latest.getVersionNo())
          .append("（").append(latest.getStatus()).append("）】\n")
          .append(truncate(latest.getDesignJson())).append("\n\n");
    }

    @Override
    public String validateResult(String resultJson) {
        BizException error = validateForConfirmation(resultJson);
        return error == null ? null : error.getMessage();
    }

    @Override
    public BizException validateForConfirmation(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return BizException.keyed(ErrorCode.AI_CONFIRM_VALIDATION_FAILED, "ai.validation.null_result",
                    new Object[]{}, "prd design result is empty");
        }
        PrdDesignData data;
        try {
            data = PrdDesignCodec.parse(resultJson);
        } catch (RuntimeException e) {
            return BizException.keyed(ErrorCode.AI_CONFIRM_VALIDATION_FAILED, "ai.validation.invalid_json",
                    new Object[]{e.getMessage()}, "prd design result is not valid JSON: " + e.getMessage());
        }
        if (data.getTitle() == null || data.getTitle().isBlank()) {
            return BizException.keyed(ErrorCode.AI_CONFIRM_VALIDATION_FAILED, "prd.design.title_missing",
                    new Object[]{}, "prd design result has no title");
        }
        if (data.getRequirements().isEmpty()) {
            return BizException.keyed(ErrorCode.AI_CONFIRM_VALIDATION_FAILED, "prd.design.requirements_missing",
                    new Object[]{}, "prd design result has no requirements");
        }
        // The confirmation gate is re-checked at confirm time; surfacing it here lets the user see
        // what still needs work before they attempt to confirm.
        List<String> problems = PrdDesignCodec.normalizeKeys(data);
        if (!problems.isEmpty()) {
            return BizException.keyed(ErrorCode.AI_CONFIRM_VALIDATION_FAILED, "prd.confirmation.blocked",
                    new Object[]{String.join("；", problems)},
                    "prd design not confirmable: " + String.join("; ", problems));
        }
        return null;
    }

    @Override
    public void persistConfirmedResult(AiSessionDO session, String resultJson) {
        domainGuard.requireWritableDomains(session.getTenantId(), AutoWonderContext.get().getUserId(),
                "设计 PRD", "access.action.design_prd", WorkspaceDomain.PRD);
        Long workitemId = session.getBizRefId();
        long userId = AutoWonderContext.get().getUserId();

        // Re-parse and re-render server-side: the stored version's HTML comes from the parsed model,
        // so any HTML the model may have emitted is discarded rather than trusted.
        PrdDesignData data = PrdDesignCodec.parse(resultJson);
        data.setSchemaVersion(PrdDesignData.SCHEMA_VERSION);
        designService.getOrCreate(session.getTenantId(), workitemId, userId);

        SaveDesignRequest request = new SaveDesignRequest();
        request.setDesignJson(PrdDesignCodec.serialize(data));
        request.setAiSessionId(session.getId());
        request.setChangeSummary(data.getChangeLog());
        PrdDesignVersionVO version = designService.saveDraft(session.getTenantId(), workitemId, request, userId);
        log.info("prd design generated sessionId={} workitemId={} versionId={} versionNo={}",
                session.getId(), workitemId, version.getId(), version.getVersionNo());
    }

    private String schema() {
        return """
                {
                  "language": "zh-CN|en-US",
                  "title": "PRD 标题",
                  "background": "背景",
                  "goals": "目标",
                  "userRoles": ["用户角色"],
                  "inScope": ["本次范围"],
                  "nonGoals": ["非目标"],
                  "featureEntries": [{ "location": "入口位置", "capability": "功能" }],
                  "userFlows": [{ "order": 1, "actor": "角色", "action": "动作" }],
                  "pages": [{
                    "pageKey": "页面稳定标识",
                    "name": "页面名称",
                    "route": "路由",
                    "layout": "布局说明",
                    "interactions": ["交互说明"],
                    "normalState": "正常状态",
                    "loadingState": "加载状态",
                    "emptyState": "空状态",
                    "errorState": "错误状态",
                    "forbiddenState": "权限不足状态",
                    "mockHtml": "可选：原型片段，仅用模拟数据"
                  }],
                  "dataFields": [{ "name": "字段名", "type": "类型", "required": true,
                                   "validation": "校验规则", "description": "说明" }],
                  "interfaces": [{ "name": "接口名", "method": "GET", "path": "/api/...",
                                   "status": "EXISTING|PLANNED|TO_BE_CONFIRMED", "description": "说明" }],
                  "targetProjectAdaptation": "与目标项目的适配与复用方案",
                  "requirements": [{ "key": "REQ-001", "title": "需求标题", "description": "需求说明",
                                     "priority": "P0", "acceptanceCriteria": ["验收标准"],
                                     "excluded": false }],
                  "openQuestions": [{ "key": "Q-001", "question": "待确认问题", "blocking": true,
                                      "resolved": false, "resolution": null }],
                  "risks": ["风险"],
                  "changeLog": "本版变更说明"
                }
                """;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_CONTEXT_CHARS
                ? value : value.substring(0, MAX_CONTEXT_CHARS) + "\n…（已截断）";
    }
}
