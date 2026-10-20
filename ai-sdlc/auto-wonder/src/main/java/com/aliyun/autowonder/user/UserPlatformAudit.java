package com.aliyun.autowonder.user;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 平台级用户操作（删除/恢复/重置密码/编辑资料/工号变更）的审计落库。
 *
 * <p>audit_log.tenant_id 非空但平台级操作没有天然空间归属：统一取目标用户最早加入的活跃空间
 * （与密码重置通知的归属规则一致，见需求 §8.1），目标用户没有活跃空间时退回操作人自己的空间，
 * 两者都没有时跳过并告警——不因归属缺失而中断业务操作。
 */
final class UserPlatformAudit {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserPlatformAudit.class);
    static final String ACTOR_HUMAN = "HUMAN";
    static final String MODULE_USER = "USER";
    static final String TARGET_TYPE_USER = "USER";

    private UserPlatformAudit() {
    }

    static Long resolveTenantId(WorkspaceMemberDao memberDao, Long targetUserId, Long operatorId) {
        Long tenantId = memberDao.findEarliestActiveWorkspaceId(targetUserId);
        return tenantId != null ? tenantId : memberDao.findEarliestActiveWorkspaceId(operatorId);
    }

    static void record(AuditLogService auditLogService, Long tenantId, Long operatorId,
                       String action, Long targetUserId, String targetName) {
        if (tenantId == null) {
            LOGGER.warn("User {} audit skipped: no active workspace for target or operator, operatorId={} targetId={}",
                    action, operatorId, targetUserId);
            return;
        }
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(tenantId);
        record.setActorId(operatorId);
        record.setActorType(ACTOR_HUMAN);
        record.setModule(MODULE_USER);
        record.setAction(action);
        record.setTargetType(TARGET_TYPE_USER);
        record.setTargetId(targetUserId);
        record.setTriggerType("EVENT");
        record.setTriggerSource("WEB");
        record.setEventType(action);
        record.detail("operatorId", operatorId)
                .detail("targetUserId", targetUserId)
                .detail("targetName", targetName);
        auditLogService.recordRequired(record);
    }
}
