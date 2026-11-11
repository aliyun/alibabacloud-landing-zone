package com.aliyun.autowonder.workspace.guide;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.mcp.PlatformSkillCatalog;
import com.aliyun.autowonder.mcp.dto.PlatformSkillVO;
import com.aliyun.autowonder.user.UserSettingService;
import com.aliyun.autowonder.user.dto.UserSettingVO;
import com.aliyun.autowonder.workspace.guide.dto.SkipWorkspaceGuideRequest;
import com.aliyun.autowonder.workspace.guide.dto.UpdateWorkspaceGuideRequest;
import com.aliyun.autowonder.workspace.guide.dto.WorkspaceGuideVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新空间 Chief 初始化引导。资格来自空间创建时登记的记录，进度按 workspace_id + user_id 隔离，
 * 写入一律带乐观锁版本，迟到的标签页请求不会覆盖新进度。
 */
@Service
public class WorkspaceGuideService {

    /** 账号级永久关闭偏好；与普通跳过分开保存在用户设置里，跨设备生效。 */
    public static final String DISABLE_SETTING_KEY = "workspace_guide.disabled";
    /** 57450 的空间初始化 Skill；能力以平台技能目录是否已收录为准，不用工单状态猜测。 */
    static final String INITIALIZATION_SKILL_ID = "initialize-autowonder-workspace";

    static final int MAX_STAGE = 4;
    static final int MAX_SUBSTEP = 5;

    private final WorkspaceGuideProgressDao guideDao;
    private final UserSettingService userSettingService;
    private final PlatformSkillCatalog platformSkillCatalog;

    public WorkspaceGuideService(WorkspaceGuideProgressDao guideDao,
                                 UserSettingService userSettingService,
                                 PlatformSkillCatalog platformSkillCatalog) {
        this.guideDao = guideDao;
        this.userSettingService = userSettingService;
        this.platformSkillCatalog = platformSkillCatalog;
    }

    /** 空间创建事务提交后为创建者登记资格；重复登记由唯一键吞掉。 */
    public void registerEligibility(long workspaceId, long userId) {
        WorkspaceGuideProgressDO row = new WorkspaceGuideProgressDO();
        row.setWorkspaceId(workspaceId);
        row.setUserId(userId);
        row.setStatus(WorkspaceGuideStatus.PENDING.name());
        row.setStage(1);
        row.setSubstep(0);
        row.setCreatorId(userId);
        row.setModifierId(userId);
        guideDao.insertIfAbsent(row);
    }

    /** 读取资格与进度。读取账号偏好失败会向上抛出，前端据此显示非阻塞重试而不是强制弹窗。 */
    public WorkspaceGuideVO get(long workspaceId, long userId) {
        WorkspaceGuideProgressDO row = guideDao.findByWorkspaceAndUser(workspaceId, userId);
        WorkspaceGuideVO vo = new WorkspaceGuideVO();
        vo.setCapabilityReady(capabilityReady());
        if (row == null || row.getUserId() == null || row.getUserId() != userId) {
            return vo;
        }
        vo.setGloballyDisabled(globallyDisabled(userId));
        vo.setEligible(true);
        vo.setStatus(row.getStatus());
        vo.setStage(row.getStage());
        vo.setSubstep(row.getSubstep());
        vo.setConversationId(row.getConversationId());
        vo.setClientMessageId(row.getClientMessageId());
        vo.setVersion(row.getVersion());
        return vo;
    }

    /**
     * 推进阶段或子步骤。只允许前进：阶段最多跨一级，子步骤不得回退；
     * 同一阶段/子步骤允许补写会话与去重标识（发送前预留），也允许在阶段 4 置为 COMPLETED。
     */
    @Transactional
    public WorkspaceGuideVO advance(long workspaceId, long userId, UpdateWorkspaceGuideRequest req) {
        if (req == null || req.getStatus() == null || req.getStage() == null
                || req.getSubstep() == null || req.getVersion() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        WorkspaceGuideProgressDO current = requireRow(workspaceId, userId);
        WorkspaceGuideStatus currentStatus = statusOf(current);
        if (currentStatus.terminal()) {
            throw new BizException(ErrorCode.WORKSPACE_GUIDE_STATE_INVALID);
        }
        WorkspaceGuideStatus targetStatus = WorkspaceGuideStatus.from(req.getStatus());
        if (targetStatus == null || targetStatus == WorkspaceGuideStatus.PENDING
                || targetStatus == WorkspaceGuideStatus.SKIPPED) {
            throw new BizException(ErrorCode.WORKSPACE_GUIDE_STATE_INVALID);
        }
        int stage = req.getStage();
        int substep = req.getSubstep();
        if (stage < 1 || stage > MAX_STAGE || substep < 0 || substep > MAX_SUBSTEP) {
            throw new BizException(ErrorCode.WORKSPACE_GUIDE_STATE_INVALID);
        }
        int currentStage = current.getStage() == null ? 1 : current.getStage();
        int currentSubstep = current.getSubstep() == null ? 0 : current.getSubstep();
        if (stage < currentStage || (stage == currentStage && substep < currentSubstep)) {
            throw new BizException(ErrorCode.WORKSPACE_GUIDE_STATE_INVALID);
        }
        if (stage > currentStage + 1) {
            throw new BizException(ErrorCode.WORKSPACE_GUIDE_STATE_INVALID);
        }
        if (targetStatus == WorkspaceGuideStatus.COMPLETED && stage != MAX_STAGE) {
            throw new BizException(ErrorCode.WORKSPACE_GUIDE_STATE_INVALID);
        }
        if (req.getVersion() < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        Long conversationId = req.getConversationId() != null ? req.getConversationId() : current.getConversationId();
        String clientMessageId = req.getClientMessageId() != null ? req.getClientMessageId() : current.getClientMessageId();
        int updated = guideDao.updateProgress(current.getId(), req.getVersion(), targetStatus.name(),
                stage, substep, conversationId, clientMessageId, userId);
        if (updated != 1) {
            throw new BizException(ErrorCode.WORKSPACE_GUIDE_VERSION_CONFLICT);
        }
        return get(workspaceId, userId);
    }

    /** 普通跳过只改当前空间的记录；已跳过时幂等返回，不改动版本。 */
    @Transactional
    public WorkspaceGuideVO skip(long workspaceId, long userId, SkipWorkspaceGuideRequest req) {
        WorkspaceGuideProgressDO current = requireRow(workspaceId, userId);
        WorkspaceGuideStatus currentStatus = statusOf(current);
        if (currentStatus == WorkspaceGuideStatus.COMPLETED) {
            throw new BizException(ErrorCode.WORKSPACE_GUIDE_STATE_INVALID);
        }
        if (currentStatus == WorkspaceGuideStatus.SKIPPED) {
            return get(workspaceId, userId);
        }
        if (req == null || req.getVersion() == null || req.getVersion() < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        int stage = current.getStage() == null ? 1 : current.getStage();
        int substep = current.getSubstep() == null ? 0 : current.getSubstep();
        int updated = guideDao.updateProgress(current.getId(), req.getVersion(),
                WorkspaceGuideStatus.SKIPPED.name(), stage, substep,
                current.getConversationId(), current.getClientMessageId(), userId);
        if (updated != 1) {
            throw new BizException(ErrorCode.WORKSPACE_GUIDE_VERSION_CONFLICT);
        }
        return get(workspaceId, userId);
    }

    private WorkspaceGuideProgressDO requireRow(long workspaceId, long userId) {
        WorkspaceGuideProgressDO row = guideDao.findByWorkspaceAndUser(workspaceId, userId);
        if (row == null || row.getUserId() == null || row.getUserId() != userId) {
            throw new BizException(ErrorCode.WORKSPACE_GUIDE_NOT_ELIGIBLE);
        }
        return row;
    }

    private WorkspaceGuideStatus statusOf(WorkspaceGuideProgressDO current) {
        WorkspaceGuideStatus status = WorkspaceGuideStatus.from(current.getStatus());
        if (status == null) {
            throw new BizException(ErrorCode.WORKSPACE_GUIDE_STATE_INVALID);
        }
        return status;
    }

    private boolean globallyDisabled(long userId) {
        UserSettingVO setting = userSettingService.get(userId, DISABLE_SETTING_KEY);
        String value = setting == null ? null : setting.getValueJson();
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    private boolean capabilityReady() {
        for (PlatformSkillVO skill : platformSkillCatalog.list()) {
            if (INITIALIZATION_SKILL_ID.equals(skill.getId())) {
                return true;
            }
        }
        return false;
    }
}
