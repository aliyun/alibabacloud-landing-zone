package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Conflict with a structured reason and the occupying Workspace ids. The workspace names are
 * resolved and permission-filtered at the response boundary.
 */
public class ImIdentityConflictException extends BizException {
    private final ImIdentityConflictReason reason;
    private final List<Long> occupantTenantIds;

    public ImIdentityConflictException(ImIdentityConflictReason reason,
            List<Long> occupantTenantIds) {
        super(ErrorCode.CONFLICT.getCode(), messageKey(reason), new Object[0], diagnostic(reason));
        this.reason = reason;
        this.occupantTenantIds = occupantTenantIds == null
                ? new ArrayList<>() : new ArrayList<>(occupantTenantIds);
    }

    public ImIdentityConflictReason getReason() {
        return reason;
    }

    public List<Long> getOccupantTenantIds() {
        return new ArrayList<>(occupantTenantIds);
    }

    private static String messageKey(ImIdentityConflictReason reason) {
        if (reason == ImIdentityConflictReason.LEGACY_BINDING_OCCUPIED) {
            return "im.legacy_binding_occupied";
        }
        if (reason == ImIdentityConflictReason.NOTIFY_GROUP_IDENTITY_CONFLICT) {
            return "im.notify_identity_conflict";
        }
        return "im.shared_identity_occupied";
    }

    private static String diagnostic(ImIdentityConflictReason reason) {
        if (reason == ImIdentityConflictReason.LEGACY_BINDING_OCCUPIED) {
            return "该机器人身份已被其他项目空间的旧群聊机器人绑定占用";
        }
        if (reason == ImIdentityConflictReason.NOTIFY_GROUP_IDENTITY_CONFLICT) {
            return "通知机器人与群聊机器人必须使用不同的应用身份";
        }
        return "该应用身份已被平台群聊机器人使用";
    }
}
